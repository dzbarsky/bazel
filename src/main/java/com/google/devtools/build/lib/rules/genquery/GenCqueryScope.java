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
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** A closed query graph owned by one evaluation attempt. */
final class GenCqueryScope implements WalkableGraph {
  private final ImmutableList<ConfiguredTargetKey> rootKeys;
  private final Map<SkyKey, SkyValue> values;
  private final Map<SkyKey, Iterable<SkyKey>> directDeps;
  @Nullable private ImmutableMap<SkyKey, Iterable<SkyKey>> reverseDeps;
  private final ImmutableListMultimap<Label, SkyKey> targetKeysByLabel;
  private final ImmutableMap<String, BuildConfigurationValue> configurations;

  GenCqueryScope(
      ImmutableList<ConfiguredTargetKey> rootKeys,
      Map<SkyKey, SkyValue> values,
      Map<SkyKey, Iterable<SkyKey>> directDeps) {
    this.rootKeys = rootKeys;
    this.values = values;
    this.directDeps = directDeps;
    // The function has finished populating these maps before constructing the query view.
    // Delegating keys can point to the same target; index each full identity only once.
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
  }

  private synchronized ImmutableMap<SkyKey, Iterable<SkyKey>> reverseDeps() {
    if (reverseDeps != null) {
      return reverseDeps;
    }
    Map<SkyKey, List<SkyKey>> reversed = new LinkedHashMap<>();
    directDeps.forEach(
        (key, deps) -> {
          reversed.computeIfAbsent(key, unused -> new ArrayList<>());
          for (SkyKey dep : deps) {
            reversed.computeIfAbsent(dep, unused -> new ArrayList<>()).add(key);
          }
        });
    ImmutableMap.Builder<SkyKey, Iterable<SkyKey>> builder = ImmutableMap.builder();
    reversed.forEach((key, deps) -> builder.put(key, ImmutableList.copyOf(deps)));
    return reverseDeps = builder.buildOrThrow();
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
      result.put(key, reverseDeps().getOrDefault(key, ImmutableList.of()));
    }
    return result.buildKeepingLast();
  }

  @Override
  public Map<SkyKey, Pair<SkyValue, Iterable<SkyKey>>> getValueAndRdeps(Iterable<SkyKey> keys) {
    ImmutableMap.Builder<SkyKey, Pair<SkyValue, Iterable<SkyKey>>> result = ImmutableMap.builder();
    for (SkyKey key : keys) {
      SkyValue value = values.get(key);
      if (value != null) {
        result.put(key, Pair.of(value, reverseDeps().getOrDefault(key, ImmutableList.of())));
      }
    }
    return result.buildKeepingLast();
  }
}
