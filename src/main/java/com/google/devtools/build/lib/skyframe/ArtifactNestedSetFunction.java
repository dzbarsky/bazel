// Copyright 2019 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.collect.nestedset.ArtifactNestedSetKey;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.MissingArtifactValue;
import com.google.devtools.build.lib.skyframe.ArtifactFunction.SourceArtifactException;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A builder of values for {@link ArtifactNestedSetKey}.
 *
 * <p>When an Action is executed with ActionExecutionFunction, the actions's input {@code
 * NestedSet<Artifact>} could be evaluated as an {@link ArtifactNestedSetKey}[1].
 *
 * <p>{@link ArtifactNestedSetFunction} then evaluates the {@link ArtifactNestedSetKey} by:
 *
 * <p>- Evaluating the directs elements as Artifacts.
 *
 * <p>- Evaluating the transitive elements as {@link ArtifactNestedSetKey}s.
 *
 * <p>[1] Heuristic: If the size of the NestedSet exceeds a certain threshold, we evaluate it as an
 * ArtifactNestedSetKey.
 */
final class ArtifactNestedSetFunction implements SkyFunction {

  private final Supplier<ConsumedArtifactsTracker> consumedArtifactsTrackerSupplier;
  private final BooleanSupplier cacheProbe;

  ArtifactNestedSetFunction(
      Supplier<ConsumedArtifactsTracker> consumedArtifactsTrackerSupplier,
      BooleanSupplier cacheProbe) {
    this.consumedArtifactsTrackerSupplier = consumedArtifactsTrackerSupplier;
    this.cacheProbe = cacheProbe;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, ArtifactNestedSetFunctionException {
    ArtifactNestedSetKey artifactNestedSetKey = (ArtifactNestedSetKey) skyKey;
    if (consumedArtifactsTrackerSupplier.get() != null) {
      artifactNestedSetKey.applyToDirectArtifacts(
          (x) -> consumedArtifactsTrackerSupplier.get().registerConsumedArtifact(x));
    }
    ImmutableList<SkyKey> depKeys = artifactNestedSetKey.getDirectDepKeys();
    if (cacheProbe.getAsBoolean()) {
      depKeys = env.getState(CacheProbeInputBatches::new).request(env, depKeys);
      if (depKeys == null) {
        return null;
      }
    }
    SkyframeLookupResult depsEvalResult = env.getValuesAndExceptions(depKeys);

    NestedSetBuilder<Pair<SkyKey, Exception>> transitiveExceptionsBuilder =
        NestedSetBuilder.stableOrder();
    boolean catastrophic = false;
    Pair<SkyKey, ActionExecutionException> cacheProbeMiss = null;
    ArtifactNestedSetValue result = ArtifactNestedSetValue.ALL_PRESENT;

    // Throw a SkyFunctionException when a dep evaluation results in an exception.
    for (SkyKey key : depKeys) {
      try {
        // Trigger the exception, if any.
        SkyValue value =
            depsEvalResult.getOrThrow(
                key,
                SourceArtifactException.class,
                ActionExecutionException.class,
                ArtifactNestedSetEvalException.class);
        if (value == null) {
          continue;
        }

        if (key instanceof ArtifactNestedSetKey) {
          if (value == ArtifactNestedSetValue.SOME_MISSING) {
            result = ArtifactNestedSetValue.SOME_MISSING;
          }
          continue;
        }

        if (value instanceof MissingArtifactValue) {
          result = ArtifactNestedSetValue.SOME_MISSING;
        }
      } catch (SourceArtifactException e) {
        // SourceArtifactException is never catastrophic.
        transitiveExceptionsBuilder.add(Pair.of(key, e));
      } catch (ActionExecutionException e) {
        if (e.isCacheProbeMiss()) {
          if (cacheProbeMiss == null) {
            cacheProbeMiss = Pair.of(key, e);
          }
        } else {
          transitiveExceptionsBuilder.add(Pair.of(key, e));
        }
        catastrophic |= e.isCatastrophe();
      } catch (ArtifactNestedSetEvalException e) {
        catastrophic |= e.isCatastrophic();
        if (e.hasMissingInputs()) {
          result = ArtifactNestedSetValue.SOME_MISSING;
        }
        transitiveExceptionsBuilder.addTransitive(e.nestedExceptions);
        if (cacheProbeMiss == null) {
          cacheProbeMiss = e.cacheProbeMiss;
        }
      }
    }

    if (!transitiveExceptionsBuilder.isEmpty() || cacheProbeMiss != null) {
      NestedSet<Pair<SkyKey, Exception>> transitiveExceptions = transitiveExceptionsBuilder.build();
      // The NestedSet of exceptions is usually small, hence flattening won't be too costly.
      Pair<SkyKey, ? extends Exception> firstSkyKeyAndException =
          transitiveExceptions.isEmpty() ? cacheProbeMiss : transitiveExceptions.toList().get(0);
      throw new ArtifactNestedSetFunctionException(
          new ArtifactNestedSetEvalException(
              "Error evaluating artifact nested set. First exception: "
                  + firstSkyKeyAndException.getSecond()
                  + ", SkyKey: "
                  + firstSkyKeyAndException.getFirst(),
              transitiveExceptions,
              catastrophic,
              result == ArtifactNestedSetValue.SOME_MISSING,
              cacheProbeMiss));
    }

    // This should only happen when all error handling is done.
    if (env.valuesMissing()) {
      return null;
    }
    return result;
  }

  /** Mainly used for error bubbling when evaluating direct/transitive children. */
  private static final class ArtifactNestedSetFunctionException extends SkyFunctionException {

    private final boolean catastrophic;

    ArtifactNestedSetFunctionException(ArtifactNestedSetEvalException e) {
      super(e, Transience.PERSISTENT);
      this.catastrophic = e.isCatastrophic();
    }

    @Override
    public boolean isCatastrophic() {
      return catastrophic;
    }
  }

  /** Bundles the exceptions from the evaluation of the children keys together. */
  static final class ArtifactNestedSetEvalException extends Exception {

    private final NestedSet<Pair<SkyKey, Exception>> nestedExceptions;
    private final boolean catastrophic;
    private final boolean hasMissingInputs;
    @Nullable private final Pair<SkyKey, ActionExecutionException> cacheProbeMiss;

    ArtifactNestedSetEvalException(
        String message,
        NestedSet<Pair<SkyKey, Exception>> nestedExceptions,
        boolean catastrophic,
        boolean hasMissingInputs,
        @Nullable Pair<SkyKey, ActionExecutionException> cacheProbeMiss) {
      super(message);
      this.nestedExceptions = nestedExceptions;
      this.catastrophic = catastrophic;
      this.hasMissingInputs = hasMissingInputs;
      this.cacheProbeMiss = cacheProbeMiss;
    }

    NestedSet<Pair<SkyKey, Exception>> getNestedExceptions() {
      return nestedExceptions;
    }

    @Nullable
    ActionExecutionException getCacheProbeMiss() {
      return cacheProbeMiss == null ? null : cacheProbeMiss.getSecond();
    }

    boolean hasCacheProbeMiss() {
      return cacheProbeMiss != null;
    }

    // Should be true if at least one child exception is catastrophic.
    boolean isCatastrophic() {
      return catastrophic;
    }

    boolean hasMissingInputs() {
      return hasMissingInputs;
    }
  }
}
