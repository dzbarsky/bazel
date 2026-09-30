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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.supplier.InterruptibleSupplier;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import javax.annotation.Nullable;

/** An evaluation-scoped lookup, needed only when Starlark observes the main repo mapping. */
public final class MainRepositoryMappingSupplier
    implements InterruptibleSupplier<RepositoryMapping>, AutoCloseable {
  @Nullable private Environment env;
  @Nullable private RepositoryMapping mapping;
  @Nullable private InterruptedException interruption;
  private boolean missing;

  public MainRepositoryMappingSupplier(Environment env) {
    this.env = env;
  }

  @Override
  public RepositoryMapping get() throws InterruptedException {
    if (mapping == null) {
      RepositoryMappingValue value;
      try {
        value =
            (RepositoryMappingValue)
                checkNotNull(env).getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
      } catch (InterruptedException e) {
        // Label.debugPrint catches InterruptedException. Keep cancellation prompt and ensure the
        // evaluation boundary still propagates it instead of committing incomplete diagnostics.
        interruption = e;
        Thread.currentThread().interrupt();
        throw e;
      }
      missing = value == null;
      // The caller discards the evaluation on a miss. A non-null placeholder also lets private
      // initializer checks fail normally, without an NPE that would bypass the restart.
      mapping = missing ? RepositoryMapping.EMPTY : value.repositoryMapping();
    }
    return mapping;
  }

  /** Must be checked before replaying events or saving the evaluation's result. */
  public boolean isMissing() throws InterruptedException {
    if (interruption != null) {
      throw interruption;
    }
    return missing;
  }

  @Override
  public void close() {
    // Builders and rule-definition contexts may outlive this SkyFunction invocation.
    env = null;
  }
}
