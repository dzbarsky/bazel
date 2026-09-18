// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0

package com.google.devtools.build.lib.bazel;

import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.exec.ExecutionLogServiceGrpc;
import com.google.devtools.build.lib.exec.ExecutionLogStreamRequest;
import com.google.devtools.build.lib.exec.ExecutionLogStreamResponse;
import com.google.devtools.build.lib.exec.Finish;
import com.google.devtools.build.lib.exec.Header;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Streams compact execution-log bytes to a dedicated gRPC service as they are produced. */
final class ExecutionLogGrpcOutputStream extends OutputStream {
  private static final long CLOSE_TIMEOUT_SECONDS = 30;
  private static final int MAX_CHUNK_BYTES = 64 * 1024;
  private static final long MAX_CHUNK_DELAY_MILLIS = 100;

  private final ManagedChannel channel;
  private final StreamObserver<ExecutionLogStreamRequest> requests;
  private final CountDownLatch responseDone = new CountDownLatch(1);
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicReference<ExecutionLogStreamResponse> response = new AtomicReference<>();
  private final Hasher hasher = Hashing.sha256().newHasher();
  private final ByteArrayOutputStream pending = new ByteArrayOutputStream(MAX_CHUNK_BYTES);
  private final ScheduledExecutorService flusher =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "execution-log-grpc-flusher");
            thread.setDaemon(true);
            return thread;
          });
  private long sizeBytes;
  private boolean closed;

  ExecutionLogGrpcOutputStream(String endpoint, String invocationId, String logName) {
    String target = endpoint.startsWith("grpc://") ? endpoint.substring("grpc://".length()) : endpoint;
    channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
    requests =
        ExecutionLogServiceGrpc.newStub(channel)
            .stream(
                new StreamObserver<>() {
                  @Override
                  public void onNext(ExecutionLogStreamResponse value) {
                    response.set(value);
                  }

                  @Override
                  public void onError(Throwable error) {
                    failure.compareAndSet(null, error);
                    responseDone.countDown();
                  }

                  @Override
                  public void onCompleted() {
                    responseDone.countDown();
                  }
                });
    requests.onNext(
        ExecutionLogStreamRequest.newBuilder()
            .setHeader(
                Header.newBuilder()
                    .setInvocationId(invocationId)
                    .setLogName(logName)
                    .setFormat("compact")
                    .setCompression("zstd"))
            .build());
    flusher.scheduleAtFixedRate(
        this::flushFromTimer,
        MAX_CHUNK_DELAY_MILLIS,
        MAX_CHUNK_DELAY_MILLIS,
        TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void write(int value) throws IOException {
    write(new byte[] {(byte) value}, 0, 1);
  }

  @Override
  public synchronized void write(byte[] data, int offset, int length) throws IOException {
    if (closed) {
      throw new IOException("execution-log stream is closed");
    }
    throwIfFailed();
    if (length == 0) {
      return;
    }
    hasher.putBytes(data, offset, length);
    sizeBytes += length;
    pending.write(data, offset, length);
    if (pending.size() >= MAX_CHUNK_BYTES) {
      flushPending();
    }
  }

  /**
   * The compact-log writer flushes after each record so zstd emits bytes promptly. Keep those
   * bytes locally until either a small time bound or chunk size is reached.
   */
  @Override
  public synchronized void flush() throws IOException {
    throwIfFailed();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      throwIfFailed();
      flushPending();
      String digest = hasher.hash().toString();
      requests.onNext(
          ExecutionLogStreamRequest.newBuilder()
              .setFinish(Finish.newBuilder().setSizeBytes(sizeBytes).setSha256(digest))
              .build());
      requests.onCompleted();
      if (!responseDone.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new IOException("timed out waiting for execution-log stream response");
      }
      throwIfFailed();
      ExecutionLogStreamResponse result = response.get();
      if (result == null
          || result.getCommittedSize() != sizeBytes
          || !result.getSha256().equals(digest)) {
        throw new IOException("execution-log stream response failed integrity validation");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while closing execution-log stream", e);
    } finally {
      flusher.shutdownNow();
      channel.shutdownNow();
    }
  }

  private synchronized void flushFromTimer() {
    if (closed || pending.size() == 0) {
      return;
    }
    try {
      flushPending();
    } catch (IOException e) {
      failure.compareAndSet(null, e);
    }
  }

  private void flushPending() throws IOException {
    if (pending.size() == 0) {
      return;
    }
    byte[] data = pending.toByteArray();
    pending.reset();
    try {
      requests.onNext(
          ExecutionLogStreamRequest.newBuilder().setData(ByteString.copyFrom(data)).build());
    } catch (RuntimeException e) {
      failure.compareAndSet(null, e);
      throw new IOException("failed to stream execution log", e);
    }
  }

  private void throwIfFailed() throws IOException {
    Throwable error = failure.get();
    if (error != null) {
      throw new IOException("execution-log stream failed", error);
    }
  }
}
