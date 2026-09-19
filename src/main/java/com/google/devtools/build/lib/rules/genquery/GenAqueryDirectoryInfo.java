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

package com.google.devtools.build.lib.rules.genquery;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.ArrayDeque;
import java.util.HashSet;

/** Locations embedded in opaque action-report bytes, which cannot be relocated on cache hits. */
@AutoCodec
public record GenAqueryDirectoryInfo(
    PathFragment workspace,
    PathFragment outputBase,
    PathFragment execRootBase,
    PathFragment installBase)
    implements SkyValue {
  public enum Key implements SkyKey {
    INSTANCE;

    public static final SkyFunctionName FUNCTION_NAME =
        SkyFunctionName.createHermetic("GEN_AQUERY_DIRECTORIES");

    @Override
    public SkyFunctionName functionName() {
      return FUNCTION_NAME;
    }
  }

  /** A cache entry whose analysis or execution transitively depends on report bytes. */
  @AutoCodec
  public record BoundValue(SkyValue value, GenAqueryDirectoryInfo directories)
      implements SkyValue {}

  public static GenAqueryDirectoryInfo create(BlazeDirectories directories) {
    return new GenAqueryDirectoryInfo(
        directories.getWorkspace().asFragment(),
        directories.getOutputBase().asFragment(),
        directories.getExecRootBase().asFragment(),
        directories.getInstallBase().asFragment());
  }

  /** Captures affected entries before upload can discard reverse edges to reduce memory usage. */
  public static ImmutableMap<SkyKey, GenAqueryDirectoryInfo> capture(
      InMemoryGraph graph, ImmutableSet<SkyKey> selection) throws InterruptedException {
    var root = graph.getIfPresent(Key.INSTANCE);
    if (root == null || !root.isDone()) {
      return ImmutableMap.of();
    }
    var directories = (GenAqueryDirectoryInfo) root.getValue();
    var result = ImmutableMap.<SkyKey, GenAqueryDirectoryInfo>builder();
    var visited = new HashSet<SkyKey>();
    var pending = new ArrayDeque<SkyKey>();
    visited.add(Key.INSTANCE);
    pending.add(Key.INSTANCE);
    // Only report dependents are traversed, never the scoped graph or unrelated build targets.
    // Cache hits request the same key, so the binding also survives a subsequent upload.
    while (!pending.isEmpty()) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      SkyKey key = pending.removeLast();
      var entry = graph.getIfPresent(key);
      if (entry == null || !entry.isDone()) {
        continue;
      }
      if (selection.contains(key)) {
        result.put(key, directories);
      }
      for (SkyKey dependent : entry.getReverseDepsForDoneEntry()) {
        if (visited.add(dependent)) {
          pending.add(dependent);
        }
      }
    }
    return result.buildOrThrow();
  }
}
