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
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.skyframe.PartialReevaluationMailbox;
import com.google.devtools.build.skyframe.PartialReevaluationMailbox.Mail;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunction.Environment.ClassToInstanceMapSkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Reports provisional misses without waiting for every selected output of a root. */
public final class CacheProbeCompletion {
  private CacheProbeCompletion() {}

  /** Informational only: final completion and test outcomes remain authoritative. */
  public record MissingOutputEvent(ActionLookupKey actionLookupKey) implements Postable {}

  /** A completed test probe established that a required cached result is missing. */
  public record TestMissEvent(ConfiguredTargetKey configuredTargetKey) implements Postable {
    @Override
    public boolean storeForReplay() {
      return true;
    }
  }

  private static final class State implements SkyKeyComputeState {
    final Set<SkyKey> pending = new HashSet<>();
    boolean initialized;
    boolean reportedMissing;
  }

  /** Partial reevaluation requires explicit propagation of failed prerequisites. */
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

  /**
   * Observes completed output dependencies; returns true only when the normal completion pass can
   * examine all of them. No values or failures are synthesized or cached here.
   */
  static boolean awaitOutputs(
      Environment env,
      ActionLookupKey actionLookupKey,
      Supplier<? extends Iterable<? extends SkyKey>> outputKeys)
      throws InterruptedException {
    ClassToInstanceMapSkyKeyComputeState computeState =
        env.getState(ClassToInstanceMapSkyKeyComputeState::new);
    Mail mail = PartialReevaluationMailbox.from(computeState).getMail();
    State state = computeState.getInstance(State.class, State::new);
    boolean initial = !state.initialized;
    ImmutableList<? extends SkyKey> toCheck;
    SkyframeLookupResult result;
    if (initial) {
      toCheck = ImmutableList.copyOf(outputKeys.get());
      state.initialized = true;
      result = env.getValuesAndExceptions(toCheck);
    } else {
      if (state.pending.isEmpty()) {
        return true;
      }
      switch (mail.kind()) {
        case FRESHLY_INITIALIZED -> throw new IllegalStateException("Missing completion state");
        case EMPTY -> {
          return false;
        }
        case CAUSES ->
            toCheck =
                mail.causes().other()
                    ? ImmutableList.copyOf(state.pending)
                    : mail.causes().signaledDeps();
        default -> throw new IllegalStateException("Unexpected mailbox state: " + mail.kind());
      }
      result = env.getLookupHandleForPreviouslyRequestedDeps();
    }

    SkyframeLookupResult.QueryDepCallback observer =
        new SkyframeLookupResult.QueryDepCallback() {
          @Override
          public void acceptValue(SkyKey key, SkyValue value) {
            if (!initial) {
              state.pending.remove(key);
            }
          }

          @Override
          public boolean tryHandleException(SkyKey key, Exception exception) {
            if (!initial) {
              state.pending.remove(key);
            }
            if (exception instanceof ActionExecutionException failure
                && failure.isCacheProbeMiss()
                && !state.reportedMissing) {
              state.reportedMissing = true;
              env.getListener().post(new MissingOutputEvent(actionLookupKey));
            }
            // This is readiness bookkeeping, not final error handling. The ordinary completion
            // pass below re-reads every output and preserves genuine errors alongside misses.
            return true;
          }
        };
    for (SkyKey key : toCheck) {
      if (initial) {
        if (!result.queryDep(key, observer)) {
          state.pending.add(key);
        }
      } else if (state.pending.contains(key)) {
        result.queryDep(key, observer);
      }
    }
    return state.pending.isEmpty();
  }
}
