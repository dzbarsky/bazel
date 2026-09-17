// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.skyframe.NotComparableSkyValue;
import com.google.devtools.build.skyframe.SkyKey;
import javax.annotation.Nullable;

/**
 * Super-interface for {@link ConfiguredTargetValue} and {@link RuleConfiguredObjectValue}
 * (transitively including {@link AspectValue}).
 */
public interface ConfiguredObjectValue extends NotComparableSkyValue {
  /** Returns the configured target/aspect for this value. */
  ProviderCollection getConfiguredObject();

  /**
   * Query graph edges retained for this key with a remotely cached value, or null when its edges
   * are available in Skyframe. A local node can delegate to a cached value under another key; that
   * node must use its own edges. These keys do not request dependency evaluation.
   */
  @Nullable
  default ImmutableList<SkyKey> getQueryDependencies(SkyKey key) {
    return null;
  }

  /**
   * Returns the metadata for the set packages transitively loaded by this value. Must only be used
   * for:
   *
   * <ul>
   *   <li>constructing the package -> source root map needed for some builds, OR
   *   <li>building the repo mapping manifest for runfiles
   * </ul>
   *
   * If the caller has not specified that this map needs to be constructed (via the constructor
   * argument in {@link
   * com.google.devtools.build.lib.skyframe.ConfiguredTargetFunction#ConfiguredTargetFunction} or
   * {@link com.google.devtools.build.lib.skyframe.AspectFunction#AspectFunction}), calling this
   * will crash.
   */
  // TODO(b/283125139): Most builds never need to build a repo mapping manifest. Store transitive
  // packages outside of configured object values to save the wasted field.
  NestedSet<Package.Metadata> getTransitivePackages();
}
