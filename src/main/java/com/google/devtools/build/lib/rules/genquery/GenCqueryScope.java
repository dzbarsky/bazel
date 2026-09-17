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
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.analysis.ConfiguredObjectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** A dependency-tracked, closed snapshot of a gencquery's configured target graph. */
public final class GenCqueryScope implements SkyValue, WalkableGraph {
  private final ImmutableList<ConfiguredTargetKey> rootKeys;
  private final ImmutableMap<SkyKey, SkyValue> values;
  private final ImmutableMap<SkyKey, Iterable<SkyKey>> directDeps;
  private final ImmutableMap<SkyKey, Iterable<SkyKey>> reverseDeps;
  private final ImmutableListMultimap<Label, SkyKey> targetKeysByLabel;
  private final ImmutableMap<String, BuildConfigurationValue> configurations;

  private GenCqueryScope(
      ImmutableList<ConfiguredTargetKey> rootKeys,
      Map<SkyKey, SkyValue> values,
      Map<SkyKey, Iterable<SkyKey>> directDeps) {
    this.rootKeys = rootKeys;
    this.values = ImmutableMap.copyOf(values);
    this.directDeps = ImmutableMap.copyOf(directDeps);
    // Reports with the same roots share both the graph and its indexes. Delegating Skyframe keys
    // can point to the same configured target, so index each full target identity only once.
    Set<SkyKey> indexedTargets = new HashSet<>();
    ImmutableListMultimap.Builder<Label, SkyKey> targets = ImmutableListMultimap.builder();
    ImmutableMap.Builder<String, BuildConfigurationValue> configurations = ImmutableMap.builder();
    for (var entry : values.entrySet()) {
      SkyValue value = entry.getValue();
      if (value instanceof ConfiguredTargetValue configured) {
        CqueryNode target = configured.getConfiguredTarget();
        if (indexedTargets.add(target.getLookupKey())) {
          targets.put(target.getOriginalLabel(), entry.getKey());
        }
      } else if (value instanceof BuildConfigurationValue configuration) {
        configurations.put(configuration.checksum(), configuration);
      }
    }
    this.targetKeysByLabel = targets.build();
    this.configurations = configurations.buildOrThrow();
    Map<SkyKey, List<SkyKey>> reverseDeps = new LinkedHashMap<>();
    directDeps.forEach(
        (key, deps) -> {
          reverseDeps.computeIfAbsent(key, unused -> new ArrayList<>());
          for (SkyKey dep : deps) {
            reverseDeps.computeIfAbsent(dep, unused -> new ArrayList<>()).add(key);
          }
        });
    ImmutableMap.Builder<SkyKey, Iterable<SkyKey>> builder = ImmutableMap.builder();
    reverseDeps.forEach((key, deps) -> builder.put(key, ImmutableList.copyOf(deps)));
    this.reverseDeps = builder.buildOrThrow();
  }

  List<CqueryNode> getTargets(Label label) {
    // Index keys, not configured target objects, so clearing analysis values still releases their
    // providers and actions. Only materialize targets while evaluating a query.
    return Lists.transform(
        targetKeysByLabel.get(label),
        key -> ((ConfiguredTargetValue) values.get(key)).getConfiguredTarget());
  }

  ImmutableMap<String, BuildConfigurationValue> getConfigurations() {
    return configurations;
  }

  public ImmutableList<ConfiguredTargetKey> getRootKeys() {
    return rootKeys;
  }

  @Override
  @Nullable
  public SkyValue getValue(SkyKey key) {
    return values.get(key);
  }

  @Override
  public Map<SkyKey, SkyValue> getSuccessfulValues(Iterable<? extends SkyKey> keys) {
    ImmutableMap.Builder<SkyKey, SkyValue> result = ImmutableMap.builder();
    for (SkyKey key : keys) {
      SkyValue value = values.get(key);
      if (value != null) {
        result.put(key, value);
      }
    }
    return result.buildKeepingLast();
  }

  @Override
  public Map<SkyKey, Exception> getMissingAndExceptions(Iterable<SkyKey> keys) {
    Map<SkyKey, Exception> result = new HashMap<>();
    for (SkyKey key : keys) {
      if (!values.containsKey(key)) {
        result.put(key, null);
      }
    }
    return result;
  }

  @Override
  @Nullable
  public Exception getException(SkyKey key) {
    return null;
  }

  @Override
  public boolean isCycle(SkyKey key) {
    return false;
  }

  @Override
  public Map<SkyKey, Iterable<SkyKey>> getDirectDeps(Iterable<SkyKey> keys) {
    ImmutableMap.Builder<SkyKey, Iterable<SkyKey>> result = ImmutableMap.builder();
    for (SkyKey key : keys) {
      result.put(key, getDirectDeps(key));
    }
    return result.buildKeepingLast();
  }

  @Override
  public Iterable<SkyKey> getDirectDeps(SkyKey key) {
    return directDeps.getOrDefault(key, ImmutableList.of());
  }

