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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.ActionTemplate;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.RunfilesArtifactValue;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue;
import com.google.devtools.build.lib.skyframe.ActionTemplateExpansionValue.ActionTemplateExpansionKey;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

/** Tests for {@link RemoteRewoundActionSynchronizer}. */
@RunWith(TestParameterInjector.class)
public final class RemoteRewoundActionSynchronizerTest {
  private static final long DEADLOCK_TIMEOUT_MILLIS = 10_000;

  private RemoteActionInputFetcher actionInputFetcher;
  private WalkableGraph graph;
  private RemoteRewoundActionSynchronizer synchronizer;

  @Before
  public void setUp() {
    actionInputFetcher = mock(RemoteActionInputFetcher.class);
    graph = mock(WalkableGraph.class);
    synchronizer = new RemoteRewoundActionSynchronizer(actionInputFetcher, graph);
  }

  @Test
  public void rewind_cancelsAllRegisteredTasksOnce() throws Exception {
    Action action = newAction();
    var first = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    var second = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    var unusedFirst = synchronizer.registerOutputUploadTask(action, first);
    var unusedSecond = synchronizer.registerOutputUploadTask(action, second);

    rewind(action);
    rewind(action);

    verify(first, times(1)).requestCancellation();
    verify(first, times(1)).awaitCompletion();
    verify(second, times(1)).requestCancellation();
    verify(second, times(1)).awaitCompletion();
  }

  @Test
  public void rewind_interruptedWhileAwaiting_cancelsAndAwaitsEveryTask() throws Exception {
    Action action = newAction();
    var first = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    var second = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    doThrow(new InterruptedException()).doNothing().when(first).awaitCompletion();
    var unusedFirst = synchronizer.registerOutputUploadTask(action, first);
    var unusedSecond = synchronizer.registerOutputUploadTask(action, second);

    assertThrows(InterruptedException.class, () -> rewind(action));

    InOrder order = inOrder(first, second);
    order.verify(first).requestCancellation();
    order.verify(second).requestCancellation();
    order.verify(first, times(2)).awaitCompletion();
    order.verify(second).awaitCompletion();
    verify(actionInputFetcher, never()).handleRewoundActionOutputs(any());
  }

  @Test
  public void unregisterHandle_preventsCancellation() throws Exception {
    Action action = newAction();
    var task = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    Runnable unregister = synchronizer.registerOutputUploadTask(action, task);

    unregister.run();
    rewind(action);

    verify(task, never()).requestCancellation();
    verify(task, never()).awaitCompletion();
    assertThat(synchronizer.hasRegisteredOutputUploadTasks(action)).isFalse();
  }

  @Test
  public void predecessorUnregisterHandle_doesNotRemoveReplacementTask() throws Exception {
    Action action = newAction();
    var predecessor = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    Runnable unregisterPredecessor = synchronizer.registerOutputUploadTask(action, predecessor);
    rewind(action);
    var replacement = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    var unused = synchronizer.registerOutputUploadTask(action, replacement);

    unregisterPredecessor.run();
    rewind(action);

    verify(predecessor, times(1)).requestCancellation();
    verify(predecessor, times(1)).awaitCompletion();
    verify(replacement, times(1)).requestCancellation();
    verify(replacement, times(1)).awaitCompletion();
  }

  @Test
  public void unregisterHandle_usesIdentityComparison() throws Exception {
    Action action = newAction();
    var first = new EqualCancellable();
    var second = new EqualCancellable();
    Runnable unregisterFirst = synchronizer.registerOutputUploadTask(action, first);
    var unused = synchronizer.registerOutputUploadTask(action, second);

    unregisterFirst.run();
    rewind(action);

    assertThat(first.cancellations.get()).isEqualTo(0);
    assertThat(second.cancellations.get()).isEqualTo(1);
  }

