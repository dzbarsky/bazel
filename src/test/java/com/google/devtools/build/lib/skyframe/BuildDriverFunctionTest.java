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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ActionConflictsAndStats;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that conflict checks yield without being lost across Skyframe restarts. */
@RunWith(JUnit4.class)
public final class BuildDriverFunctionTest {
  private final IncrementalArtifactConflictFinder finder =
      mock(IncrementalArtifactConflictFinder.class);
  private final BuildDriverKey key = mock(BuildDriverKey.class);
  private final ActionLookupKey actionKey = mock(ActionLookupKey.class);
  private final SettableFuture<ActionConflictsAndStats> check = SettableFuture.create();
  private final Environment env = mock(Environment.class);
  private final AtomicReference<ListenableFuture<?>> dependency = new AtomicReference<>();
  private final BuildDriverFunction function =
      new BuildDriverFunction(() -> finder, () -> null, () -> null, () -> null, null);

  @Before
  public void setUp() {
    when(key.getActionLookupKey()).thenReturn(actionKey);
    when(finder.findArtifactConflictsAsync(actionKey)).thenReturn(check);
    doAnswer(
            invocation -> {
              dependency.set(invocation.getArgument(0));
              return null;
            })
        .when(env)
        .dependOnFuture(any());
    when(env.valuesMissing()).thenAnswer(unused -> !dependency.get().isDone());
  }

  @Test
  public void pendingCheck_yieldsAndReusesResult() throws Exception {
    assertThat(function.checkActionConflicts(key, env)).isNull();
    assertThat(function.checkActionConflicts(key, env)).isNull();
    ActionAnalysisMetadata action = mock(ActionAnalysisMetadata.class);
    ActionConflictException conflict = mock(ActionConflictException.class);
    ImmutableMap<ActionAnalysisMetadata, ActionConflictException> conflicts =
        ImmutableMap.of(action, conflict);
    check.set(ActionConflictsAndStats.create(conflicts, 1));

    assertThat(function.checkActionConflicts(key, env)).isEqualTo(conflicts);
    verify(finder).findArtifactConflictsAsync(actionKey);
  }

  @Test
  public void dependencyCancelled_doesNotCancelCheck() throws Exception {
    assertThat(function.checkActionConflicts(key, env)).isNull();
    dependency.get().cancel(true);
    assertThat(check.isCancelled()).isFalse();

    assertThat(function.checkActionConflicts(key, env)).isNull();
    check.set(ActionConflictsAndStats.create(ImmutableMap.of(), 0));
    assertThat(function.checkActionConflicts(key, env)).isEmpty();
    verify(finder).findArtifactConflictsAsync(actionKey);
  }

  @Test
  public void resetStates_startsNewCheck() throws Exception {
    check.set(ActionConflictsAndStats.create(ImmutableMap.of(), 0));
    assertThat(function.checkActionConflicts(key, env)).isEmpty();
    function.resetStates();
    SettableFuture<ActionConflictsAndStats> nextCheck = SettableFuture.create();
    when(finder.findArtifactConflictsAsync(actionKey)).thenReturn(nextCheck);

    assertThat(function.checkActionConflicts(key, env)).isNull();
    verify(finder, times(2)).findArtifactConflictsAsync(actionKey);
  }

  @Test
  public void checkCancelled_interruptsInsteadOfAllowingExecution() {
    check.cancel(true);
    assertThrows(InterruptedException.class, () -> function.checkActionConflicts(key, env));
  }

  @Test
  public void checkFailed_propagatesFailure() {
    IllegalStateException failure = new IllegalStateException("conflict traversal failed");
    check.setException(failure);
    assertThat(
            assertThrows(
                IllegalStateException.class, () -> function.checkActionConflicts(key, env)))
        .isSameInstanceAs(failure);
  }
}