  @Override
  public Map<SkyKey, Iterable<SkyKey>> getReverseDeps(Iterable<? extends SkyKey> keys) {
    ImmutableMap.Builder<SkyKey, Iterable<SkyKey>> result = ImmutableMap.builder();
    for (SkyKey key : keys) {
      result.put(key, reverseDeps.getOrDefault(key, ImmutableList.of()));
    }
    return result.buildKeepingLast();
  }

  @Override
  public Map<SkyKey, Pair<SkyValue, Iterable<SkyKey>>> getValueAndRdeps(Iterable<SkyKey> keys) {
    ImmutableMap.Builder<SkyKey, Pair<SkyValue, Iterable<SkyKey>>> result = ImmutableMap.builder();
    for (SkyKey key : keys) {
      SkyValue value = values.get(key);
      if (value != null) {
        result.put(key, Pair.of(value, reverseDeps.getOrDefault(key, ImmutableList.of())));
      }
    }
    return result.buildKeepingLast();
  }

  /** Analyzes the roots and collects their dependency-tracked query graph. */
  public static final class Function implements SkyFunction {
    private final Supplier<WalkableGraph> graphSupplier;
    private final BooleanSupplier tracksIncrementalState;

    public Function(Supplier<WalkableGraph> graphSupplier, BooleanSupplier tracksIncrementalState) {
      this.graphSupplier = graphSupplier;
      this.tracksIncrementalState = tracksIncrementalState;
    }

    private static final class State implements SkyKeyComputeState {
      final Map<SkyKey, SkyValue> values = new LinkedHashMap<>();
      final Map<SkyKey, Iterable<SkyKey>> directDeps = new LinkedHashMap<>();
      final Set<SkyKey> metadataKeys = new LinkedHashSet<>();
      final Set<SkyKey> pending;

      State(GenCqueryScopeKey key) {
        pending = new LinkedHashSet<>(key.argument());
      }
    }

    @Override
    @Nullable
    public SkyValue compute(SkyKey skyKey, Environment env)
        throws InterruptedException, ScopeFunctionException {
      if (!tracksIncrementalState.getAsBoolean()) {
        throw new ScopeFunctionException(
            new ScopeException("gencquery requires --track_incremental_state"));
      }
      WalkableGraph graph = graphSupplier.get();
      // Cache hits can require additional rounds of dependency downloads. Retain completed work
      // across restarts so each node and its outgoing edges are processed only once.
      State state = env.getState(() -> new State((GenCqueryScopeKey) skyKey));
      while (!state.pending.isEmpty()) {
        SkyframeLookupResult lookup = env.getValuesAndExceptions(state.pending);
        Set<SkyKey> next = new LinkedHashSet<>();
        var iterator = state.pending.iterator();
        while (iterator.hasNext()) {
          SkyKey key = iterator.next();
          SkyValue value = lookup.get(key);
          if (value == null) {
            continue;
          }
          iterator.remove();
          state.values.put(key, value);
          if (value instanceof ConfiguredTargetValue configuredTargetValue) {
            var target = configuredTargetValue.getConfiguredTarget();
            state.metadataKeys.add(target.getOriginalLabel().getPackageIdentifier());
            if (target.getConfigurationKey() != null) {
              state.metadataKeys.add(target.getConfigurationKey());
            }
          }
          // Never walk reverse edges in the live graph: they may include unrelated builds or the
          // gencquery itself. Reading edges is safe only after requesting the parent through env.
          // Track every visited value so changes to both nodes and edges invalidate this snapshot.
          // Only query results need sorting. Stringifying aspect keys here can exponentially
          // expand their shared base-aspect graphs.
          ImmutableList<SkyKey> deps =
              value instanceof ConfiguredObjectValue configuredValue
                  ? configuredValue.getQueryDependencies(key)
                  : null;
          if (deps == null) {
            deps = GenCqueryScopeKey.queryDependencies(graph.getDirectDeps(key));
          }
          state.directDeps.put(key, deps);
          next.addAll(deps);
        }
        next.removeAll(state.values.keySet());
        state.pending.addAll(next);
        if (env.valuesMissing()) {
          return null;
        }
      }
      SkyframeLookupResult metadata = env.getValuesAndExceptions(state.metadataKeys);
      var iterator = state.metadataKeys.iterator();
      while (iterator.hasNext()) {
        SkyKey key = iterator.next();
        SkyValue value = metadata.get(key);
        if (value == null) {
          continue;
        }
        iterator.remove();
        state.values.put(key, value);
      }
      if (env.valuesMissing()) {
        return null;
      }
      return new GenCqueryScope(
          ((GenCqueryScopeKey) skyKey).argument(), state.values, state.directDeps);
    }
  }

  /** A scope that cannot be queried in this build. */
  public static final class ScopeException extends Exception {
    private ScopeException(String message) {
      super(message);
    }
  }

  private static final class ScopeFunctionException extends SkyFunctionException {
    private ScopeFunctionException(ScopeException cause) {
      super(cause, Transience.PERSISTENT);
    }
  }
}
