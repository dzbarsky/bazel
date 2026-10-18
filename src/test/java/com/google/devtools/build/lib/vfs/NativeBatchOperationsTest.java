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
package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NativeBatchOperationsTest {
  private static Map<PathFragment, PathFragment> links() {
    Map<PathFragment, PathFragment> links = new LinkedHashMap<>();
    links.put(PathFragment.create("first"), PathFragment.create("/target1"));
    links.put(PathFragment.create("second"), PathFragment.create("/target2"));
    return links;
  }

  private static final class PartialFileSystem extends InMemoryFileSystem {
    final List<String> readbacks = new ArrayList<>();
    boolean ambiguousFailure;

    PartialFileSystem() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public int[] createSymlinksBatch(
        PathFragment root, List<PathFragment> paths, List<PathFragment> targets)
        throws IOException {
      getPath(root.getRelative(paths.get(0))).createSymbolicLink(targets.get(0));
      if (ambiguousFailure) {
        throw new IOException("completion unknown");
      }
      return new int[] {0, 17};
    }

    @Override
    public PathFragment readSymbolicLink(PathFragment path) throws IOException {
      readbacks.add(path.getBaseName());
      return super.readSymbolicLink(path);
    }
  }

  @Test
  public void reconcilesOnlyFailedSymlinks() throws Exception {
    PartialFileSystem fs = new PartialFileSystem();
    Path root = fs.getPath("/tree");
    root.createDirectory();
    root.getChild("second").createSymbolicLink(PathFragment.create("/old"));
    new NativeBatchOperations(Runnable::run, 0, 0, 4).createSymlinks(root, links());
    assertThat(fs.readbacks).containsExactly("second");
    assertThat(root.getChild("first").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target1"));
    assertThat(root.getChild("second").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target2"));
  }

  @Test
  public void ambiguousFailureDoesNotReplayOrContinue() throws Exception {
    PartialFileSystem fs = new PartialFileSystem();
    fs.ambiguousFailure = true;
    Path root = fs.getPath("/tree");
    root.createDirectory();
    IOException error =
        assertThrows(
            IOException.class,
            () -> new NativeBatchOperations(Runnable::run, 0, 0, 4).createSymlinks(root, links()));
    assertThat(error).hasMessageThat().contains("completion unknown");
    assertThat(fs.readbacks).isEmpty();
    assertThat(root.getChild("first").isSymbolicLink()).isTrue();
    assertThat(root.getChild("second").exists(Symlinks.NOFOLLOW)).isFalse();
  }

  @Test
  public void concurrentFailureDrainsSubmittedBatches() throws Exception {
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch failed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger finished = new AtomicInteger();
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public int[] createSymlinksBatch(
              PathFragment root, List<PathFragment> paths, List<PathFragment> targets)
              throws IOException {
            int call = calls.getAndIncrement();
            started.countDown();
            try {
              if (call == 0) {
                if (!started.await(10, TimeUnit.SECONDS)) {
                  throw new IOException("batches did not start concurrently");
                }
                throw new IOException("completion unknown");
              }
              if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("batch was not released");
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            finished.incrementAndGet();
            return new int[paths.size()];
          }
        };
    Path root = fs.getPath("/tree");
    root.createDirectory();
    Map<PathFragment, PathFragment> symlinks = new LinkedHashMap<>();
    for (int i = 0; i < 5 * 4096; i++) {
      symlinks.put(PathFragment.create("link" + i), PathFragment.create("/target"));
    }
    try (var workers = Executors.newFixedThreadPool(4);
        var caller = Executors.newSingleThreadExecutor()) {
      var result =
          caller.submit(
              () -> {
                new NativeBatchOperations(
                        command ->
                            workers.execute(
                                () -> {
                                  command.run();
                                  // Publish the failed future before releasing any successful
                                  // batch.
                                  failed.countDown();
                                }),
                        0,
                        0,
                        4)
                    .createSymlinks(root, symlinks);
                return null;
              });
      try {
        assertThat(failed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(result.isDone()).isFalse();
        assertThat(calls.get()).isEqualTo(4);
        release.countDown();
        var error = assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        assertThat(error).hasCauseThat().hasMessageThat().contains("completion unknown");
        assertThat(finished.get()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(4);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  public void serialConcurrencyKeepsEveryChunkOnCaller() throws Exception {
    List<Integer> batches = new ArrayList<>();
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public int[] createSymlinksBatch(
              PathFragment root, List<PathFragment> paths, List<PathFragment> targets) {
            batches.add(paths.size());
            return new int[paths.size()];
          }
        };
    Path root = fs.getPath("/tree");
    root.createDirectory();
    Map<PathFragment, PathFragment> symlinks = new LinkedHashMap<>();
    for (int i = 0; i < 4097; i++) {
      symlinks.put(PathFragment.create("link" + i), PathFragment.create("/target"));
    }
    new NativeBatchOperations(
            command -> {
              throw new AssertionError("Serial mode must not dispatch work");
            },
            0,
            0,
            1)
        .createSymlinks(root, symlinks);
    assertThat(batches).containsExactly(4096, 1).inOrder();
  }

  @Test
  public void unsupportedFilesystemCreatesLinksLocally() throws Exception {
    Path root = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/tree");
    root.createDirectory();
    new NativeBatchOperations(Runnable::run, 0, 0, 4).createSymlinks(root, links());
    assertThat(root.getChild("first").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target1"));
    assertThat(root.getChild("second").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target2"));
  }

  @Test
  public void directoryFailurePreservesPrefixAcrossChunks() throws Exception {
    List<Integer> batches = new ArrayList<>();
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public int createDirectoriesBatch(PathFragment root, List<PathFragment> paths)
              throws IOException {
            batches.add(paths.size());
            int count = 0;
            for (PathFragment path : paths) {
              if (!getPath(root.getRelative(path)).createDirectory()) {
                break;
              }
              count++;
            }
            return count;
          }
        };
    Path root = fs.getPath("/tree");
    root.createDirectory();
    root.getChild("d4096").createDirectory();
    List<PathFragment> paths =
        IntStream.range(0, 4098).mapToObj(i -> PathFragment.create("d" + i)).toList();
    assertThat(new NativeBatchOperations(Runnable::run, 0, 0, 4).createDirectories(root, paths))
        .isEqualTo(4096);
    assertThat(batches).containsExactly(4096, 2).inOrder();
    assertThat(root.getChild("d4095").isDirectory()).isTrue();
    assertThat(root.getChild("d4097").exists()).isFalse();
  }
}
