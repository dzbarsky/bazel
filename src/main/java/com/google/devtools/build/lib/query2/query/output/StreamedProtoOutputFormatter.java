// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query.output;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ExecutorUtil;
import com.google.devtools.build.lib.concurrent.MoreFutures;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.query2.engine.OutputFormatterCallback;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import javax.annotation.Nullable;

/**
 * An output formatter that outputs a protocol buffer representation of a query result and outputs
 * the proto bytes to the output print stream. By taking the bytes and calling {@code mergeFrom()}
 * on a {@code Build.QueryResult} object the full result can be reconstructed.
 */
public class StreamedProtoOutputFormatter extends ProtoOutputFormatter
    implements HashOutputFormatter {
  private static final int OUTPUT_PARALLELISM = Runtime.getRuntime().availableProcessors();

  // At the issue's reported average target size, this produces roughly 0.5 MB chunks: large enough
  // to amortize task overhead without retaining the roughly 5 MB chunks used by the previous PR.
  @VisibleForTesting static final int TARGETS_PER_CHUNK = 50;

  private static final int HASH_OUTPUT_PARALLELISM = Math.min(4, OUTPUT_PARALLELISM);

  private static final ThreadFactory THREAD_FACTORY =
      Thread.ofPlatform().daemon().name("streamed-proto-output-", 0).factory();

  private boolean parallelHashOutput;

  @Override
  public void setOptions(
      CommonQueryOptions options, AspectResolver aspectResolver, HashFunction hashFunction) {
    super.setOptions(options, aspectResolver, hashFunction);
    parallelHashOutput =
        options instanceof QueryOptions query
            && query.getParallelStreamedProtoOutput()
            && !query.getProtoRuleClasses()
            && HASH_OUTPUT_PARALLELISM > 1;
  }

  @Override
  public String getName() {
    return "streamed_proto";
  }

  @Override
  public void writeHashedTargets(
      Iterable<Target> targets,
      LabelPrinter labelPrinter,
      Map<Label, String> hashes,
      OutputStream out)
      throws IOException, InterruptedException {
    if (parallelHashOutput) {
      var callback = new ParallelCallback(out, labelPrinter, hashes, HASH_OUTPUT_PARALLELISM);
      try {
        callback.processOutput(targets);
      } finally {
        callback.shutdown();
      }
      return;
    }
    CodedOutputStream codedOut = CodedOutputStream.newInstance(out);
    for (Target target : targets) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      codedOut.writeMessageNoTag(
          toTargetWithHash(target, labelPrinter, hashes.get(target.getLabel())));
    }
    codedOut.flush();
  }

  private Build.Target toTargetWithHash(
      Target target, LabelPrinter labelPrinter, @Nullable String digest)
      throws InterruptedException {
    Build.Target record = toTargetProtoBuffer(target, labelPrinter);
    return digest == null
        ? record
        : record.toBuilder().setTransitiveHash(ByteString.fromHex(digest)).build();
  }

  @Override
  public OutputFormatterCallback<Target> createPostFactoStreamCallback(
      final OutputStream out, final QueryOptions options, LabelPrinter labelPrinter) {
    // rule_class_info is emitted for the first rule in the stream with a given key. Parallel target
    // construction would race that stateful first-seen decision even though writes remain ordered.
    if (options.getParallelStreamedProtoOutput() && !options.getProtoRuleClasses()) {
      return new ParallelCallback(out, labelPrinter, /* hashes= */ null, OUTPUT_PARALLELISM);
    }
    return new OutputFormatterCallback<Target>() {
      private final CodedOutputStream codedOut = CodedOutputStream.newInstance(out);

      @Override
      public void processOutput(Iterable<Target> partialResult)
          throws IOException, InterruptedException {
        for (Target target : partialResult) {
          if (Thread.interrupted()) {
            throw new InterruptedException();
          }
          codedOut.writeMessageNoTag(toTargetProtoBuffer(target, labelPrinter));
          codedOut.flush();
        }
      }
    };
  }

  /** Serializes bounded chunks in parallel and writes their bytes in input order. */
  private final class ParallelCallback extends OutputFormatterCallback<Target> {
    private final OutputStream out;
    private final LabelPrinter labelPrinter;
    @Nullable private final Map<Label, String> hashes;
    private final ExecutorService executor;
    private final int maxPendingChunks;

    private ParallelCallback(
        OutputStream out,
        LabelPrinter labelPrinter,
        @Nullable Map<Label, String> hashes,
        int parallelism) {
      this.out = out;
      this.labelPrinter = labelPrinter;
      this.hashes = hashes;
      this.executor = Executors.newFixedThreadPool(parallelism, THREAD_FACTORY);
      // Keep enough work queued while the head chunk is being written.
      this.maxPendingChunks = 2 * parallelism;
    }

    @Override
    public void processOutput(Iterable<Target> partialResult)
        throws IOException, InterruptedException {
      // Only this thread writes. Draining futures from the front preserves input order even when
      // later chunks finish first. The bounded deque also stops consuming input when output lags.
      Deque<Future<byte[]>> pendingChunks = new ArrayDeque<>();
      try {
        for (List<Target> targets : Iterables.partition(partialResult, TARGETS_PER_CHUNK)) {
          if (pendingChunks.size() == maxPendingChunks) {
            writeChunk(pendingChunks.removeFirst());
          }
          pendingChunks.addLast(executor.submit(() -> serializeChunk(targets)));
        }
        while (!pendingChunks.isEmpty()) {
          writeChunk(pendingChunks.removeFirst());
        }
      } finally {
        pendingChunks.forEach(future -> future.cancel(/* mayInterruptIfRunning= */ true));
      }
    }

    private byte[] serializeChunk(List<Target> targets) throws IOException, InterruptedException {
      // Serialization is part of the measured bottleneck, and bytes retain less live object graph
      // than completed Build.Target protos while they wait for ordered output. Target proto sizes
      // vary substantially, so a workload-specific initial capacity could overallocate heavily.
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      CodedOutputStream codedOut = CodedOutputStream.newInstance(bytes);
      for (Target target : targets) {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
        codedOut.writeMessageNoTag(
            toTargetWithHash(
                target, labelPrinter, hashes == null ? null : hashes.get(target.getLabel())));
      }
      codedOut.flush();
      return bytes.toByteArray();
    }

    private void writeChunk(Future<byte[]> chunk) throws IOException, InterruptedException {
      out.write(
          MoreFutures.waitForFutureAndGetWithCheckedException(
              chunk, /* cancelOnInterrupt= */ true, IOException.class));
    }

    @Override
    public void close(boolean failFast) {
      shutdown();
    }

    private void shutdown() {
      // processOutput drains all work on success. On failure, cancel remaining work without
      // replacing the primary query, output, or interruption failure during cleanup.
      if (!executor.isShutdown() && ExecutorUtil.uninterruptibleShutdownNow(executor)) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
