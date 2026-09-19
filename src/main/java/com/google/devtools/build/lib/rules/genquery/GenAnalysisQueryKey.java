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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collections;
import java.util.Comparator;
import javax.annotation.Nullable;

/**
 * Analysis dependencies of a configured query, outside the build's action dependency graph. Query
 * consumers expose the declared roots as direct dependencies, not the snapshot's full transitive
 * set of Skyframe dependencies.
 */
@AutoCodec
public final class GenAnalysisQueryKey
    extends AbstractSkyKey.WithCachedHashCode<GenAnalysisQueryKey.Request> {
  public static final SkyFunctionName FUNCTION_NAME =
      SkyFunctionName.createHermetic("GEN_ANALYSIS_QUERY");
  private static final SkyKeyInterner<GenAnalysisQueryKey> interner = SkyKey.newInterner();
  // The standard ordering omits this bit, but a transition's result is not its incoming root.
  private static final Comparator<ConfiguredTargetKey> ROOT_ORDER =
      ConfiguredTargetKey.ORDERING.thenComparing(ConfiguredTargetKey::shouldApplyRuleTransition);

  /** The two analysis query languages supported by native rules. */
  public enum Kind {
    CQUERY("gencquery"),
    AQUERY("genaquery");

    private final String ruleName;

    Kind(String ruleName) {
      this.ruleName = ruleName;
    }

    public String ruleName() {
      return ruleName;
    }
  }

  /** Immutable inputs resolved and validated by the native rule. */
  @AutoCodec
  public record Request(
      Kind kind,
      Label owner,
      BuildConfigurationKey configuration,
      ImmutableList<ConfiguredTargetKey> roots,
      String expression,
      ImmutableList<PackageIdentifier> targetPatternPackages,
      ImmutableList<String> options,
      @Nullable Artifact formatter,
      boolean strict,
      boolean compressedOutput) {
    public Request {
      roots = ImmutableList.sortedCopyOf(ROOT_ORDER, ImmutableSet.copyOf(roots));
    }
  }

  private GenAnalysisQueryKey(Request request) {
    super(request);
  }

  @VisibleForSerialization
  @AutoCodec.Instantiator
  public static GenAnalysisQueryKey create(Request argument) {
    return interner.intern(new GenAnalysisQueryKey(argument));
  }

  public ImmutableList<ConfiguredTargetKey> roots() {
    return argument().roots();
  }

  /** Tests the original root key, before configuration trimming or rule transitions. */
  public boolean containsRoot(SkyKey key) {
    return key instanceof ConfiguredTargetKey configuredKey
        && Collections.binarySearch(roots(), configuredKey, ROOT_ORDER) >= 0;
  }

  /** Collects logical query edges for both local scope snapshots and cached analysis values. */
  public static ImmutableList<SkyKey> queryDependencies(Iterable<SkyKey> dependencies) {
    ImmutableSet.Builder<SkyKey> result = ImmutableSet.builder();
    for (SkyKey dependency : dependencies) {
      if (dependency instanceof GenAnalysisQueryKey scope) {
        result.addAll(scope.roots());
      } else if (dependency.functionName().equals(SkyFunctions.CONFIGURED_TARGET)
          || dependency.functionName().equals(SkyFunctions.ASPECT)
          || dependency.functionName().equals(SkyFunctions.TOOLCHAIN_RESOLUTION)) {
        result.add(dependency);
      }
    }
    // Retain the adjacency list without the temporary set's lookup table.
    return ImmutableList.copyOf(result.build().iterator());
  }

  @Override
  public SkyFunctionName functionName() {
    return FUNCTION_NAME;
  }

  @Override
  public SkyKeyInterner<GenAnalysisQueryKey> getSkyKeyInterner() {
    return interner;
  }

  @Override
  public boolean skipsBatchPrefetch() {
    // Traversal state retains completed values; fetching them again on every layer is quadratic
    // for a deep cached graph.
    return true;
  }
}
