// Copyright 2023 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/** The value for {@link ModuleExtensionRepoMappingEntriesFunction}. */
@AutoCodec
public record ModuleExtensionRepoMappingEntriesValue(
    Entries defaultEntries, ImmutableMap<RepositoryName, Entries> scopedEntries, ModuleKey moduleKey)
    implements SkyValue {
  public ModuleExtensionRepoMappingEntriesValue {
    requireNonNull(defaultEntries, "defaultEntries");
    requireNonNull(scopedEntries, "scopedEntries");
    requireNonNull(moduleKey, "moduleKey");
  }

  /** Shared for repositories with the same visible names. */
  @AutoCodec
  public record Entries(ImmutableMap<String, RepositoryName> mapping, int mappingHash) {
    static Entries of(ImmutableMap<String, RepositoryName> mapping) {
      return new Entries(mapping, mapping.hashCode());
    }
  }

  public Entries forRepo(RepositoryName repoName) {
    return scopedEntries.getOrDefault(repoName, defaultEntries);
  }

  public static ModuleExtensionRepoMappingEntriesValue.Key key(ModuleExtensionId id) {
    return ModuleExtensionRepoMappingEntriesValue.Key.create(id);
  }

  /**
   * The {@link com.google.devtools.build.skyframe.SkyKey} of a {@link
   * ModuleExtensionRepoMappingEntriesValue}.
   */
  @AutoCodec
  public static class Key extends AbstractSkyKey<ModuleExtensionId> {

    private static final SkyKeyInterner<ModuleExtensionRepoMappingEntriesValue.Key> interner =
        SkyKey.newInterner();

    protected Key(ModuleExtensionId arg) {
      super(arg);
    }

    private static ModuleExtensionRepoMappingEntriesValue.Key create(ModuleExtensionId arg) {
      return interner.intern(new ModuleExtensionRepoMappingEntriesValue.Key(arg));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Key intern(Key key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES;
    }

    @Override
    public SkyKeyInterner<ModuleExtensionRepoMappingEntriesValue.Key> getSkyKeyInterner() {
      return interner;
    }
  }
}
