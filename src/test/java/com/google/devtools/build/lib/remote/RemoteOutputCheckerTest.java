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
import com.google.devtools.build.lib.actions.Artifact.TreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.remote.options.RemoteOutputsMode;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.RegexPatternOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteOutputChecker} */
@RunWith(JUnit4.class)
public class RemoteOutputCheckerTest {
  private static final FileArtifactValue REMOTE_METADATA =
      FileArtifactValue.createForRemoteFile(
          new byte[] {1, 2, 3}, /* size= */ 3, /* locationIndex= */ 1);
  private static final FileArtifactValue LOCAL_METADATA =
      FileArtifactValue.createForNormalFile(new byte[] {1, 2, 3}, /* proxy= */ null, /* size= */ 3);

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

  @Test
  public void shouldDownloadOutput_treeMatchesRegex_downloadsChildren() throws Exception {
    var checker = createCheckerWithRegex(".*/foo/tree");

    assertThat(
            checker.shouldDownloadOutput(
                PathFragment.create("out/foo/tree/dir/a.txt"), PathFragment.create("out/foo/tree")))
        .isTrue();
    assertThat(
            checker.shouldDownloadOutput(
                PathFragment.create("out/foo/other/dir/a.txt"),
                PathFragment.create("out/foo/other")))
        .isFalse();
  }

  @Test
  public void getTreeChildrenToDownload_childrenMatchRegex_returnsMatchingRemoteChildren()
      throws Exception {
    var checker = createCheckerWithRegex(".*\\.txt");
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var remoteMatching = TreeFileArtifact.createTreeOutput(tree, "dir/a.txt");
    var remoteNotMatching = TreeFileArtifact.createTreeOutput(tree, "dir/b.bin");
    var localMatching = TreeFileArtifact.createTreeOutput(tree, "c.txt");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(remoteMatching, REMOTE_METADATA)
            .putChild(remoteNotMatching, REMOTE_METADATA)
            .putChild(localMatching, LOCAL_METADATA)
            .build();

    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(remoteMatching);
    // Served from the cache.
    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(remoteMatching);
    assertConsistentWithShouldDownloadOutput(checker, tree, treeValue);
  }

  @Test
  public void getTreeChildrenToDownload_treeMatchesRegex_returnsAllRemoteChildren()
      throws Exception {
    var checker = createCheckerWithRegex(".*/foo/tree");
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var remote1 = TreeFileArtifact.createTreeOutput(tree, "dir/a.txt");
    var remote2 = TreeFileArtifact.createTreeOutput(tree, "dir/b.bin");
    var local = TreeFileArtifact.createTreeOutput(tree, "c.txt");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(remote1, REMOTE_METADATA)
            .putChild(remote2, REMOTE_METADATA)
            .putChild(local, LOCAL_METADATA)
            .build();

    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(remote1, remote2);
    assertConsistentWithShouldDownloadOutput(checker, tree, treeValue);
  }

