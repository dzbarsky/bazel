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
package com.google.devtools.build.lib.remote;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.BatchStatRequest;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.BatchSymlinkRequest;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.BatchSymlinkResponse;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.FileStat;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.Symlink;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.OutputService.BatchOperations;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.protobuf.ByteString;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A partial output-service client. The ordinary output service retains all other responsibilities.
 */
final class BatchOutputServiceClient implements BatchOperations {
  private static final int MAX_ENTRIES = 4096;
  private static final int MAX_PATH_BYTES = 1024 * 1024;
  private static final int MAX_SYMLINK_REQUESTS = 4;
  private final BatchOutputServiceGrpc.BatchOutputServiceBlockingStub stub;
  private final BatchOutputServiceGrpc.BatchOutputServiceFutureStub symlinkStub;
  private final Supplier<Path> execRoot;
  private final int symlinkMinEntries;
  private final int statConcurrency;
  private volatile boolean statSupported = true;
  private volatile boolean symlinkSupported = true;

  BatchOutputServiceClient(
      Channel channel, Supplier<Path> execRoot, int symlinkMinEntries, int statConcurrency) {
    this.stub = BatchOutputServiceGrpc.newBlockingStub(channel);
    this.symlinkStub = BatchOutputServiceGrpc.newFutureStub(channel);
    this.execRoot = execRoot;
    this.symlinkMinEntries = symlinkMinEntries;
    this.statConcurrency = statConcurrency;
  }

  private static ByteString pathBytes(PathFragment path) {
    return ByteString.copyFrom(path.getSafePathString(), ISO_8859_1);
  }

  @Override
  public int getMaxConcurrentCalls() {
    return statConcurrency;
  }

  @Override
  public List<FileStatusWithDigest> batchStat(Iterable<PathFragment> paths) throws IOException {
    if (!statSupported) {
      throw new IOException("Batch stat is unavailable");
    }
    Path root = execRoot.get();
    var request = BatchStatRequest.newBuilder().setDirectory(pathBytes(root.asFragment()));
    List<FileStatusWithDigest> results = new ArrayList<>();
    int bytes = 0;
    for (PathFragment path : paths) {
      // Match Path.getRelative's lexical handling of leading '..' before crossing the RPC.
      Path absolute = root.getRelative(path);
      if (!absolute.startsWith(root)) {
        throw new IOException("Batch stat path is outside the execution root");
      }
      ByteString encoded = pathBytes(absolute.relativeTo(root));
      if (request.getPathsCount() > 0
          && (request.getPathsCount() == MAX_ENTRIES || bytes + encoded.size() > MAX_PATH_BYTES)) {
        stat(request.build(), results);
        request.clearPaths();
        bytes = 0;
      }
      request.addPaths(encoded);
      bytes += encoded.size();
    }
    if (request.getPathsCount() > 0) {
      stat(request.build(), results);
    }
    return results;
  }

