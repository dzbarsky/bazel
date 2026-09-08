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

import static com.google.common.base.Preconditions.checkState;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.remote.Store;
import com.google.devtools.build.lib.util.DebugLoggerConfigurator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Best-effort population of small disk-cache entries from remote hits.
 *
 * <p>Buffers are reserved before downloading and owned independently of action outputs. A shared
 * budget bounds payload bytes and entries across commands. Closing stops admission, but does not
 * wait for writes. Daemon workers may lose pending entries on server exit; completed entries retain
 * the ordinary disk cache's fsync, atomic publication, and garbage-collection behavior.
 */
public final class AsyncDiskCacheWriter implements AutoCloseable {
  public static final int MAX_BLOB_SIZE = 64 * 1024;

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final AtomicLong nextId = new AtomicLong();

  private static final class Budget {
    final Semaphore entries;
    final Semaphore bytes;

    Budget(int entries, int bytes) {
      this.entries = new Semaphore(entries);
      this.bytes = new Semaphore(bytes);
    }
  }

  private static final class Shared {
    static final Budget BUDGET = new Budget(131072, 1024 * 1024 * 1024);
    static final ThreadPoolExecutor EXECUTOR =
        new ThreadPoolExecutor(
            32,
            32,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(131072),
            new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("disk-cache-background-%d")
                .build());

    static {
      EXECUTOR.allowCoreThreadTimeOut(true);
    }
  }

  private final DiskCacheClient client;
  private final long id = nextId.incrementAndGet();
  private final String cacheName;
  private final Executor executor;
  private final Budget budget;

  // Guarded by this. The monitor is never held during cache I/O.
  private boolean closed;
  private boolean reportedFinished;
  private long queued;
  private long completed;
  private long failed;
  private long skipped;
  private long abandoned;
  private int pending;
  private long pendingBytes;
  private long peakPendingBytes;
  private long completedBytes;

  AsyncDiskCacheWriter(DiskCacheClient client, String cacheName) {
    this(client, cacheName, Shared.EXECUTOR, Shared.BUDGET);
  }

  AsyncDiskCacheWriter(DiskCacheClient client, Executor executor, int maxEntries, int maxBytes) {
    this(client, "test", executor, new Budget(maxEntries, maxBytes));
  }

  private AsyncDiskCacheWriter(
      DiskCacheClient client, String cacheName, Executor executor, Budget budget) {
    this.client = client;
    this.cacheName = cacheName;
    this.executor = executor;
    this.budget = budget;
  }

  /** Returns null instead of waiting when admission would exceed the shared budget. */
  @Nullable
  public synchronized Buffer tryBuffer(long size) {
    if (closed || size < 0 || size > MAX_BLOB_SIZE || !budget.entries.tryAcquire()) {
      skipped++;
      return null;
    }
    if (!budget.bytes.tryAcquire((int) size)) {
      budget.entries.release();
      skipped++;
      return null;
    }
    pending++;
    pendingBytes += size;
    peakPendingBytes = Math.max(peakPendingBytes, pendingBytes);
    return new Buffer((int) size);
  }

  public void uploadActionResult(Digest digest, ActionResult result) {
    try (Buffer buffer = tryBuffer(result.getSerializedSize())) {
      if (buffer == null) {
        return;
      }
      result.writeTo(buffer);
      try (Entry entry = buffer.finish()) {
        entry.publish(digest, Store.AC);
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log(
          "Could not buffer an asynchronous disk-cache action result");
    }
  }

  /** A fixed-size download buffer. Cancellation releases it and rejects subsequent writes. */
  public final class Buffer extends OutputStream {
    @Nullable private byte[] data;
    private int position;

    private Buffer(int size) {
      data = new byte[size];
    }

    @Override
    public synchronized void write(int value) throws IOException {
      if (data == null || position == data.length) {
        throw new IOException("Closed or overflowing disk-cache download buffer");
      }
      data[position++] = (byte) value;
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (data == null || length > data.length - position) {
        throw new IOException("Closed or overflowing disk-cache download buffer");
      }
      System.arraycopy(bytes, offset, data, position, length);
      position += length;
    }

    /** Transfers ownership, including the reservation, without copying the payload. */
    public synchronized Entry finish() throws IOException {
      if (data == null || position != data.length) {
        throw new IOException("Incomplete disk-cache download buffer");
      }
      Entry entry = new Entry(data);
      data = null;
      return entry;
    }

    @Override
    public synchronized void close() {
      if (data != null) {
        release(data.length, false, false);
        data = null;
      }
    }
  }

  /** An owned payload that can be delivered to the build and then queued for persistence. */
  public final class Entry implements AutoCloseable {
    private final byte[] data;
    private boolean submitted;
    private boolean released;

    private Entry(byte[] data) {
      this.data = data;
    }

    public void writeTo(OutputStream out) throws IOException {
      checkState(!submitted && !released);
      out.write(data);
      out.flush();
    }

    public void publish(Digest digest, Store store) {
      checkState(!submitted && !released);
      submitted = true;
      synchronized (AsyncDiskCacheWriter.this) {
        queued++;
      }
      try {
        executor.execute(
            () -> {
              boolean success = false;
              try (var in = new ByteArrayInputStream(data)) {
                // Uses only cache-owned bytes and paths, even after the client's foreground
                // executor has closed. Never reopen an action output in this task.
                client.saveFile(digest, store, in);
                success = true;
              } catch (IOException | RuntimeException e) {
                logger.atWarning().atMostEvery(1, TimeUnit.MINUTES).withCause(e).log(
                    "Asynchronous disk-cache write failed");
              } finally {
                release(data.length, true, success);
              }
            });
      } catch (RejectedExecutionException e) {
        submitted = false;
        synchronized (AsyncDiskCacheWriter.this) {
          queued--;
          skipped++;
        }
      }
    }

    @Override
    public void close() {
      if (!submitted && !released) {
        released = true;
        release(data.length, false, false);
      }
    }
  }

  private synchronized void release(int size, boolean wasQueued, boolean success) {
    budget.bytes.release(size);
    budget.entries.release();
    pending--;
    pendingBytes -= size;
    if (!wasQueued) {
      abandoned++;
    } else if (success) {
      completed++;
      completedBytes += size;
    } else {
      failed++;
    }
    reportFinished();
  }

  record Stats(
      long queued,
      long completed,
      long failed,
      long skipped,
      long abandoned,
      int pending,
      long pendingBytes,
      long peakPendingBytes,
      long completedBytes) {}

  synchronized Stats stats() {
    return new Stats(
        queued,
        completed,
        failed,
        skipped,
        abandoned,
        pending,
        pendingBytes,
        peakPendingBytes,
        completedBytes);
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      closed = true;
      report("closed");
      reportFinished();
    }
  }

  private void reportFinished() {
    if (closed && pending == 0 && !reportedFinished) {
      reportedFinished = true;
      report("finished");
      // The normal command-end flush can precede this background completion.
      DebugLoggerConfigurator.flushServerLog();
    }
  }

  private void report(String phase) {
    logger.atInfo().log(
        "Async disk cache %s: writer=%d cache=%s %s", phase, id, cacheName, stats());
  }
}
