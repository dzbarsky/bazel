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
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.FileNode;
import build.bazel.remote.execution.v2.Tree;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.vfs.DetailedIOException;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.SkyKey;
import java.time.Duration;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public final class RemoteExternalOverlayFileSystemTest {
  @Test
  @SuppressWarnings("unchecked")
  public void afterLostRepoFile_invalidatesRepositoryOutsideOverlay() throws Exception {
    var digestUtil = new DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256);
    var cache = new InMemoryCombinedCache(digestUtil);
    var nativeFs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    var externalRoot = PathFragment.create("/output/external");
    var overlay = new RemoteExternalOverlayFileSystem(externalRoot, nativeFs);
    var evaluator = mock(MemoizingEvaluator.class);
    var prefetcher = mock(AbstractActionInputPrefetcher.class);
    when(prefetcher.prefetchFilesInterruptibly(isNull(), any(), any(), any(), any()))
        .thenReturn(immediateVoidFuture());
    overlay.beforeCommand(
        cache,
        prefetcher,
        new Reporter(new EventBus()),
        "build-request",
        "command",
        evaluator,
        Duration.ofMinutes(1));

    var digest = digestUtil.compute("evicted contents".getBytes(UTF_8));
    var tree =
        Tree.newBuilder()
            .setRoot(
                Directory.newBuilder()
                    .addFiles(FileNode.newBuilder().setName("data.txt").setDigest(digest)))
            .build();
    var trackedRepo = RepositoryName.create("tracked");
    assertThat(overlay.injectRemoteRepo(trackedRepo, tree, "marker")).isTrue();

    assertThrows(
        DetailedIOException.class,
        () ->
            FileSystemUtils.readContent(
                overlay.getPath(externalRoot.getRelative("tracked/data.txt"))));
    overlay.afterCommand();

    ArgumentCaptor<Predicate<SkyKey>> deletion = ArgumentCaptor.forClass(Predicate.class);
    verify(evaluator).delete(deletion.capture());
    assertThat(deletion.getValue().test(RepositoryDirectoryValue.key(trackedRepo))).isTrue();
    assertThat(
            deletion
                .getValue()
                .test(RepositoryDirectoryValue.key(RepositoryName.create("untracked"))))
        .isTrue();
    assertThat(overlay.shouldValidateRepoContents()).isTrue();
  }
}
