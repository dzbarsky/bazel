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

package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.actions.ActionKeyContext.MapEachCache;
import com.google.devtools.build.lib.actions.Artifact.SourceArtifact;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ActionKeyContextTest {
  private ArtifactRoot root;
  private StarlarkFunction function;

  @Before
  public void createInputs() throws Exception {
    root = ArtifactRoot.asSourceRoot(Root.fromPath(new Scratch().dir("/workspace")));
    try (Mutability mutability = Mutability.create("test")) {
      function =
          (StarlarkFunction)
              Starlark.execFile(
                  ParserInput.fromString("def mapper(f):\n  return f.path\nmapper", "test.bzl"),
                  FileOptions.DEFAULT,
                  Module.create(),
                  StarlarkThread.createTransient(mutability, StarlarkSemantics.DEFAULT));
    }
  }

  private MapEachCache.Key key(int i) {
    return new MapEachCache.Key(
        new SourceArtifact(root, PathFragment.create("source" + i), ArtifactOwner.NULL_OWNER),
        function,
        StarlarkSemantics.DEFAULT,
        PathMapper.NOOP.starlarkFileCacheKey());
  }

  @Test
  public void mapEachCache_limitsAccountedWeightAndRejectsOversizedResults() throws Exception {
    MapEachCache cache = new MapEachCache(4096);
    for (int i = 0; i < 32; i++) {
      cache.put(key(i), "x".repeat(128));
    }
    // Force maintenance before checking the eventual bound, not an instantaneous heap claim.
    assertThat(cache.weightedSize()).isAtMost(4096);
    assertThat(cache.estimatedSize()).isAtMost(4096 / MapEachCache.ENTRY_WEIGHT);
    MapEachCache.Key oversized = key(32);
    cache.put(oversized, "x".repeat(MapEachCache.MAX_VALUE_CHARS + 1));
    assertThat(cache.get(oversized)).isNull();
  }

  @Test
  public void clear_dropsCacheAndDoesNotReuseAcrossCommands() throws Exception {
    ActionKeyContext context = new ActionKeyContext();
    assertThat(context.getMapEachCache()).isNull();
    context.setMapEachCachingEnabled(true);
    MapEachCache oldCache = context.getMapEachCache();
    MapEachCache.Key key = key(0);
    oldCache.put(key, "first command");
    assertThat(oldCache.get(key)).isEqualTo("first command");
    context.clear();
    assertThat(context.getMapEachCache()).isNull();
    context.setMapEachCachingEnabled(true);
    assertThat(context.getMapEachCache()).isNotSameInstanceAs(oldCache);
    assertThat(context.getMapEachCache().get(key)).isNull();
    context.clear();
  }
}