  @Test
  public void getTreeChildrenToDownload_noRegex_returnsNoChildren() {
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "a.txt"), REMOTE_METADATA)
            .build();

    assertThat(remoteOutputChecker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .isEmpty();
    assertConsistentWithShouldDownloadOutput(remoteOutputChecker, tree, treeValue);
  }

  @Test
  public void getTreeChildrenToDownload_downloadAll_returnsAllRemoteChildren() {
    var checker = new RemoteOutputChecker("build", RemoteOutputsMode.ALL, ImmutableList.of());
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var remote = TreeFileArtifact.createTreeOutput(tree, "a.txt");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(remote, REMOTE_METADATA)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "b.txt"), LOCAL_METADATA)
            .build();

    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(remote);
    assertConsistentWithShouldDownloadOutput(checker, tree, treeValue);
  }

  @Test
  public void getTreeChildrenToDownload_pathsRequestedOrSkippedLater_honored() throws Exception {
    var checker = createCheckerWithRegex(".*\\.txt");
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var matching = TreeFileArtifact.createTreeOutput(tree, "a.txt");
    var notMatching = TreeFileArtifact.createTreeOutput(tree, "b.bin");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(matching, REMOTE_METADATA)
            .putChild(notMatching, REMOTE_METADATA)
            .build();

    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(matching);

    // Only the regex matches are cached, other reasons to download or skip a child must still be
    // taken into account.
    checker.addOutputToDownload(tree);
    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(matching, notMatching);
    assertConsistentWithShouldDownloadOutput(checker, tree, treeValue);

    checker.skipDownload(matching.getExecPath());
    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactly(notMatching);
    assertConsistentWithShouldDownloadOutput(checker, tree, treeValue);
  }

  @Test
  public void shouldDownloadOutput_matchesTreeRootOnlyOnce() throws Exception {
    List<String> matchedPaths = new ArrayList<>();
    var checker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(recordingRegex(".*\\.txt", matchedPaths)));

    assertThat(
            checker.shouldDownloadOutput(
                PathFragment.create("out/foo/tree/a.bin"), PathFragment.create("out/foo/tree")))
        .isFalse();
    assertThat(
            checker.shouldDownloadOutput(
                PathFragment.create("out/foo/tree/b.bin"), PathFragment.create("out/foo/tree")))
        .isFalse();

    assertThat(matchedPaths)
        .containsExactly("out/foo/tree", "out/foo/tree/a.bin", "out/foo/tree/b.bin");
  }

  @Test
  public void getTreeChildrenToDownload_matchesEachPathOnlyOnce() throws Exception {
    List<String> matchedPaths = new ArrayList<>();
    var checker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(recordingRegex(".*\\.txt", matchedPaths)));
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var matching = TreeFileArtifact.createTreeOutput(tree, "a.txt");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(matching, REMOTE_METADATA)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "b.bin"), REMOTE_METADATA)
            .build();

    for (int i = 0; i < 3; i++) {
      assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
          .containsExactly(matching);
    }

    assertThat(matchedPaths)
        .containsExactly("out/foo/tree", "out/foo/tree/a.txt", "out/foo/tree/b.bin");
  }

  @Test
  public void clearCaches_matchesPathsAgain() throws Exception {
    List<String> matchedPaths = new ArrayList<>();
    var checker =
        new RemoteOutputChecker(
            "build",
            RemoteOutputsMode.MINIMAL,
            ImmutableList.of(recordingRegex(".*\\.txt", matchedPaths)));
    var tree = ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/tree");
    var treeValue =
        TreeArtifactValue.newBuilder(tree)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "a.bin"), REMOTE_METADATA)
            .build();

    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue)).isEmpty();
    checker.clearCaches();
    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue)).isEmpty();

    assertThat(matchedPaths)
        .containsExactly(
            "out/foo/tree", "out/foo/tree/a.bin", "out/foo/tree", "out/foo/tree/a.bin");
  }

  /** Returns a regex option that records every path it is matched against. */
  private static RegexPatternOption recordingRegex(String regex, List<String> matchedPaths)
      throws Exception {
    RegexPatternOption delegate = new Converters.RegexPatternConverter().convert(regex);
    return new RegexPatternOption() {
      @Override
      public Pattern regexPattern() {
        return delegate.regexPattern();
      }

      @Override
      public Predicate<String> matcher() {
        return path -> {
          matchedPaths.add(path);
          return delegate.matcher().test(path);
        };
      }
    };
  }

  private static RemoteOutputChecker createCheckerWithRegex(String regex) throws Exception {
    return new RemoteOutputChecker(
        "build",
        RemoteOutputsMode.MINIMAL,
        ImmutableList.of(new Converters.RegexPatternConverter().convert(regex)));
  }

  private static void assertConsistentWithShouldDownloadOutput(
      RemoteOutputChecker checker, TreeArtifact tree, TreeArtifactValue treeValue) {
    var expected =
        treeValue.getChildValues().entrySet().stream()
            .filter(e -> checker.shouldDownloadOutput(e.getKey(), e.getValue()))
            .map(Map.Entry::getKey)
            .collect(ImmutableList.toImmutableList());
    assertThat(checker.getTreeChildrenToDownload(tree.getExecPath(), treeValue))
        .containsExactlyElementsIn(expected)
        .inOrder();
  }
}
