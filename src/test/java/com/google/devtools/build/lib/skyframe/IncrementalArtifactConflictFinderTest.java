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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.MutableActionGraph;
import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ActionConflictsAndStats;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests the lifetime of asynchronous conflict checks and their worker pools. */
@RunWith(JUnit4.class)
public final class IncrementalArtifactConflictFinderTest {
  private final CountDownLatch collectionStarted = new CountDownLatch(1);
  private final CountDownLatch releaseCollection = new CountDownLatch(1);
  private final WalkableGraph graph = mock(WalkableGraph.class);
  private final MutableActionGraph actionGraph = mock(MutableActionGraph.class);
  private final IncrementalArtifactConflictFinder finder =
      new IncrementalArtifactConflictFinder(actionGraph, graph);

  @Before
  public void setUp() throws Exception {
    when(graph.getValue(any()))
        .thenAnswer(
            unused -> {
              collectionStarted.countDown();
              releaseCollection.await();
              return null;
            });
  }

  @After
  public void tearDown() {
    releaseCollection.countDown();
    finder.shutdown();
  }

  @Test
  public void blockedCollection_doesNotBlockCaller() throws Exception {
    ListenableFuture<ActionConflictsAndStats> check =
        finder.findArtifactConflictsAsync(mock(ActionLookupKey.class));
    assertThat(collectionStarted.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(check.isDone()).isFalse();

    releaseCollection.countDown();
    assertThat(check.get(10, TimeUnit.SECONDS).conflicts()).isEmpty();
  }

  @Test
  public void shutdown_cancelsRunningAndQueuedChecks() throws Exception {
    List<ListenableFuture<ActionConflictsAndStats>> checks = new ArrayList<>();
    // Exceed the bounded worker count so shutdown must cancel queued checks too.
    for (int i = 0; i < ArtifactConflictFinder.NUM_JOBS * 2; i++) {
      checks.add(finder.findArtifactConflictsAsync(mock(ActionLookupKey.class)));
    }
    assertThat(collectionStarted.await(10, TimeUnit.SECONDS)).isTrue();
    finder.shutdown();

    for (ListenableFuture<?> check : checks) {
      assertThat(check.isCancelled()).isTrue();
    }
    assertThat(finder.findArtifactConflictsAsync(mock(ActionLookupKey.class)).isCancelled())
        .isTrue();
  }

  @Test
  public void shutdown_interruptsActionRegistration() throws Exception {
    ActionLookupKey key = mock(ActionLookupKey.class);
    ActionLookupValue value = mock(ActionLookupValue.class);
    ActionAnalysisMetadata action = mock(ActionAnalysisMetadata.class);
    doReturn(value).when(graph).getValue(key);
    when(graph.getDirectDeps(key)).thenReturn(ImmutableList.of());
    when(value.getActions()).thenReturn(ImmutableList.of(action));
    CountDownLatch actionStarted = new CountDownLatch(1);
    CountDownLatch releaseAction = new CountDownLatch(1);
    doAnswer(
            unused -> {
              actionStarted.countDown();
              releaseAction.await();
              return null;
            })
        .when(actionGraph)
        .registerAction(action);

    try {
      ListenableFuture<ActionConflictsAndStats> check = finder.findArtifactConflictsAsync(key);
      assertThat(actionStarted.await(10, TimeUnit.SECONDS)).isTrue();
      finder.shutdown();
      assertThat(check.isCancelled()).isTrue();
      assertThat(Thread.currentThread().isInterrupted()).isFalse();
    } finally {
      releaseAction.countDown();
    }
  }
}
