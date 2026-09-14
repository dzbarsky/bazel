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
package com.google.devtools.build.lib.exec;

import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;

/** Uses the ordinary spawn cache without dispatching to an execution strategy. */
final class CacheProbeSpawnStrategy extends AbstractSpawnStrategy {
  CacheProbeSpawnStrategy(ExecutionOptions executionOptions) {
    super(new NonExecutingRunner(), executionOptions);
  }

  private static final class NonExecutingRunner implements SpawnRunner {
    @Override
    public SpawnResult exec(Spawn spawn, SpawnExecutionContext context) {
      throw new IllegalStateException("Cache probes must not execute spawns");
    }

    @Override
    public boolean canExec(Spawn spawn) {
      return true;
    }

    @Override
    public boolean handlesCaching() {
      return false;
    }

    @Override
    public String getName() {
      return "cache-probe";
    }
  }
}
