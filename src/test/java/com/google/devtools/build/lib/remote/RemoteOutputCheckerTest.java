// Copyright 2025 The Bazel Authors. All rights reserved.
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.common.options.Converters;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteOutputChecker} */
@RunWith(JUnit4.class)
public class RemoteOutputCheckerTest {
  private final RemoteOutputChecker remoteOutputChecker =
      new RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, ImmutableList.of());
  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final ArtifactRoot execRoot =
      ArtifactRoot.asDerivedRoot(fs.getPath("/execroot"), ArtifactRoot.RootType.OUTPUT, "out");

  @Test
  public void testShouldDownloadOutput() {
    remoteOutputChecker.addOutputToDownload(
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/bar"));
    remoteOutputChecker.addOutputToDownload(
        ActionsTestUtil.createArtifact(execRoot, "foo/bar-baz"));
    assertThat(
            remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar-quz"), null))
        .isFalse();
    assertThat(remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar"), null))
        .isTrue();
    assertThat(
            remoteOutputChecker.shouldDownloadOutput(
                PathFragment.create("out/foo/bar/data.txt"), PathFragment.create("out/foo/bar")))
        .isTrue();
    assertThat(
            remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar-baz"), null))
        .isTrue();
  }

  @Test
  public void cachedTestOutputs_reusedAfterRestart_newExecutionStillDownloads() {
    Artifact log = ActionsTestUtil.createArtifact(execRoot, "testlogs/pkg/t/test.log");
    Artifact tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(
        execRoot, "testlogs/pkg/t/test.outputs");
    var child = tree.getExecPath().getRelative("junit.xml");
    var metadata = FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 10, 1);
    var previous = new RemoteOutputChecker("test", RemoteOutputsMode.TOPLEVEL, ImmutableList.of());
    previous.addTestOutput(log);
    previous.addTestOutput(tree);
    var current =
        new RemoteOutputChecker("test", RemoteOutputsMode.TOPLEVEL, ImmutableList.of(), previous);
    current.addTestOutput(log);
    current.addTestOutput(tree);

    assertThat(current.shouldDownloadOutput(log, metadata)).isFalse();
    assertThat(current.shouldDownloadOutput(child, tree.getExecPath())).isFalse();
    assertThat(current.shouldTrustMetadata(log, metadata)).isTrue();
    assertThat(current.shouldTrustCachedMetadata(log, metadata)).isTrue();
    assertThat(current.shouldDownloadTestOutputAfterExecution(log.getExecPath())).isTrue();
    assertThat(current.shouldDownloadTestOutputAfterExecution(child)).isTrue();
  }

  @Test
  public void cachedTestOutputs_explicitlyRequestedAndCoverageAreMaterialized() throws Exception {
    Artifact log = ActionsTestUtil.createArtifact(execRoot, "testlogs/pkg/t/test.log");
    Artifact xml = ActionsTestUtil.createArtifact(execRoot, "testlogs/pkg/t/test.xml");
    Artifact coverage = ActionsTestUtil.createArtifact(execRoot, "testlogs/pkg/t/coverage.dat");
    var metadata = FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 10, 1);
    var previous = new RemoteOutputChecker("test", RemoteOutputsMode.TOPLEVEL, ImmutableList.of());
    previous.addTestOutput(log);
    previous.addTestOutput(xml);
    var verbose =
        new RemoteOutputChecker(
            "test",
            RemoteOutputsMode.TOPLEVEL,
            ImmutableList.of(),
            previous,
            /* downloadCachedTestLogs= */ true,
            /* downloadCachedTestXml= */ false);
    // Before Skymeld registers the current targets, the previous command identifies the test.
    assertThat(verbose.shouldTrustMetadata(log, metadata)).isFalse();
    assertThat(verbose.shouldTrustMetadata(xml, metadata)).isTrue();
    verbose.addTestOutput(log);
    verbose.addTestOutput(xml);
    assertThat(verbose.shouldTrustCachedMetadata(log, metadata)).isFalse();
    assertThat(verbose.shouldDownloadOutput(xml, metadata)).isFalse();

    var regex = new RemoteOutputChecker(
        "coverage", RemoteOutputsMode.TOPLEVEL,
        ImmutableList.of(new Converters.RegexPatternConverter().convert(".*/test[.]xml")),
        previous);
    regex.addTestOutput(log);
    regex.addTestOutput(xml);
    regex.addTestOutput(coverage);
    regex.addOutputToDownload(coverage);
    assertThat(regex.shouldTrustCachedMetadata(xml, metadata)).isFalse();
    assertThat(regex.shouldTrustCachedMetadata(log, metadata)).isTrue();
    assertThat(regex.shouldDownloadOutput(coverage, metadata)).isTrue();

    var all = new RemoteOutputChecker("test", RemoteOutputsMode.ALL, ImmutableList.of());
    assertThat(all.shouldDownloadOutput(log, metadata)).isTrue();
    assertThat(all.shouldDownloadOutput(xml, metadata)).isTrue();
  }

  @Test
  public void cachedTestOutputs_detailedSummaryDoesNotDownloadLogsOrUndeclaredOutputs() {
    Artifact log = ActionsTestUtil.createArtifact(execRoot, "testlogs/pkg/t/test.log");
    Artifact xml = ActionsTestUtil.createArtifact(execRoot, "testlogs/pkg/t/test.xml");
    var tree =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(
            execRoot, "testlogs/pkg/t/test.outputs");
    var child = Artifact.TreeFileArtifact.createTreeOutput(tree, "test.xml");
    var metadata = FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 10, 1);
    var previous = new RemoteOutputChecker("test", RemoteOutputsMode.MINIMAL, ImmutableList.of());
    for (Artifact output : ImmutableList.of(log, xml, tree)) {
      previous.addTestOutput(output);
    }
    var detailed =
        new RemoteOutputChecker(
            "test",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(),
            previous,
            /* downloadCachedTestLogs= */ false,
            /* downloadCachedTestXml= */ true);

    assertThat(detailed.shouldTrustMetadata(xml, metadata)).isFalse();
    assertThat(detailed.shouldTrustMetadata(log, metadata)).isTrue();
    assertThat(detailed.shouldTrustMetadata(child, metadata)).isTrue();
    for (Artifact output : ImmutableList.of(log, xml, tree)) {
      detailed.addTestOutput(output);
    }
    assertThat(detailed.shouldDownloadOutput(xml, metadata)).isTrue();
    assertThat(detailed.shouldDownloadOutput(log, metadata)).isFalse();
    assertThat(detailed.shouldDownloadOutput(child, metadata)).isFalse();
    assertThat(detailed.shouldTrustCachedMetadata(child, metadata)).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void cachedTestOutputs_changingRequestedReportsInvalidatesCompletions() {
    var previous = new RemoteOutputChecker("test", RemoteOutputsMode.TOPLEVEL, ImmutableList.of());
    var verbose =
        new RemoteOutputChecker(
            "test",
            RemoteOutputsMode.TOPLEVEL,
            ImmutableList.of(),
            previous,
            /* downloadCachedTestLogs= */ true,
            /* downloadCachedTestXml= */ false);
    var detailed =
        new RemoteOutputChecker(
            "test",
            RemoteOutputsMode.TOPLEVEL,
            ImmutableList.of(),
            verbose,
            /* downloadCachedTestLogs= */ false,
            /* downloadCachedTestXml= */ true);

    for (var checker : ImmutableList.of(verbose, detailed)) {
      MemoizingEvaluator evaluator = mock(MemoizingEvaluator.class);
      checker.maybeInvalidateSkyframeValues(evaluator);
      verify(evaluator).delete(any(Predicate.class));
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void maybeInvalidateSkyframeValues_downloadRegexChanged_invalidatesCompletions()
      throws Exception {
    var previousBuildChecker =
        new RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, ImmutableList.of());
    var checker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(new Converters.RegexPatternConverter().convert(".*\\.txt")),
            previousBuildChecker);
    MemoizingEvaluator evaluator = mock(MemoizingEvaluator.class);

    checker.maybeInvalidateSkyframeValues(evaluator);

    verify(evaluator).delete(any(Predicate.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void maybeInvalidateSkyframeValues_sameDownloadRegex_keepsCompletions() throws Exception {
    var previousBuildChecker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(new Converters.RegexPatternConverter().convert(".*\\.txt")));
    var checker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(new Converters.RegexPatternConverter().convert(".*\\.txt")),
            previousBuildChecker);
    MemoizingEvaluator evaluator = mock(MemoizingEvaluator.class);

    checker.maybeInvalidateSkyframeValues(evaluator);

    verify(evaluator, never()).delete(any(Predicate.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void maybeInvalidateSkyframeValues_nonBuildCommandBetweenBuilds_keepsCompletions() {
    var previousBuildChecker =
        new RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, ImmutableList.of());
    var infoChecker =
        new RemoteOutputChecker(
            "info", RemoteOutputsMode.MINIMAL, ImmutableList.of(), previousBuildChecker);
    var nextBuildChecker =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.MINIMAL, ImmutableList.of(), infoChecker);
    MemoizingEvaluator evaluator = mock(MemoizingEvaluator.class);

    infoChecker.maybeInvalidateSkyframeValues(evaluator);
    nextBuildChecker.maybeInvalidateSkyframeValues(evaluator);

    verify(evaluator, never()).delete(any(Predicate.class));
  }

  @Test
  public void shouldTrustMetadata_previousBuildDownloadedAll_trusted() {
    // Outputs that the current build does not want downloaded must not be distrusted
    // due to a previous invocation using a broader download policy.
    Artifact artifact = ActionsTestUtil.createArtifact(execRoot, "foo/bar");
    FileArtifactValue metadata =
        FileArtifactValue.createForRemoteFile(
            new byte[] {1, 2, 3}, /* size= */ 3, /* locationIndex= */ 1);

    var previousBuildChecker =
        new RemoteOutputChecker("build", RemoteOutputsMode.ALL, ImmutableList.of());
    var currentBuildChecker =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of(), previousBuildChecker);

    assertThat(currentBuildChecker.shouldTrustMetadata(artifact, metadata)).isTrue();
  }

  @Test
  public void shouldTrustMetadata_previousBuildRegexMatch_trusted() throws Exception {
    Artifact artifact = ActionsTestUtil.createArtifact(execRoot, "foo/bar");
    FileArtifactValue metadata =
        FileArtifactValue.createForRemoteFile(
            new byte[] {1, 2, 3}, /* size= */ 3, /* locationIndex= */ 1);

    var previousBuildChecker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.TOPLEVEL,
            ImmutableList.of(new Converters.RegexPatternConverter().convert(".*")));
    var currentBuildChecker =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of(), previousBuildChecker);

    assertThat(currentBuildChecker.shouldTrustMetadata(artifact, metadata)).isTrue();
  }

  @Test
  public void shouldTrustMetadata_toplevelOutputOfPreviousBuild_distrusted() {
    // A toplevel output downloaded by the previous build must be distrusted if missing locally, so
    // that the generating action reruns to restore it. Under Skymeld, the current build's toplevel
    // outputs are not yet registered when this check runs.
    Artifact artifact = ActionsTestUtil.createArtifact(execRoot, "foo/bar");
    FileArtifactValue metadata =
        FileArtifactValue.createForRemoteFile(
            new byte[] {1, 2, 3}, /* size= */ 3, /* locationIndex= */ 1);

    var previousBuildChecker =
        new RemoteOutputChecker("build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of());
    previousBuildChecker.addOutputToDownload(artifact);
    var currentBuildChecker =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of(), previousBuildChecker);

    assertThat(currentBuildChecker.shouldTrustMetadata(artifact, metadata)).isFalse();
  }

  @Test
  public void shouldTrustMetadata_downloadedByCurrentBuild_distrusted() {
    Artifact artifact = ActionsTestUtil.createArtifact(execRoot, "foo/bar");
    FileArtifactValue metadata =
        FileArtifactValue.createForRemoteFile(
            new byte[] {1, 2, 3}, /* size= */ 3, /* locationIndex= */ 1);

    var previousBuildChecker =
        new RemoteOutputChecker("build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of());
    var currentBuildChecker =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.TOPLEVEL, ImmutableList.of(), previousBuildChecker);
    currentBuildChecker.addOutputToDownload(artifact);

    assertThat(currentBuildChecker.shouldTrustMetadata(artifact, metadata)).isFalse();
  }

  @Test
  public void testShouldTrustCachedMetadata_ignoresLastRemoteOutputChecker() {
    RemoteOutputChecker lastChecker =
        new RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, ImmutableList.of());
    lastChecker.addOutputToDownload(ActionsTestUtil.createArtifact(execRoot, "foo/bar-baz"));

    RemoteOutputChecker checker =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.MINIMAL, ImmutableList.of(), lastChecker);

    FileArtifactValue remoteMetadata =
        FileArtifactValue.createForRemoteFileWithMaterializationData(
            new byte[] {1, 2, 3}, 10, 1, /* expirationTime= */ null, /* inMemoryOutput= */ false);

    assertThat(
            checker.shouldTrustCachedMetadata(
                ActionsTestUtil.createArtifact(execRoot, "foo/bar-baz"), remoteMetadata))
        .isTrue();
    assertThat(
            checker.shouldTrustMetadata(
                ActionsTestUtil.createArtifact(execRoot, "foo/bar-baz"), remoteMetadata))
        .isFalse();
  }

  @Test
  public void probeMetadataIsReusableUntilOrdinaryBuildRequestsMaterialization() {
    var output = ActionsTestUtil.createArtifact(execRoot, "image.tar");
    var metadata = FileArtifactValue.createForRemoteFile(new byte[] {1, 2, 3}, 100, 1);
    var nextProbe =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.MINIMAL, ImmutableList.of(), remoteOutputChecker);
    var nextBuild =
        new RemoteOutputChecker(
            "build", RemoteOutputsMode.ALL, ImmutableList.of(), remoteOutputChecker);

    assertThat(nextProbe.shouldTrustMetadata(output, metadata)).isTrue();
    assertThat(nextBuild.shouldTrustMetadata(output, metadata)).isFalse();
  }
}
