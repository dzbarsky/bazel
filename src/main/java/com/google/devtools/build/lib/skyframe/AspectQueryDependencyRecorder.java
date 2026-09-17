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

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.toolchains.ToolchainContextKey;
import com.google.devtools.build.lib.skyframe.toolchains.UnloadedToolchainContext;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Distinguishes an aspect's own dependencies from prerequisites borrowed from its base target. */
final class AspectQueryDependencyRecorder implements BaseTargetPrerequisitesSupplier {
  private enum Role {
    BORROWED,
    COLD_BORROWED,
    OWNED
  }

  private final BaseTargetPrerequisitesSupplier delegate;
  private final Map<SkyKey, Role> roles = new HashMap<>();

  AspectQueryDependencyRecorder(BaseTargetPrerequisitesSupplier delegate) {
    this.delegate = delegate;
  }

  void recordOwned(SkyKey key) {
    roles.put(key, Role.OWNED);
  }

  private void recordBorrowed(SkyKey key, boolean cold) {
    roles.merge(
        key,
        cold ? Role.COLD_BORROWED : Role.BORROWED,
        (previous, current) -> previous.ordinal() > current.ordinal() ? previous : current);
  }

  @Override
  @Nullable
  public ConfiguredTargetValue getPrerequisite(ConfiguredTargetKey key)
      throws InterruptedException {
    ConfiguredTargetValue value = delegate.getPrerequisite(key);
    recordBorrowed(key, value == null);
    return value;
  }

  @Override
  @Nullable
  public BuildConfigurationValue getPrerequisiteConfiguration(BuildConfigurationKey key)
      throws InterruptedException {
    return delegate.getPrerequisiteConfiguration(key);
  }

  @Override
  @Nullable
  public UnloadedToolchainContext getUnloadedToolchainContext(ToolchainContextKey key)
      throws InterruptedException {
    UnloadedToolchainContext value = delegate.getUnloadedToolchainContext(key);
    recordBorrowed(key, value == null);
    return value;
  }

  ImmutableSet<SkyKey> excludedEdges(Environment env) {
    var result = ImmutableSet.<SkyKey>builder();
    var previousDeps = env.getTemporaryDirectDeps();
    // Current fallback lookups are not in previousDeps. Conversely, after compute-state eviction
    // a previously cold lookup may now be warm. Recording both roles and prior edges handles both
    // cases without retaining exclusions for warm local aspects that have no borrowed edges.
    roles.forEach(
        (key, role) -> {
          if (role == Role.COLD_BORROWED || (previousDeps == null && role == Role.BORROWED)) {
            result.add(key);
          }
        });
    if (previousDeps != null) {
      for (SkyKey key : previousDeps.getAllElementsAsIterable()) {
        if (roles.get(key) == Role.BORROWED) {
          result.add(key);
        }
      }
    }
    return result.build();
  }
}
