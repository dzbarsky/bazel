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
import com.google.devtools.build.lib.skyframe.ArtifactFunction.SourceArtifactException;
import com.google.devtools.build.lib.skyframe.ArtifactNestedSetFunction.ArtifactNestedSetEvalException;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import javax.annotation.Nullable;

/** Limits speculative dependency requests without changing successful action validation. */
final class CacheProbeInputBatches implements SkyKeyComputeState {
  private static final int INITIAL_BATCH_SIZE = 32;
  private static final int MAX_BATCH_SIZE = 256;

  private int completed;
  private int batchSize = INITIAL_BATCH_SIZE;
  private boolean stoppedOnMiss;

  boolean stoppedOnMiss() {
    return stoppedOnMiss;
  }

  /**
   * Returns all requested keys once they settle, stopping new requests at the first expected miss.
   * The caller still classifies the entire requested prefix so real errors take precedence.
   */
  @Nullable
  ImmutableList<SkyKey> request(Environment env, ImmutableList<SkyKey> keys)
      throws InterruptedException {
    while (completed < keys.size()) {
      int end = Math.min(keys.size(), completed + batchSize);
      ImmutableList<SkyKey> batch = keys.subList(completed, end);
      SkyframeLookupResult values = env.getValuesAndExceptions(batch);
      boolean cacheMiss = false;
      for (SkyKey key : batch) {
        try {
          values.getOrThrow(
              key,
              SourceArtifactException.class,
              ActionExecutionException.class,
              ArtifactNestedSetEvalException.class);
        } catch (ActionExecutionException e) {
          cacheMiss |= e.isCacheProbeMiss();
        } catch (ArtifactNestedSetEvalException e) {
          cacheMiss |= e.getCacheProbeMiss() != null;
        } catch (SourceArtifactException e) {
          // Optional source inputs require the consuming action's normal error classification.
        }
      }
      if (env.valuesMissing()) {
        return null;
      }
      if (cacheMiss) {
        stoppedOnMiss = true;
        return keys.subList(0, end);
      }
      completed = end;
      batchSize = Math.min(MAX_BATCH_SIZE, batchSize * 4);
    }
    return keys;
  }
}
