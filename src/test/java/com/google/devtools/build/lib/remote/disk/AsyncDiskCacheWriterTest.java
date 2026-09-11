// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote.disk;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import com.google.devtools.build.lib.remote.Store;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests ownership, bounded admission, and persistence independently of command lifetime. */
@RunWith(JUnit4.class)
public class AsyncDiskCacheWriterTest {
  private final DigestUtil digestUtil =
      new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
  private final Path root = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/cache");
  private final Queue<Runnable> tasks = new ArrayDeque<>();
  private DiskCacheClient client;
  private AsyncDiskCacheWriter writer;
  private boolean failWrites;

  @Before
  public void setUp() throws Exception {
    client =
        new DiskCacheClient(root, digestUtil, /* checkActionResultIntegrity= */ true) {
          @Override
          public void saveFile(Digest digest, Store store, InputStream in) throws IOException {
            if (failWrites) {
              throw new IOException("disk full");
            }
            super.saveFile(digest, store, in);
          }
        };
    writer = new AsyncDiskCacheWriter(client, tasks::add, 2, 8);
  }

  @After
  public void tearDown() {
    writer.close();
    while (!tasks.isEmpty()) {
      tasks.remove().run();
    }
    client.close();
  }

  @Test
  public void queuedBytesSurviveClientCloseAndOutputChanges() throws Exception {
    byte[] original = "data".getBytes(UTF_8);
    var digest = digestUtil.compute(original);
    var out = new ByteArrayOutputStream();
    try (var buffer = writer.tryBuffer(original.length)) {
      buffer.write(original);
      try (var entry = buffer.finish()) {
        entry.writeTo(out);
        entry.publish(digest, Store.CAS);
      }
    }
    assertThat(out.toByteArray()).isEqualTo(original);
    assertThat(client.toPath(digest, Store.CAS).exists()).isFalse();
    writer.close();
    client.close();
    out.reset();
    out.write("changed".getBytes(UTF_8));
    tasks.remove().run();
    assertThat(FileSystemUtils.readContent(client.toPath(digest, Store.CAS))).isEqualTo(original);
    assertThat(writer.stats().completed()).isEqualTo(1);
    assertThat(writer.stats().pending()).isEqualTo(0);
  }

  @Test
  public void actionResultIsQueuedWithoutWaitingForDisk() throws Exception {
    var result = ActionResult.newBuilder().setExitCode(1).build();
    var key = digestUtil.compute("action".getBytes(UTF_8));
    writer.uploadActionResult(key, result);
    assertThat(client.toPath(key, Store.AC).exists()).isFalse();
    tasks.remove().run();
    assertThat(ActionResult.parseFrom(FileSystemUtils.readContent(client.toPath(key, Store.AC))))
        .isEqualTo(result);
  }

  @Test
  public void byteBudgetIncludesDownloadsAndQueuedWrites() throws Exception {
    try (var buffer = writer.tryBuffer(8)) {
      assertThat(writer.tryBuffer(1)).isNull();
      buffer.write(new byte[8]);
      try (var entry = buffer.finish()) {
        buffer.close();
        assertThat(writer.tryBuffer(1)).isNull();
        entry.publish(digestUtil.compute(new byte[8]), Store.CAS);
      }
    }
    assertThat(writer.tryBuffer(1)).isNull();
    tasks.remove().run();
    try (var buffer = writer.tryBuffer(8)) {
      assertThat(buffer).isNotNull();
      assertThat(writer.stats().peakPendingBytes()).isEqualTo(8);
    }
  }

  @Test
  public void entryBudgetAlsoBoundsEmptyBuffers() {
    try (var first = writer.tryBuffer(0);
        var second = writer.tryBuffer(0)) {
      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
      assertThat(writer.tryBuffer(0)).isNull();
    }
    assertThat(writer.stats().pending()).isEqualTo(0);
  }

  @Test
  public void cancellationReleasesBudgetAndRejectsLateNetworkWrites() throws Exception {
    var buffer = writer.tryBuffer(8);
    buffer.write(new byte[2]);
    buffer.close();
    buffer.close();
    assertThrows(IOException.class, () -> buffer.write(new byte[6]));
    assertThrows(IOException.class, buffer::finish);
    assertThat(writer.stats().pending()).isEqualTo(0);
    try (var next = writer.tryBuffer(8)) {
      assertThat(next).isNotNull();
    }
  }