  @SuppressWarnings("try")
  private void stat(BatchStatRequest request, List<FileStatusWithDigest> results)
      throws IOException {
    try (var ignored = Profiler.instance().profile("Batch output service stat")) {
      var response = stub.withDeadlineAfter(30, TimeUnit.SECONDS).batchStat(request);
      if (response.getStatsCount() != request.getPathsCount()) {
        throw new IOException("Batch stat returned an incorrect result count");
      }
      for (FileStat stat : response.getStatsList()) {
        if (stat.getError() == 2 || stat.getError() == 20) { // ENOENT or ENOTDIR
          results.add(null);
        } else if (stat.getError() != 0
            || (stat.getMode() & 0170000) == 0
            || Integer.compareUnsigned(stat.getMtimeNanos(), 1_000_000_000) >= 0
            || Integer.compareUnsigned(stat.getCtimeNanos(), 1_000_000_000) >= 0) {
          // FilesystemValueChecker retries the shard with ordinary filesystem operations.
          throw new IOException("Batch stat unavailable or invalid: errno " + stat.getError());
        } else {
          results.add(new NativeStat(stat));
        }
      }
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNIMPLEMENTED) {
        statSupported = false;
      }
      throw new IOException("Batch stat RPC failed", e);
    }
  }

  @Override
  public void createSymlinks(Path root, Map<PathFragment, PathFragment> symlinks)
      throws IOException {
    // Decide for the whole tree before submitting any potentially mutating RPC.
    if (symlinks.size() < symlinkMinEntries) {
      for (var entry : symlinks.entrySet()) {
        FileSystemUtils.ensureSymbolicLink(root.getRelative(entry.getKey()), entry.getValue());
      }
      return;
    }
    List<PendingSymlinks> pending = new ArrayList<>(MAX_SYMLINK_REQUESTS);
    var completed = new ArrayBlockingQueue<PendingSymlinks>(MAX_SYMLINK_REQUESTS);
    var request = BatchSymlinkRequest.newBuilder().setDirectory(pathBytes(root.asFragment()));
    int bytes = 0;
    for (var entry : symlinks.entrySet()) {
      var link =
          Symlink.newBuilder()
              .setPath(pathBytes(entry.getKey()))
              .setTarget(pathBytes(entry.getValue()))
              .build();
      int size = link.getSerializedSize();
      if (request.getSymlinksCount() > 0
          && (request.getSymlinksCount() == MAX_ENTRIES || bytes + size > MAX_PATH_BYTES)) {
        enqueueSymlinks(root, request.build(), pending, completed);
        request.clearSymlinks();
        bytes = 0;
      }
      request.addSymlinks(link);
      bytes += size;
    }
    if (request.getSymlinksCount() > 0) {
      enqueueSymlinks(root, request.build(), pending, completed);
    }
    finishSymlinks(root, pending);
  }

  private record PendingSymlinks(
      BatchSymlinkRequest request, ListenableFuture<BatchSymlinkResponse> response) {}

  private void enqueueSymlinks(
      Path root,
      BatchSymlinkRequest request,
      List<PendingSymlinks> pending,
      ArrayBlockingQueue<PendingSymlinks> completed)
      throws IOException {
    if (!symlinkSupported) {
      finishSymlinks(root, pending);
      symlinkLocally(root, request);
      return;
    }
    long start = Profiler.nanoTimeMaybe();
    // No retries or deadline: transport failure may leave a partially completed batch.
    var response = symlinkStub.batchSymlink(request);
    var batch = new PendingSymlinks(request, response);
    pending.add(batch);
    response.addListener(
        () -> {
          try {
            Profiler.instance()
                .logSimpleTask(start, ProfilerTask.INFO, "Batch output service symlink");
          } finally {
            completed.add(batch);
          }
        },
        directExecutor());
    if (pending.size() == MAX_SYMLINK_REQUESTS) {
      // Refill whichever slot finishes first, without waiting for the rest of a wave.
      try {
        var finished = completed.take();
        try {
          checkSymlinkResponse(finished);
        } catch (ExecutionException | IOException e) {
          // Retain every pending batch until all writes finish and all replies are checked.
          finishSymlinks(root, pending);
          return;
        }
        pending.remove(finished);
      } catch (InterruptedException e) {
        cancelSymlinks(pending);
        throw new IOException("Batch symlink interrupted; completion is unknown", e);
      }
    }
  }

  private void finishSymlinks(Path root, List<PendingSymlinks> pending) throws IOException {
    // Drain every submitted request before reporting a failure or falling back locally.
    try {
      for (var batch : pending) {
        try {
          batch.response().get();
        } catch (ExecutionException ignored) {
          // Inspect all results only after the other requests finish.
        }
      }
    } catch (InterruptedException e) {
      cancelSymlinks(pending);
      throw new IOException("Batch symlink interrupted; completion is unknown", e);
    }
    List<BatchSymlinkRequest> unsupported = new ArrayList<>();
    for (var batch : pending) {
      try {
        checkSymlinkResponse(batch);
      } catch (ExecutionException e) {
        if (Status.fromThrowable(e.getCause()).getCode() != Status.Code.UNIMPLEMENTED) {
          throw new IOException("Batch symlink RPC failed; completion is unknown", e);
        }
        // The protocol guarantees UNIMPLEMENTED is returned before any mutation.
        symlinkSupported = false;
        unsupported.add(batch.request());
      }
    }
    pending.clear();
    for (var request : unsupported) {
      symlinkLocally(root, request);
    }
  }

  private static void cancelSymlinks(List<PendingSymlinks> pending) {
    for (var batch : pending) {
      batch.response().cancel(true);
    }
    Thread.currentThread().interrupt();
  }

  private static void checkSymlinkResponse(PendingSymlinks batch)
      throws ExecutionException, IOException {
    var response = Futures.getDone(batch.response());
    var request = batch.request();
    if (response.getErrorsCount() != request.getSymlinksCount()) {
      throw new IOException("Batch symlink returned an incorrect result count");
    }
    for (int i = 0; i < response.getErrorsCount(); i++) {
      int error = response.getErrors(i);
      if (error != 0) {
        throw new IOException(
            "Batch symlink failed: errno "
                + error
                + " for "
                + request.getSymlinks(i).getPath().toString(ISO_8859_1));
      }
    }
  }

  private static void symlinkLocally(Path root, BatchSymlinkRequest request) throws IOException {
    for (Symlink link : request.getSymlinksList()) {
      FileSystemUtils.ensureSymbolicLink(
          root.getRelative(link.getPath().toString(ISO_8859_1)),
          PathFragment.create(link.getTarget().toString(ISO_8859_1)));
    }
  }

  private record NativeStat(FileStat stat) implements FileStatusWithDigest {
    private int type() {
      return stat.getMode() & 0170000;
    }

    @Override
    public boolean isFile() {
      return type() == 0100000 || isSpecialFile();
    }

    @Override
    public boolean isDirectory() {
      return type() == 0040000;
    }

    @Override
    public boolean isSymbolicLink() {
      return type() == 0120000;
    }

    @Override
    public boolean isSpecialFile() {
      return type() == 0140000 || type() == 0060000 || type() == 0020000 || type() == 0010000;
    }

    @Override
    public long getSize() {
      return stat.getSize();
    }

    @Override
    public long getLastModifiedTime() {
      return stat.getMtimeSeconds() * 1000 + stat.getMtimeNanos() / 1_000_000;
    }

    @Override
    public long getLastChangeTime() {
      return stat.getCtimeSeconds() * 1000 + stat.getCtimeNanos() / 1_000_000;
    }

    @Override
    public long getNodeId() {
      return stat.getInode();
    }

    @Override
    public int getPermissions() {
      return stat.getMode() & 07777;
    }

    @Override
    public byte[] getDigest() {
      return null;
    }
  }
}
