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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.analysis.ConfiguredObjectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestProvider.TestParams;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.causes.LabelCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn;
import com.google.devtools.build.lib.skyframe.CacheProbeCompletion.MissingOutputEvent;
import com.google.devtools.build.lib.skyframe.CacheProbeCompletion.TestMissEvent;
import com.google.devtools.build.lib.skyframe.CompletionFunction.Completor;
import com.google.devtools.build.lib.skyframe.MetadataConsumerForMetrics.FilesMetricConsumer;
import com.google.devtools.build.lib.skyframe.TargetCompletionValue.TargetCompletionKey;
import com.google.devtools.build.lib.skyframe.rewinding.ActionRewindStrategy;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EmittedEventState;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationProgressReceiver;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.EventFilter;
import com.google.devtools.build.skyframe.GraphInconsistencyReceiver;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CacheProbeCompletionTest extends FoundationTestCase {
  private static final Label LABEL = Label.parseCanonicalUnchecked("//pkg:root");
  private static final ConfiguredTargetKey CONFIGURED_TARGET =
      ConfiguredTargetKey.builder().setLabel(LABEL).build();

  private record FinishedEvent() implements Postable {
    @Override
    public boolean storeForReplay() {
      return true;
    }
  }

  @Test
  public void reportsMissBeforeSiblingFinishesAndRetriesTransientMisses() throws Exception {
    Fixture fixture = createFixture(/* probe= */ true, Spawn.Code.CACHE_PROBE_MISS);

    assertThat(fixture.evaluate().isCacheProbeMiss()).isTrue();
    assertThat(fixture.events.getPosts())
        .containsExactly(new MissingOutputEvent(CONFIGURED_TARGET), new FinishedEvent())
        .inOrder();
    assertThat(fixture.executions.get()).isEqualTo(2);

    fixture.events.clear();
    assertThat(fixture.evaluate().isCacheProbeMiss()).isTrue();
    assertThat(fixture.events.getPosts()).doesNotContain(new MissingOutputEvent(CONFIGURED_TARGET));
    assertThat(fixture.executions.get()).isEqualTo(2);

    fixture.events.clear();
    fixture.differencer.invalidateTransientErrors();
    assertThat(fixture.evaluate().isCacheProbeMiss()).isTrue();
    assertThat(fixture.events.getPosts()).contains(new MissingOutputEvent(CONFIGURED_TARGET));
    assertThat(
            fixture.events.getPosts().stream().filter(MissingOutputEvent.class::isInstance).count())
        .isEqualTo(1);
    assertThat(fixture.executions.get()).isEqualTo(4);
  }

  @Test
  public void provisionalMissDoesNotHideDelayedRealError() throws Exception {
    Fixture fixture = createFixture(/* probe= */ true, Spawn.Code.EXEC_IO_EXCEPTION);

    ActionExecutionException failure = fixture.evaluate();

    assertThat(failure.isCacheProbeMiss()).isFalse();
    assertThat(failure.getDetailedExitCode().getFailureDetail().getSpawn().getCode())
        .isEqualTo(Spawn.Code.EXEC_IO_EXCEPTION);
    assertThat(failure.getRootCauses().toList()).hasSize(1);
    assertThat(fixture.events.getPosts())
        .containsExactly(new MissingOutputEvent(CONFIGURED_TARGET), new FinishedEvent())
        .inOrder();
  }

  @Test
  public void normalCompletionDoesNotReportProvisionalMisses() throws Exception {
    Fixture fixture = createFixture(/* probe= */ false, Spawn.Code.EXEC_IO_EXCEPTION);

    assertThat(fixture.evaluate().isCacheProbeMiss()).isFalse();
    assertThat(fixture.events.getPosts()).containsExactly(new FinishedEvent());
    assertThat(fixture.key.supportsPartialReevaluation()).isFalse();
  }

  @Test
  public void testCompletionPropagatesFailedTargetPrerequisite() throws Exception {
    for (Spawn.Code code :
        ImmutableList.of(Spawn.Code.CACHE_PROBE_MISS, Spawn.Code.EXEC_IO_EXCEPTION)) {
      Fixture fixture = createFixture(/* probe= */ true, code);
      SkyKey testKey =
          TestCompletionValue.key(CONFIGURED_TARGET, fixture.key.topLevelArtifactContext(), false);

      EvaluationResult<SkyValue> result = fixture.evaluateResult(testKey);

      assertThat(result.get(testKey)).isNull();
      assertThat(result.getError(testKey).getException())
          .isInstanceOf(ActionExecutionException.class);
      ActionExecutionException failure =
          (ActionExecutionException) result.getError(testKey).getException();
      assertThat(failure.getDetailedExitCode().getFailureDetail().getSpawn().getCode())
          .isEqualTo(code);
      assertThat(fixture.events.getPosts())
          .containsExactly(new MissingOutputEvent(CONFIGURED_TARGET), new FinishedEvent())
          .inOrder();
    }
  }

  @Test
  public void completionPropagatesAnalysisPrerequisiteFailure() throws Exception {
    ConfiguredValueCreationException failure =
        new ConfiguredValueCreationException(
            /* location= */ null,
            "analysis failed",
            LABEL,
            /* configuration= */ null,
            /* rootCauses= */ null,
            /* detailedExitCode= */ null);
    Fixture fixture =
        createFixture(
            /* probe= */ true, Spawn.Code.CACHE_PROBE_MISS, /* testStatusOnly= */ false, failure);
    for (SkyKey key :
        ImmutableList.of(
            fixture.key,
            TestCompletionValue.key(
                CONFIGURED_TARGET, fixture.key.topLevelArtifactContext(), false))) {
      EvaluationResult<SkyValue> result = fixture.evaluateResult(key);

      assertThat(result.get(key)).isNull();
      assertThat(result.getError(key).getException()).isSameInstanceAs(failure);
    }
    assertThat(fixture.executions.get()).isEqualTo(0);
    assertThat(fixture.events.getPosts()).isEmpty();
  }

  @Test
  public void testStatusFailuresFinishWithoutSyntheticResults() throws Exception {
    for (Spawn.Code code :
        ImmutableList.of(Spawn.Code.CACHE_PROBE_MISS, Spawn.Code.EXEC_IO_EXCEPTION)) {
      assertTestStatusFailure(/* exclusive= */ false, code);
      assertTestStatusFailure(/* exclusive= */ true, code);
    }
  }

  private void assertTestStatusFailure(boolean exclusive, Spawn.Code code) throws Exception {
    Fixture fixture =
        createFixture(
            /* probe= */ true, code, /* testStatusOnly= */ true, /* prerequisiteFailure= */ null);
    SkyKey testKey =
        TestCompletionValue.key(
            CONFIGURED_TARGET, fixture.key.topLevelArtifactContext(), exclusive);

    EvaluationResult<SkyValue> result = fixture.evaluateResult(testKey);

    assertThat(result.get(testKey)).isNull();
    ActionExecutionException failure =
        (ActionExecutionException) result.getError(testKey).getException();
    assertThat(failure.getDetailedExitCode().getFailureDetail().getSpawn().getCode())
        .isEqualTo(code);
    if (code == Spawn.Code.CACHE_PROBE_MISS) {
      assertThat(fixture.events.getPosts())
          .containsExactly(
              new MissingOutputEvent(CONFIGURED_TARGET), new TestMissEvent(CONFIGURED_TARGET))
          .inOrder();
      fixture.events.clear();
      fixture.emittedEventState.clear();
      assertThat(fixture.evaluateResult(testKey).getError(testKey)).isNotNull();
      assertThat(fixture.events.getPosts()).containsExactly(new TestMissEvent(CONFIGURED_TARGET));
    } else {
      assertThat(fixture.events.getPosts())
          .containsExactly(new MissingOutputEvent(CONFIGURED_TARGET));
    }
  }

  private Fixture createFixture(boolean probe, Spawn.Code delayedCode) throws Exception {
    return createFixture(probe, delayedCode, false, null);
  }

  @SuppressWarnings("unchecked") // Mockito cannot express the Completor type parameters.
  private Fixture createFixture(
      boolean probe,
      Spawn.Code delayedCode,
      boolean testStatusOnly,
      @Nullable Exception prerequisiteFailure)
      throws Exception {
    TopLevelArtifactContext context =
        new TopLevelArtifactContext(
            false, false, ImmutableSortedSet.of(OutputGroupInfo.DEFAULT), false, probe);
    TargetCompletionKey key = TargetCompletionValue.key(CONFIGURED_TARGET, context, false);
    DerivedArtifact miss = output("miss", 0);
    DerivedArtifact delayed = output("delayed", 1);
    ConfiguredTarget target = mock(ConfiguredTarget.class);
    when(target.getProvider(FileProvider.class))
        .thenReturn(FileProvider.of(NestedSetBuilder.create(Order.STABLE_ORDER, miss, delayed)));
    TestParams testParams = mock(TestParams.class);
    when(testParams.getTestStatusArtifacts()).thenReturn(ImmutableList.of(miss, delayed));
    when(target.getProvider(TestProvider.class)).thenReturn(new TestProvider(testParams));
    ConfiguredTargetValue value = mock(ConfiguredTargetValue.class);
    when(value.getConfiguredObject()).thenReturn(target);
    when(value.getConfiguredTarget()).thenReturn(target);
    Completor<ConfiguredObjectValue, SkyValue, TargetCompletionKey> completor =
        mock(Completor.class);
    when(completor.createFailed(any(), any(), any(), any(), any(), any()))
        .thenReturn(new FinishedEvent());
    CompletionFunction<ConfiguredObjectValue, SkyValue, TargetCompletionKey> completion =
        new CompletionFunction<>(
            unused -> ArtifactPathResolver.IDENTITY,
            completor,
            mock(SkyframeActionExecutor.class),
            new FilesMetricConsumer(),
            mock(ActionRewindStrategy.class),
            mock(BugReporter.class));
    CountDownLatch earlyMiss = new CountDownLatch(1);
    StoredEventHandler events =
        new StoredEventHandler() {
          @Override
          public synchronized void post(Postable event) {
            super.post(event);
            if (event instanceof MissingOutputEvent) {
              earlyMiss.countDown();
            }
          }
        };
    AtomicInteger executions = new AtomicInteger();
    SkyFunction execute =
        (actionKey, env) -> {
          executions.incrementAndGet();
          boolean isDelayed = actionKey.equals(delayed.getGeneratingActionKey());
          if (isDelayed && probe) {
            // Completion cannot finish until this sibling does. Only an immediate, non-replayed
            // provisional event can release the sibling and allow evaluation to complete.
            assertThat(earlyMiss.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
          }
          throw new SkyFunctionException(
              failure(isDelayed ? delayedCode : Spawn.Code.CACHE_PROBE_MISS),
              SkyFunctionException.Transience.TRANSIENT) {};
        };
    SequencedRecordingDifferencer differencer = new SequencedRecordingDifferencer();
    SkyFunction configuredTarget =
        (unusedKey, unusedEnv) -> {
          if (prerequisiteFailure != null) {
            throw new SkyFunctionException(
                prerequisiteFailure, SkyFunctionException.Transience.PERSISTENT) {};
          }
          return value;
        };
    EmittedEventState emittedEventState = new EmittedEventState();
    MemoizingEvaluator evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.of(
                SkyFunctions.CONFIGURED_TARGET,
                configuredTarget,
                SkyFunctions.TARGET_COMPLETION,
                testStatusOnly
                    ? (unusedKey, unusedEnv) -> TargetCompletionValue.INSTANCE
                    : completion,
                SkyFunctions.TEST_COMPLETION,
                new TestCompletionFunction(),
                SkyFunctions.ACTION_EXECUTION,
                execute),
            differencer,
            EvaluationProgressReceiver.NULL,
            GraphInconsistencyReceiver.THROWING,
            EventFilter.FULL_STORAGE,
            emittedEventState,
            /* keepEdges= */ true,
            /* usePooledInterning= */ true);
    return new Fixture(key, events, emittedEventState, executions, differencer, evaluator);
  }

  private DerivedArtifact output(String name, int index) {
    DerivedArtifact artifact =
        DerivedArtifact.create(
            ArtifactRoot.asDerivedRoot(rootDirectory, RootType.OUTPUT, "out"),
            PathFragment.create("out/" + name),
            CONFIGURED_TARGET);
    artifact.setGeneratingActionKey(ActionLookupData.create(CONFIGURED_TARGET, index));
    return artifact;
  }

  private static ActionExecutionException failure(Spawn.Code code) {
    DetailedExitCode exitCode =
        DetailedExitCode.of(
            FailureDetail.newBuilder().setSpawn(Spawn.newBuilder().setCode(code)).build());
    return new ActionExecutionException(
        code.toString(),
        /* action= */ null,
        NestedSetBuilder.create(Order.STABLE_ORDER, new LabelCause(LABEL, exitCode)),
        /* catastrophe= */ false,
        exitCode);
  }

  private record Fixture(
      TargetCompletionKey key,
      StoredEventHandler events,
      EmittedEventState emittedEventState,
      AtomicInteger executions,
      SequencedRecordingDifferencer differencer,
      MemoizingEvaluator evaluator) {
    ActionExecutionException evaluate() throws InterruptedException {
      EvaluationResult<SkyValue> result = evaluateResult(key);
      assertThat(result.hasError()).isTrue();
      assertThat(result.getError(key).getException()).isInstanceOf(ActionExecutionException.class);
      return (ActionExecutionException) result.getError(key).getException();
    }

    EvaluationResult<SkyValue> evaluateResult(SkyKey evaluationKey) throws InterruptedException {
      return evaluator.evaluate(
          ImmutableList.of(evaluationKey),
          EvaluationContext.newBuilder()
              .setKeepGoing(true)
              .setParallelism(2)
              .setEventHandler(events)
              .build());
    }
  }
}
