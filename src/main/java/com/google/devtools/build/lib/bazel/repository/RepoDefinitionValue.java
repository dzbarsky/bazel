// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.NotComparableSkyValue;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import net.starlark.java.eval.Dict;

/**
 * The result of {@link RepoDefinitionFunction}, holding a repository rule instance.
 *
 * <p>Structural equality of {@link RepoRule} is insufficient: its Starlark callable closes over the
 * defining module's repo mapping, but callable equality does not compare that mapping. {@link
 * Found} therefore requires the same memoized rule object, which is only reused while the evaluated
 * definition and its closed-over context are unchanged.
 */
public sealed interface RepoDefinitionValue extends SkyValue {
  SkyFunctionName REPO_DEFINITION = SkyFunctionName.createHermetic("REPO_DEFINITION");

  RepoDefinitionValue NOT_FOUND = new NotFound();

  /** No repo found with the given name. */
  @AutoCodec
  record NotFound() implements RepoDefinitionValue, NotComparableSkyValue {}

  /** Symlink to target directory. */
  @AutoCodec
  record RepoOverride(PathFragment repoPath)
      implements RepoDefinitionValue, NotComparableSkyValue {}

  /** A repo with the given name is found. */
  @AutoCodec
  record Found(RepoDefinition repoDefinition) implements RepoDefinitionValue {
    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Found other)
          || repoDefinition.repoRule() != other.repoDefinition.repoRule()
          || !repoDefinition.equals(other.repoDefinition)) {
        return false;
      }
      var attributes = repoDefinition.attrValues().attributes();
      var otherAttributes = other.repoDefinition.attrValues().attributes();
      // Repository dict attributes contain scalars or scalar lists, not nested dictionaries.
      // Their entry order is observable even though Dict.equals ignores it.
      for (var entry : attributes.entrySet()) {
        if (entry.getValue() instanceof Dict<?, ?> dict
            && !Iterables.elementsEqual(
                dict.entrySet(), ((Dict<?, ?>) otherAttributes.get(entry.getKey())).entrySet())) {
          return false;
        }
      }
      return true;
    }
  }

  static Key key(RepositoryName repositoryName) {
    return Key.create(repositoryName);
  }

  /** Key type for {@link RepoDefinitionValue}. */
  @AutoCodec
  class Key extends AbstractSkyKey<RepositoryName> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(RepositoryName arg) {
      super(arg);
    }

    @VisibleForSerialization
    @AutoCodec.Instantiator
    static Key create(RepositoryName arg) {
      return interner.intern(new Key(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return REPO_DEFINITION;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }
}