  @Test
  public void incompleteAndOversizedDownloadsAreRejected() throws Exception {
    try (var buffer = writer.tryBuffer(4)) {
      buffer.write(new byte[3]);
      assertThrows(IOException.class, buffer::finish);
      assertThrows(IOException.class, () -> buffer.write(new byte[2]));
    }
    assertThat(writer.stats().pendingBytes()).isEqualTo(0);
    assertThat(writer.tryBuffer(AsyncDiskCacheWriter.MAX_BLOB_SIZE + 1L)).isNull();
  }

  @Test
  public void failedOutputDeliveryDoesNotQueueACacheWrite() throws Exception {
    try (var buffer = writer.tryBuffer(4)) {
      buffer.write(new byte[4]);
      try (var entry = buffer.finish()) {
        OutputStream brokenOutput =
            new OutputStream() {
              @Override
              public void write(int value) throws IOException {
                throw new IOException("output failed");
              }
            };
        assertThrows(IOException.class, () -> entry.writeTo(brokenOutput));
      }
    }
    assertThat(tasks).isEmpty();
    assertThat(writer.stats().pending()).isEqualTo(0);
  }

  @Test
  public void rejectedTaskReleasesItsReservation() throws Exception {
    writer.close();
    writer =
        new AsyncDiskCacheWriter(
            client,
            task -> {
              throw new RejectedExecutionException("stopped");
            },
            1,
            4);
    try (var buffer = writer.tryBuffer(4)) {
      buffer.write(new byte[4]);
      try (var entry = buffer.finish()) {
        entry.publish(digestUtil.compute(new byte[4]), Store.CAS);
      }
    }
    assertThat(writer.stats().pending()).isEqualTo(0);
    assertThat(writer.stats().queued()).isEqualTo(0);
    assertThat(writer.stats().skipped()).isEqualTo(1);
    try (var next = writer.tryBuffer(4)) {
      assertThat(next).isNotNull();
    }
  }

  @Test
  public void failedWriteIsCountedAndReleasesItsReservation() throws Exception {
    failWrites = true;
    try (var buffer = writer.tryBuffer(4)) {
      buffer.write(new byte[4]);
      try (var entry = buffer.finish()) {
        entry.publish(digestUtil.compute(new byte[4]), Store.CAS);
      }
    }
    tasks.remove().run();
    assertThat(writer.stats().failed()).isEqualTo(1);
    assertThat(writer.stats().completed()).isEqualTo(0);
    assertThat(writer.stats().pending()).isEqualTo(0);
    assertThat(client.toPath(digestUtil.compute(new byte[4]), Store.CAS).exists()).isFalse();
  }

  @Test
  public void closeRejectsNewBuffersButPreservesExistingOwnership() throws Exception {
    try (var buffer = writer.tryBuffer(4)) {
      writer.close();
      assertThat(writer.tryBuffer(1)).isNull();
      buffer.write(new byte[4]);
      try (var entry = buffer.finish()) {
        entry.publish(digestUtil.compute(new byte[4]), Store.CAS);
      }
    }
    tasks.remove().run();
    assertThat(writer.stats().completed()).isEqualTo(1);
    assertThat(writer.stats().pending()).isEqualTo(0);
  }

  @Test
  public void admissionBudgetIsSharedAcrossClientsAndSurvivesClose() throws Exception {
    var first = new DiskCacheClient(root, digestUtil, true, true);
    var second = new DiskCacheClient(root, digestUtil, true, true);
    var buffers = new ArrayList<AsyncDiskCacheWriter.Buffer>();
    try {
      AsyncDiskCacheWriter.Buffer buffer;
      while ((buffer = first.getAsyncWriter().tryBuffer(0)) != null) {
        buffers.add(buffer);
        assertThat(buffers.size()).isAtMost(131072);
      }
      assertThat(buffers).isNotEmpty();
      first.close();
      assertThat(second.getAsyncWriter().tryBuffer(0)).isNull();
      buffers.remove(0).close();
      try (var admitted = second.getAsyncWriter().tryBuffer(0)) {
        assertThat(admitted).isNotNull();
      }
    } finally {
      buffers.forEach(AsyncDiskCacheWriter.Buffer::close);
      first.close();
      second.close();
    }
  }
}
