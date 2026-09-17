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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
  public static final SkyFunctionName FUNCTION_NAME =
      SkyFunctionName.createHermetic("GENCQUERY_SCOPE");

  @AutoCodec
  public static final class Key
      extends AbstractSkyKey.WithCachedHashCode<ImmutableList<ConfiguredTargetKey>> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(ImmutableList<ConfiguredTargetKey> arg) {
      super(arg);
    }

    @VisibleForSerialization
    @AutoCodec.Instantiator
    public static Key create(ImmutableList<ConfiguredTargetKey> arg) {
      return interner.intern(
          new Key(
              ImmutableList.sortedCopyOf(
                  Comparator.comparing(ConfiguredTargetKey::toString), arg)));
    }

    @Override
    public SkyFunctionName functionName() {
      return FUNCTION_NAME;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  private final ImmutableMap<SkyKey, SkyValue> values;
  private final ImmutableMap<SkyKey, Iterable<SkyKey>> directDeps;
  private final ImmutableMap<SkyKey, Iterable<SkyKey>> reverseDeps;

  private GenCqueryScope(Map<SkyKey, SkyValue> values, Map<SkyKey, Iterable<SkyKey>> directDeps) {
    this.values = ImmutableMap.copyOf(values);
    this.directDeps = ImmutableMap.copyOf(directDeps);
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

  public ImmutableMap<SkyKey, SkyValue> getValues() {
    return values;
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

  /** Builds the snapshot only after its roots have finished analysis. */
  public static final class Function implements SkyFunction {
    private final Supplier<WalkableGraph> graphSupplier;
    private final BooleanSupplier tracksIncrementalState;

    public Function(Supplier<WalkableGraph> graphSupplier, BooleanSupplier tracksIncrementalState) {
      this.graphSupplier = graphSupplier;
      this.tracksIncrementalState = tracksIncrementalState;
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
      Map<SkyKey, SkyValue> values = new LinkedHashMap<>();
      Map<SkyKey, Iterable<SkyKey>> directDeps = new LinkedHashMap<>();
      Set<SkyKey> metadataKeys = new LinkedHashSet<>();
      Set<SkyKey> pending = new LinkedHashSet<>(((Key) skyKey).argument());
      while (!pending.isEmpty()) {
        SkyframeLookupResult lookup = env.getValuesAndExceptions(pending);
        if (env.valuesMissing()) {
          return null;
        }
        Set<SkyKey> next = new LinkedHashSet<>();
        for (SkyKey key : pending) {
          SkyValue value = lookup.get(key);
          if (value == null) {
            return null;
          }
          values.put(key, value);
          if (value instanceof ConfiguredTargetValue configuredTargetValue) {
            var target = configuredTargetValue.getConfiguredTarget();
            metadataKeys.add(target.getOriginalLabel().getPackageIdentifier());
            if (target.getConfigurationKey() != null) {
              metadataKeys.add(target.getConfigurationKey());
            }
          }
          // Never walk reverse edges in the live graph: they may include unrelated builds or the
          // gencquery itself. Reading edges is safe only after requesting the parent through env.
          // Track every visited value so changes to both nodes and edges invalidate this snapshot.
          // Only query results need sorting. Stringifying aspect keys here can exponentially
          // expand their shared base-aspect graphs.
          ImmutableList<SkyKey> deps =
              ImmutableSet.copyOf(graph.getDirectDeps(key)).stream()
                  .filter(
                      dep ->
                          dep.functionName().equals(SkyFunctions.CONFIGURED_TARGET)
                              || dep.functionName().equals(SkyFunctions.ASPECT)
                              || dep.functionName().equals(SkyFunctions.TOOLCHAIN_RESOLUTION))
                  .collect(ImmutableList.toImmutableList());
          directDeps.put(key, deps);
          next.addAll(deps);
        }
        next.removeAll(values.keySet());
        pending = next;
      }
      SkyframeLookupResult metadata = env.getValuesAndExceptions(metadataKeys);
      if (env.valuesMissing()) {
        return null;
      }
      for (SkyKey key : metadataKeys) {
        SkyValue value = metadata.get(key);
        if (value == null) {
          return null;
        }
        values.put(key, value);
      }
      return new GenCqueryScope(values, directDeps);
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
