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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.BatchStatRequest;
import com.google.devtools.build.lib.remote.BatchOutputServiceProto.BatchSymlinkRequest;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A partial output-service client. The ordinary output service retains all other responsibilities.
 */
final class BatchOutputServiceClient implements BatchOperations {
  private static final int MAX_ENTRIES = 4096;
  private static final int MAX_PATH_BYTES = 1024 * 1024;
  private final BatchOutputServiceGrpc.BatchOutputServiceBlockingStub stub;
  private final Supplier<Path> execRoot;
  private volatile boolean statSupported = true;
  private volatile boolean symlinkSupported = true;

  BatchOutputServiceClient(Channel channel, Supplier<Path> execRoot) {
    this.stub = BatchOutputServiceGrpc.newBlockingStub(channel);
    this.execRoot = execRoot;
  }

  private static ByteString pathBytes(PathFragment path) {
    return ByteString.copyFrom(path.getSafePathString(), ISO_8859_1);
  }

  @Override
  public int getMaxConcurrentCalls() {
    // Amortize RPC overhead with larger batches instead of creating four shards per CPU.
    return 16;
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
        symlink(root, request.build());
        request.clearSymlinks();
        bytes = 0;
      }
      request.addSymlinks(link);
      bytes += size;
    }
    if (request.getSymlinksCount() > 0) {
      symlink(root, request.build());
    }
  }

  @SuppressWarnings("try")
  private void symlink(Path root, BatchSymlinkRequest request) throws IOException {
    if (symlinkSupported) {
      try (var ignored = Profiler.instance().profile("Batch output service symlink")) {
        // No retries or deadline: transport failure may leave a partially completed batch.
        var response = stub.batchSymlink(request);
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
        return;
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() != Status.Code.UNIMPLEMENTED) {
          throw new IOException("Batch symlink RPC failed; completion is unknown", e);
        }
        // The protocol guarantees UNIMPLEMENTED is returned before any mutation.
        symlinkSupported = false;
      }
    }
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