  /**
   * Shared actions are equivalent actions of different owners generating the same outputs, which
   * SkyframeActionExecutor executes as one. A consumer of one owner's output excludes the rewinding
   * of the other owner's action, which deletes the same file, and that rewinding cancels the
   * uploads registered by either owner.
   */
  @Test
  public void sharedAction_rewoundUnderOtherOwner_waitsForConsumerAndCancelsUpload(
      @TestParameter boolean inflateFineLocksFirst) throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    var otherOwner = ActionsTestUtil.YET_ANOTHER_NULL_ARTIFACT_OWNER;
    PathFragment sharedExecPath = root.getExecPath().getRelative("shared");
    DerivedArtifact output = DerivedArtifact.create(root, sharedExecPath, owner);
    output.setGeneratingActionKey(ActionLookupData.create(owner, 0));
    Action action = newAction(ImmutableList.of(output), ImmutableList.of());
    DerivedArtifact otherOutput = DerivedArtifact.create(root, sharedExecPath, otherOwner);
    otherOutput.setGeneratingActionKey(ActionLookupData.create(otherOwner, 0));
    Action otherAction = newAction(ImmutableList.of(otherOutput), ImmutableList.of());
    DerivedArtifact consumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "consumer.out");
    consumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action consumer = newAction(ImmutableList.of(consumerOutput), ImmutableList.of(output));
    mockActions(owner, ImmutableList.of(action, consumer));
    mockActions(otherOwner, ImmutableList.of(otherAction));
    var upload = mock(RemoteRewoundActionSynchronizer.Cancellable.class);
    var unused = synchronizer.registerOutputUploadTask(action, upload);

    maybeInflateFineLocks(inflateFineLocksFirst);

    var otherPreparation = new TestThread(() -> rewind(otherAction));
    try (SilentCloseable execution =
        synchronizer.enterActionExecution(
            consumer, /* wasRewound= */ false, mock(InputMetadataProvider.class))) {
      otherPreparation.start();
      waitUntilBlocked(otherPreparation);
    }
    otherPreparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    verify(upload).requestCancellation();
    verify(upload).awaitCompletion();
    assertThat(synchronizer.hasRegisteredOutputUploadTasks(action)).isFalse();
  }

  /**
   * A runfiles tree is guarded by the keys of the actions generating the artifacts it contains,
   * which are taken from its metadata rather than from all runfiles trees known to the metadata
   * provider. The action generating the runfiles tree itself isn't excluded, as it doesn't write to
   * disk.
   */
  @Test
  public void outputProcessing_runfilesTree_locksOnlyItsProducers(
      @TestParameter boolean inflateFineLocksFirst) throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    DerivedArtifact file = (DerivedArtifact) ActionsTestUtil.createArtifact(root, "file");
    file.setGeneratingActionKey(ActionLookupData.create(owner, 0));
    Action producer = newAction(ImmutableList.of(file), ImmutableList.of());
    DerivedArtifact unrelatedFile =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "unrelated");
    unrelatedFile.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action unrelatedProducer = newAction(ImmutableList.of(unrelatedFile), ImmutableList.of());
    SpecialArtifact runfiles = ActionsTestUtil.createRunfilesArtifact(root, "out/runfiles");
    runfiles.setGeneratingActionKey(ActionLookupData.create(owner, 2));
    Action runfilesAction = newAction(ImmutableList.of(runfiles), ImmutableList.of(file));
    mockActions(owner, ImmutableList.of(producer, unrelatedProducer, runfilesAction));

    RunfilesTree runfilesTree = mock(RunfilesTree.class);
    when(runfilesTree.getArtifacts()).thenReturn(NestedSetBuilder.create(Order.STABLE_ORDER, file));
    RunfilesTree unrelatedRunfilesTree = mock(RunfilesTree.class);
    when(unrelatedRunfilesTree.getArtifacts())
        .thenReturn(NestedSetBuilder.create(Order.STABLE_ORDER, unrelatedFile));
    InputMetadataProvider metadataProvider = mock(InputMetadataProvider.class);
    when(metadataProvider.getRunfilesTrees())
        .thenReturn(ImmutableList.of(runfilesTree, unrelatedRunfilesTree));
    var runfilesValue =
        new RunfilesArtifactValue(
            runfilesTree,
            ImmutableList.of(file),
            ImmutableList.of(FileArtifactValue.createForNormalFile(new byte[32], null, 0)),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of());
    when(metadataProvider.getRunfilesMetadata(runfiles)).thenReturn(runfilesValue);

    maybeInflateFineLocks(inflateFineLocksFirst);

    var preparation = new TestThread(() -> rewind(producer));
    var unrelatedPreparation = new TestThread(() -> rewind(unrelatedProducer));
    var runfilesPreparation = new TestThread(() -> rewind(runfilesAction));
    try (SilentCloseable processing =
        synchronizer.enterProcessOutputsAndGetLostArtifacts(
            ImmutableList.of(runfiles), metadataProvider)) {
      preparation.start();
      waitUntilBlocked(preparation);
      unrelatedPreparation.start();
      unrelatedPreparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
      runfilesPreparation.start();
      runfilesPreparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    }
    preparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
  }

  /**
   * A consumer of one output tree of an {@link ActionTemplate} doesn't depend on expanded actions
   * that only populate another output tree of the same template and thus must not wait for their
   * rewinding, whereas it does exclude the rewinding of the actions populating its own tree.
   */
  @Test
  public void expandedActionRewound_treeConsumer_excludesOnlyProducersOfItsTree(
      @TestParameter boolean inflateFineLocksFirst) throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    var templateKey = ActionLookupData.create(owner, 0);
    ActionTemplateExpansionKey expansion = ActionTemplateExpansionValue.key(owner, 0);
    SpecialArtifact firstTree = newTreeArtifact(root, "first", templateKey);
    SpecialArtifact secondTree = newTreeArtifact(root, "second", templateKey);
    TreeFileArtifact firstFile =
        TreeFileArtifact.createTemplateExpansionOutput(firstTree, "file", expansion);
    firstFile.setGeneratingActionKey(ActionLookupData.create(expansion, 0));
    TreeFileArtifact secondFile =
        TreeFileArtifact.createTemplateExpansionOutput(secondTree, "file", expansion);
    secondFile.setGeneratingActionKey(ActionLookupData.create(expansion, 1));
    Action firstProducer = newAction(ImmutableList.of(firstFile), ImmutableList.of());
    Action secondProducer = newAction(ImmutableList.of(secondFile), ImmutableList.of());
    DerivedArtifact consumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "consumer.out");
    consumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action consumer = newAction(ImmutableList.of(consumerOutput), ImmutableList.of(firstTree));

    InputMetadataProvider metadataProvider = mock(InputMetadataProvider.class);
    mockTemplateExpansion(owner, ImmutableList.of(firstProducer, secondProducer));

    maybeInflateFineLocks(inflateFineLocksFirst);

    try (SilentCloseable secondPreparation =
        synchronizer.enterActionPreparation(secondProducer, /* wasRewound= */ true)) {
      // The consumer of the first tree is admitted while the producer of the second tree holds its
      // write lock.
      var consumerExecution =
          new TestThread(
              () -> {
                try (SilentCloseable execution =
                    synchronizer.enterActionExecution(
                        consumer, /* wasRewound= */ false, metadataProvider)) {}
              });
      consumerExecution.start();
      consumerExecution.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);

      // The producer of the first tree still waits for the consumer.
      var firstPreparation = new TestThread(() -> rewind(firstProducer));
      try (SilentCloseable execution =
          synchronizer.enterActionExecution(consumer, /* wasRewound= */ false, metadataProvider)) {
        firstPreparation.start();
        waitUntilBlocked(firstPreparation);
      }
      firstPreparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    }
  }

  /**
   * Regression test for a lock cycle between the rewound expanded action P of an action template, a
   * rewound consumer C of the tree artifact P populates, and an action D expanded from a downstream
   * template that consumes both P's file and C's output:
   *
   * <ol>
   *   <li>D holds the read lock of P and waits for the read lock of C, which C holds for writing.
   *   <li>P waits for the write lock of its own key, which D holds for reading.
   *   <li>C, which depends on P but not on D, requests the read lock of P.
   * </ol>
   *
   * <p>C must be admitted even though P is waiting for the same lock as a writer. Any lock that
   * queues readers behind waiting writers would make C wait for P and close the cycle. {@link
   * java.util.concurrent.locks.ReentrantReadWriteLock} does so in both fairness modes and {@link
   * java.util.concurrent.locks.StampedLock} does so in {@code readLock}, but not in {@code
   * readLockInterruptibly}, which only waits for a writer that holds the lock.
   */
  @Test
  public void expandedActionRewound_treeConsumerNotQueuedBehindWaitingProducer() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;

    // The upstream template (action 0 of the owner) declares a tree artifact that its single
    // expanded action P populates with a file.
    SpecialArtifact upstreamTree =
        newTreeArtifact(root, "upstream", ActionLookupData.create(owner, 0));
    ActionTemplateExpansionKey upstreamExpansion = ActionTemplateExpansionValue.key(owner, 0);
    TreeFileArtifact upstreamFile =
        TreeFileArtifact.createTemplateExpansionOutput(upstreamTree, "file", upstreamExpansion);
    upstreamFile.setGeneratingActionKey(ActionLookupData.create(upstreamExpansion, 0));
    Action upstreamAction = newAction(ImmutableList.of(upstreamFile), ImmutableList.of());

    // The regular action C (action 1 of the owner) consumes the whole upstream tree artifact.
    DerivedArtifact treeConsumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "tree_consumer.out");
    treeConsumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action treeConsumer =
        newAction(ImmutableList.of(treeConsumerOutput), ImmutableList.of(upstreamTree));

    // The downstream template (action 2 of the owner) is expanded over the upstream tree artifact.
    // Its expanded action D consumes the file generated by P as well as the output of C and
    // acquires their read locks in this order.
    SpecialArtifact downstreamTree =
        newTreeArtifact(root, "downstream", ActionLookupData.create(owner, 2));
    ActionTemplateExpansionKey downstreamExpansion = ActionTemplateExpansionValue.key(owner, 2);
    TreeFileArtifact downstreamFile =
        TreeFileArtifact.createTemplateExpansionOutput(downstreamTree, "file", downstreamExpansion);
    downstreamFile.setGeneratingActionKey(ActionLookupData.create(downstreamExpansion, 0));
    Action downstreamAction =
        newAction(
            ImmutableList.of(downstreamFile), ImmutableList.of(upstreamFile, treeConsumerOutput));

    InputMetadataProvider metadataProvider = mock(InputMetadataProvider.class);
    mockActions(
        owner,
        ImmutableList.of(mock(ActionTemplate.class), treeConsumer, mock(ActionTemplate.class)));
    mockTemplateExpansion(owner, /* actionIndex= */ 0, ImmutableList.of(upstreamAction));

    // C is rewound and prepares for its re-execution, which makes it hold the write lock guarding
    // its output until the end of its execution.
    SilentCloseable treeConsumerPreparation =
        synchronizer.enterActionPreparation(treeConsumer, /* wasRewound= */ true);
    // D enters execution, acquires the read lock guarding the upstream file and then blocks on the
    // read lock guarding the output of C.
    var downstreamExecution =
        new TestThread(
            () -> {
              try (SilentCloseable execution =
                  synchronizer.enterActionExecution(
                      downstreamAction, /* wasRewound= */ false, metadataProvider)) {}
            });
    downstreamExecution.start();
    waitUntilBlocked(downstreamExecution);
    // P is rewound and prepares for its re-execution, which blocks on the read lock held by D.
    var upstreamPreparation = new TestThread(() -> rewind(upstreamAction));
    upstreamPreparation.start();
    waitUntilBlocked(upstreamPreparation);
    // C enters execution, which requires the read lock guarding the upstream tree artifact. It must
    // not wait for P, which waits for D, which waits for C.
    var treeConsumerExecution =
        new TestThread(
            () -> {
              try (treeConsumerPreparation;
                  SilentCloseable execution =
                      synchronizer.enterActionExecution(
                          treeConsumer, /* wasRewound= */ true, metadataProvider)) {}
            });
    treeConsumerExecution.start();

    treeConsumerExecution.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    downstreamExecution.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    upstreamPreparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
  }

  /**
   * While a rewound action waits for a consumer of its outputs that started before the fine locks
   * were inflated, other consumers are admitted.
   */
  @Test
  public void rewoundActionAwaitingRegisteredConsumer_admitsOtherConsumers() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    DerivedArtifact file = (DerivedArtifact) ActionsTestUtil.createArtifact(root, "file");
    file.setGeneratingActionKey(ActionLookupData.create(owner, 0));
    Action producer = newAction(ImmutableList.of(file), ImmutableList.of());
    DerivedArtifact otherFile = (DerivedArtifact) ActionsTestUtil.createArtifact(root, "other");
    otherFile.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action otherProducer = newAction(ImmutableList.of(otherFile), ImmutableList.of());
    DerivedArtifact consumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "consumer.out");
    consumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 2));
    Action consumer = newAction(ImmutableList.of(consumerOutput), ImmutableList.of(file));
    DerivedArtifact otherConsumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "other_consumer.out");
    otherConsumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 3));
    Action otherConsumer =
        newAction(ImmutableList.of(otherConsumerOutput), ImmutableList.of(otherFile));
    mockActions(owner, ImmutableList.of(producer, otherProducer, consumer, otherConsumer));
    InputMetadataProvider metadataProvider = mock(InputMetadataProvider.class);

    var preparation = new TestThread(() -> rewind(producer));
    var otherExecution =
        new TestThread(
            () -> {
              try (SilentCloseable execution =
                  synchronizer.enterActionExecution(
                      otherConsumer, /* wasRewound= */ false, metadataProvider)) {}
            });
    try (SilentCloseable execution =
        synchronizer.enterActionExecution(consumer, /* wasRewound= */ false, metadataProvider)) {
      preparation.start();
      waitUntilBlocked(preparation);
      otherExecution.start();
      otherExecution.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    }
    preparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
  }

  /**
   * A rewound action reads the metadata provider of a consumer that registered before the fine
   * locks were inflated to find out whether it consumes the rewound action's outputs. Since the
   * consumer may mutate the provider once unregistered, unregistration waits for the rewound
   * action.
   */
  @Test
  public void unregistration_waitsForRewoundActionReadingConsumerMetadata() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    DerivedArtifact file = (DerivedArtifact) ActionsTestUtil.createArtifact(root, "file");
    file.setGeneratingActionKey(ActionLookupData.create(owner, 0));
    Action producer = newAction(ImmutableList.of(file), ImmutableList.of());
    DerivedArtifact unrelatedFile =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "unrelated");
    unrelatedFile.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action unrelatedProducer = newAction(ImmutableList.of(unrelatedFile), ImmutableList.of());
    SpecialArtifact runfiles = ActionsTestUtil.createRunfilesArtifact(root, "out/runfiles");
    runfiles.setGeneratingActionKey(ActionLookupData.create(owner, 2));
    Action runfilesAction = newAction(ImmutableList.of(runfiles), ImmutableList.of(file));
    DerivedArtifact consumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "consumer.out");
    consumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 3));
    Action consumer = newAction(ImmutableList.of(consumerOutput), ImmutableList.of(runfiles));
    mockActions(owner, ImmutableList.of(producer, unrelatedProducer, runfilesAction, consumer));
    var runfilesValue =
        new RunfilesArtifactValue(
            mock(RunfilesTree.class),
            ImmutableList.of(file),
            ImmutableList.of(FileArtifactValue.createForNormalFile(new byte[32], null, 0)),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of());
    var metadataRead = new CountDownLatch(1);
    var metadataReleased = new CountDownLatch(1);
    InputMetadataProvider metadataProvider = mock(InputMetadataProvider.class);
    when(metadataProvider.getRunfilesMetadata(runfiles))
        .thenAnswer(
            invocation -> {
              metadataRead.countDown();
              metadataReleased.await();
              return runfilesValue;
            });

    SilentCloseable execution =
        synchronizer.enterActionExecution(consumer, /* wasRewound= */ false, metadataProvider);
    var unrelatedPreparation = new TestThread(() -> rewind(unrelatedProducer));
    unrelatedPreparation.start();
    var unregistration = new TestThread(execution::close);
    try {
      assertThat(metadataRead.await(DEADLOCK_TIMEOUT_MILLIS, MILLISECONDS)).isTrue();
      unregistration.start();
      Thread.State state;
      while ((state = unregistration.getState()) != Thread.State.BLOCKED) {
        assertThat(state).isNotEqualTo(Thread.State.TERMINATED);
        Thread.onSpinWait();
      }
    } finally {
      metadataReleased.countDown();
    }

    unregistration.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    unrelatedPreparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
  }

  @Test
  public void enterActionExecution_interrupted_throws(@TestParameter boolean inflateFineLocksFirst)
      throws Exception {
    maybeInflateFineLocks(inflateFineLocksFirst);

    Thread.currentThread().interrupt();

    assertThrows(
        InterruptedException.class,
        () ->
            synchronizer.enterActionExecution(
                newAction(), /* wasRewound= */ false, mock(InputMetadataProvider.class)));
  }

  @Test
  public void rewoundActionInterruptedWhileAwaitingRegisteredConsumer_canBeRewoundAgain()
      throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    DerivedArtifact file = (DerivedArtifact) ActionsTestUtil.createArtifact(root, "file");
    file.setGeneratingActionKey(ActionLookupData.create(owner, 0));
    Action producer = newAction(ImmutableList.of(file), ImmutableList.of());
    DerivedArtifact consumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "consumer.out");
    consumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action consumer = newAction(ImmutableList.of(consumerOutput), ImmutableList.of(file));
    mockActions(owner, ImmutableList.of(producer, consumer));

    var preparation =
        new TestThread(() -> assertThrows(InterruptedException.class, () -> rewind(producer)));
    try (SilentCloseable execution =
        synchronizer.enterActionExecution(
            consumer, /* wasRewound= */ false, mock(InputMetadataProvider.class))) {
      preparation.start();
      waitUntilBlocked(preparation);
      preparation.interrupt();
      preparation.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
    }

    rewind(producer);
  }

  /**
   * A consumer that races with the first rewound action either finds the fine locks inflated or is
   * found registered by the rewound action, so it never executes while the rewound action holds its
   * write lock. The race is only hit with some probability per iteration.
   */
  @Test
  public void consumersRacingWithFirstRewinding_neverOverlapRewoundAction() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot root = ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    var owner = ActionsTestUtil.NULL_ARTIFACT_OWNER;
    DerivedArtifact file = (DerivedArtifact) ActionsTestUtil.createArtifact(root, "file");
    file.setGeneratingActionKey(ActionLookupData.create(owner, 0));
    Action producer = newAction(ImmutableList.of(file), ImmutableList.of());
    DerivedArtifact consumerOutput =
        (DerivedArtifact) ActionsTestUtil.createArtifact(root, "consumer.out");
    consumerOutput.setGeneratingActionKey(ActionLookupData.create(owner, 1));
    Action consumer = newAction(ImmutableList.of(consumerOutput), ImmutableList.of(file));
    mockActions(owner, ImmutableList.of(producer, consumer));
    InputMetadataProvider metadataProvider = mock(InputMetadataProvider.class);

    Thread rewindingThread = Thread.currentThread();
    for (int i = 0; i < 3000; i++) {
      var synchronizer = new RemoteRewoundActionSynchronizer(actionInputFetcher, graph);
      var rewinding = new AtomicBoolean();
      var start = new CountDownLatch(1);
      var consumerExecutions = new ArrayList<TestThread>();
      for (int j = 0; j < 4; j++) {
        var consumerExecution =
            new TestThread(
                () -> {
                  start.await();
                  try (SilentCloseable execution =
                      synchronizer.enterActionExecution(
                          consumer, /* wasRewound= */ false, metadataProvider)) {
                    // Stay until the rewound action either waits, possibly for this consumer, or
                    // holds its write lock.
                    while (!rewinding.get() && !isBlocked(rewindingThread)) {
                      Thread.onSpinWait();
                    }
                    assertThat(rewinding.get()).isFalse();
                  }
                });
        consumerExecution.start();
        consumerExecutions.add(consumerExecution);
      }

      start.countDown();
      try (SilentCloseable preparation =
          synchronizer.enterActionPreparation(producer, /* wasRewound= */ true)) {
        rewinding.set(true);
        // Keep the write lock until every consumer has either finished or blocked on it, so that a
        // consumer that wrongly executes concurrently observes the flag.
        for (var consumerExecution : consumerExecutions) {
          while (consumerExecution.isAlive() && !isBlocked(consumerExecution)) {
            Thread.onSpinWait();
          }
        }
        rewinding.set(false);
      }
      for (var consumerExecution : consumerExecutions) {
        consumerExecution.joinAndAssertState(DEADLOCK_TIMEOUT_MILLIS);
      }
    }
  }

  /**
   * Makes the given owner's only action an {@link ActionTemplate} whose expansion consists of the
   * given actions.
   */
  private void mockTemplateExpansion(ActionLookupKey owner, ImmutableList<Action> expandedActions)
      throws InterruptedException {
    mockActions(owner, ImmutableList.of(mock(ActionTemplate.class)));
    mockTemplateExpansion(owner, /* actionIndex= */ 0, expandedActions);
  }

  /** Makes the given actions the expansion of the given owner's template at the given index. */
  private void mockTemplateExpansion(
      ActionLookupKey owner, int actionIndex, ImmutableList<Action> expandedActions)
      throws InterruptedException {
    when(graph.getValue(ActionTemplateExpansionValue.key(owner, actionIndex)))
        .thenReturn(new ActionTemplateExpansionValue(ImmutableList.copyOf(expandedActions)));
  }

  /** Makes the given actions, in order, the actions of the given owner. */
  private void mockActions(
      ActionLookupKey owner, ImmutableList<? extends ActionAnalysisMetadata> actions)
      throws InterruptedException {
    ActionLookupValue ownerValue = mock(ActionLookupValue.class);
    when(ownerValue.getActions()).thenReturn(ImmutableList.<ActionAnalysisMetadata>copyOf(actions));
    when(graph.getValue(owner)).thenReturn(ownerValue);
  }

  private static boolean isBlocked(Thread thread) {
    return switch (thread.getState()) {
      case BLOCKED, WAITING, TIMED_WAITING -> true;
      case NEW, RUNNABLE, TERMINATED -> false;
    };
  }

  @SuppressWarnings("ThreadPriorityCheck")
  private static void waitUntilBlocked(Thread thread) {
    Thread.State state;
    while ((state = thread.getState()) != Thread.State.WAITING) {
      assertThat(state).isNotEqualTo(Thread.State.TERMINATED);
      Thread.yield();
    }
  }

  /**
   * Inflates the fine locks by rewinding an unrelated action if requested. Otherwise, consumers
   * entered before the first rewinding only register themselves.
   */
  private void maybeInflateFineLocks(boolean inflate) throws Exception {
    if (inflate) {
      rewind(newAction());
    }
  }

  private void rewind(Action action) throws Exception {
    try (SilentCloseable ignored = synchronizer.enterActionPreparation(action, true)) {
      // Cancellation happens while entering preparation.
    }
  }

  private static Action newAction() {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    ArtifactRoot outputRoot =
        ArtifactRoot.asDerivedRoot(fs.getPath("/exec"), RootType.OUTPUT, "out");
    DerivedArtifact output = (DerivedArtifact) ActionsTestUtil.createArtifact(outputRoot, "output");
    output.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA);
    return newAction(ImmutableList.of(output), ImmutableList.of());
  }

  private static Action newAction(
      ImmutableList<? extends Artifact> outputs, ImmutableList<? extends Artifact> inputs) {
    Action action = mock(Action.class);
    when(action.getPrimaryOutput()).thenReturn(outputs.get(0));
    when(action.getOutputs()).thenReturn(ImmutableList.copyOf(outputs));
    when(action.getInputs()).thenReturn(NestedSetBuilder.wrap(Order.STABLE_ORDER, inputs));
    return action;
  }

  private static SpecialArtifact newTreeArtifact(
      ArtifactRoot root, String name, ActionLookupData generatingActionKey) {
    SpecialArtifact tree =
        SpecialArtifact.create(
            root,
            root.getExecPath().getRelative(name),
            generatingActionKey.getActionLookupKey(),
            SpecialArtifactType.TREE);
    tree.setGeneratingActionKey(generatingActionKey);
    return tree;
  }

  private static final class EqualCancellable
      implements RemoteRewoundActionSynchronizer.Cancellable {
    private final AtomicInteger cancellations = new AtomicInteger();

    @Override
    public void requestCancellation() {
      cancellations.incrementAndGet();
    }

    @Override
    public void awaitCompletion() {}

    @Override
    public boolean equals(Object obj) {
      return obj instanceof EqualCancellable;
    }

    @Override
    public int hashCode() {
      return 1;
    }
  }
}
