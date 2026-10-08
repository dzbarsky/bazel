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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;

/** Propagates prerequisite failures and completed test misses during a cache probe. */
public final class CacheProbeCompletion {
  private CacheProbeCompletion() {}

  /** A completed test probe established that a required cached result is missing. */
  public record TestMissEvent(ConfiguredTargetKey configuredTargetKey) implements Postable {
    @Override
    public boolean storeForReplay() {
      return true;
    }
  }

  /** Propagates failed prerequisites without replacing their exceptions. */
  @Nullable
  static SkyValue getValue(Environment env, SkyKey key)
      throws DependencyException, InterruptedException {
    return getValue(env.getValuesAndExceptions(ImmutableList.of(key)), key);
  }

  @Nullable
  static SkyValue getValue(SkyframeLookupResult result, SkyKey key) throws DependencyException {
    class Dependency implements SkyframeLookupResult.QueryDepCallback {
      @Nullable SkyValue value;
      @Nullable Exception failure;

      @Override
      public void acceptValue(SkyKey unused, SkyValue value) {
        this.value = value;
      }

      @Override
      public boolean tryHandleException(SkyKey unused, Exception failure) {
        this.failure = failure;
        return true;
      }
    }
    Dependency dependency = new Dependency();
    result.queryDep(key, dependency);
    if (dependency.failure != null) {
      throw new DependencyException(dependency.failure);
    }
    return dependency.value;
  }

  static final class DependencyException extends SkyFunctionException {
    DependencyException(Exception cause) {
      // The child retains its exact exception and transience; this node only propagates it.
      super(cause, Transience.PERSISTENT);
    }

    @Override
    public boolean isCatastrophic() {
      return getCause() instanceof ActionExecutionException failure && failure.isCatastrophe();
    }
  }
}
