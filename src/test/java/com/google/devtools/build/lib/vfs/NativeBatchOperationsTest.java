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
package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NativeBatchOperationsTest {
  private static Map<PathFragment, PathFragment> links() {
    Map<PathFragment, PathFragment> links = new LinkedHashMap<>();
    links.put(PathFragment.create("first"), PathFragment.create("/target1"));
    links.put(PathFragment.create("second"), PathFragment.create("/target2"));
    return links;
  }

  private static final class PartialFileSystem extends InMemoryFileSystem {
    final List<String> readbacks = new ArrayList<>();
    boolean ambiguousFailure;

    PartialFileSystem() {
      super(DigestHashFunction.SHA256);
    }

    @Override
    public int[] createSymlinksBatch(
        PathFragment root, List<PathFragment> paths, List<PathFragment> targets)
        throws IOException {
      getPath(root.getRelative(paths.get(0))).createSymbolicLink(targets.get(0));
      if (ambiguousFailure) {
        throw new IOException("completion unknown");
      }
      return new int[] {0, 17};
    }

    @Override
    public PathFragment readSymbolicLink(PathFragment path) throws IOException {
      readbacks.add(path.getBaseName());
      return super.readSymbolicLink(path);
    }
  }

  @Test
  public void reconcilesOnlyFailedSymlinks() throws Exception {
    PartialFileSystem fs = new PartialFileSystem();
    Path root = fs.getPath("/tree");
    root.createDirectory();
    root.getChild("second").createSymbolicLink(PathFragment.create("/old"));
    new NativeBatchOperations(root.getFileSystem(), 0, 0).createSymlinks(root, links());
    assertThat(fs.readbacks).containsExactly("second");
    assertThat(root.getChild("first").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target1"));
    assertThat(root.getChild("second").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target2"));
  }

  @Test
  public void ambiguousFailureDoesNotReplayOrContinue() throws Exception {
    PartialFileSystem fs = new PartialFileSystem();
    fs.ambiguousFailure = true;
    Path root = fs.getPath("/tree");
    root.createDirectory();
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                new NativeBatchOperations(root.getFileSystem(), 0, 0)
                    .createSymlinks(root, links()));
    assertThat(error).hasMessageThat().contains("completion unknown");
    assertThat(fs.readbacks).isEmpty();
    assertThat(root.getChild("first").isSymbolicLink()).isTrue();
    assertThat(root.getChild("second").exists(Symlinks.NOFOLLOW)).isFalse();
  }

  @Test
  public void unsupportedFilesystemCreatesLinksLocally() throws Exception {
    Path root = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/tree");
    root.createDirectory();
    new NativeBatchOperations(root.getFileSystem(), 0, 0).createSymlinks(root, links());
    assertThat(root.getChild("first").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target1"));
    assertThat(root.getChild("second").readSymbolicLink())
        .isEqualTo(PathFragment.create("/target2"));
  }

  @Test
  public void directoryFailurePreservesPrefixAcrossChunks() throws Exception {
    List<Integer> batches = new ArrayList<>();
    FileSystem fs =
        new InMemoryFileSystem(DigestHashFunction.SHA256) {
          @Override
          public int createDirectoriesBatch(PathFragment root, List<PathFragment> paths)
              throws IOException {
            batches.add(paths.size());
            int count = 0;
            for (PathFragment path : paths) {
              if (!getPath(root.getRelative(path)).createDirectory()) {
                break;
              }
              count++;
            }
            return count;
          }
        };
    Path root = fs.getPath("/tree");
    root.createDirectory();
    root.getChild("d4096").createDirectory();
    List<PathFragment> paths =
        IntStream.range(0, 4098).mapToObj(i -> PathFragment.create("d" + i)).toList();
    assertThat(new NativeBatchOperations(root.getFileSystem(), 0, 0).createDirectories(root, paths))
        .isEqualTo(4096);
    assertThat(batches).containsExactly(4096, 2).inOrder();
    assertThat(root.getChild("d4095").isDirectory()).isTrue();
    assertThat(root.getChild("d4097").exists()).isFalse();
  }
}
