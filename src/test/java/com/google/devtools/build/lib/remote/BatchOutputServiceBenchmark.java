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

import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.unix.UnixFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.common.options.Options;
import io.grpc.ManagedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/** Run with {@code -p root=/path/on/the/filesystem} to compare mounted filesystems. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class BatchOutputServiceBenchmark {
  @State(Scope.Thread)
  public static class StatState {
    @Param({"/tmp"})
    public String root;

    @Param({"10000"})
    public int entries;

    @Param({"1", "100"})
    public int siblings;

    @Param({"false", "true"})
    public boolean missing;

    @Param({"shuffled"})
    public String order;

    @Param({"unix:/tmp/timber-output-service.sock"})
    public String endpoint;

    UnixFileSystem fs;
    ManagedChannel channel;
    BatchOutputServiceClient client;
    Path directory;
    List<Path> paths;
    List<PathFragment> fragments;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      fs = new UnixFileSystem(DigestHashFunction.SHA256, "");
      directory = fs.getPath(root).getChild("bazel-stat-benchmark-" + UUID.randomUUID());
      directory.createDirectory();
      channel =
          GoogleAuthUtils.newChannel(
              null, endpoint, null, Options.getDefaults(AuthAndTLSOptions.class), null, Map.of());
      client = new BatchOutputServiceClient(channel, () -> directory, 0);
      paths = new ArrayList<>();
      fragments = new ArrayList<>();
      for (int i = 0; i < entries; i++) {
        Path path =
            directory.getRelative(
                "execroot/_main/bazel-out/bin/package/" + (i / siblings) + "/file" + i);
        path.getParentDirectory().createDirectoryAndParents();
        if (!missing) {
          FileSystemUtils.createEmptyFile(path);
        }
        paths.add(path);
      }
      if (order.equals("shuffled")) {
        Collections.shuffle(paths, new Random(0));
      } else if (!order.equals("ordered")) {
        throw new IllegalArgumentException("Unknown order: " + order);
      }
      for (Path path : paths) {
        fragments.add(path.relativeTo(directory));
      }
      // Fail setup if the RPC or kernel backend is unavailable; never time a silent fallback.
      client.batchStat(fragments);
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      directory.deleteTree();
    }
  }

  @Benchmark
  public List<FileStatus> individualStat(StatState state) throws Exception {
    List<FileStatus> result = new ArrayList<>(state.entries);
    for (Path path : state.paths) {
      result.add(path.statIfFound(Symlinks.NOFOLLOW));
    }
    return result;
  }

  @Benchmark
  public List<FileStatusWithDigest> rpcBatchStat(StatState state) throws Exception {
    return state.client.batchStat(state.fragments);
  }

  @State(Scope.Thread)
  public static class SymlinkState {
    @Param({"/tmp"})
    public String root;

    @Param({"10000"})
    public int entries;

    @Param({"1", "100"})
    public int siblings;

    @Param({"unix:/tmp/timber-output-service.sock"})
    public String endpoint;

    UnixFileSystem fs;
    ManagedChannel channel;
    BatchOutputServiceClient client;
    Path directory;
    Map<PathFragment, PathFragment> links;

    @Setup(Level.Trial)
    public void setup() throws Exception {
      fs = new UnixFileSystem(DigestHashFunction.SHA256, "");
      directory = fs.getPath(root).getChild("bazel-symlink-benchmark-" + UUID.randomUUID());
      directory.createDirectory();
      channel =
          GoogleAuthUtils.newChannel(
              null, endpoint, null, Options.getDefaults(AuthAndTLSOptions.class), null, Map.of());
      client = new BatchOutputServiceClient(channel, () -> directory, 0);
      links = new LinkedHashMap<>();
      for (int i = 0; i < entries; i++) {
        Path parent =
            directory.getRelative(
                "execroot/_main/bazel-out/bin/package/tool.runfiles/_main/" + (i / siblings));
        parent.createDirectoryAndParents();
        links.put(
            parent.getChild("file" + i).relativeTo(directory), PathFragment.create("../target"));
      }
      client.batchStat(List.of(PathFragment.EMPTY_FRAGMENT));
    }

    @TearDown(Level.Invocation)
    public void removeLinks() throws Exception {
      for (PathFragment path : links.keySet()) {
        directory.getRelative(path).delete();
      }
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
      channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
      directory.deleteTree();
    }
  }

  @Benchmark
  public void individualSymlinks(SymlinkState state) throws Exception {
    for (var link : state.links.entrySet()) {
      FileSystemUtils.ensureSymbolicLink(
          state.directory.getRelative(link.getKey()), link.getValue());
    }
  }

  @Benchmark
  public void rpcBatchSymlinks(SymlinkState state) throws Exception {
    state.client.createSymlinks(state.directory, state.links);
  }
}
