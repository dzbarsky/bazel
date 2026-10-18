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

import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.vfs.OutputService.BatchOperations;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Optional filesystem-native creation; ordinary operations handle unsupported filesystems. */
public final class NativeBatchOperations implements BatchOperations {
  private static final int MAX_ENTRIES = 4096;
  private static final int MAX_BYTES = 1024 * 1024;
  private final FileSystem localFileSystem;
  private final int symlinkMinEntries;
  private final int mkdirMinEntries;

  public NativeBatchOperations(
      FileSystem localFileSystem, int symlinkMinEntries, int mkdirMinEntries) {
    this.localFileSystem = localFileSystem;
    this.symlinkMinEntries = symlinkMinEntries;
    this.mkdirMinEntries = mkdirMinEntries;
  }

  @Override
  public int getMaxConcurrentCalls() {
    return 0;
  }

  @Override
  public List<FileStatusWithDigest> batchStat(Iterable<PathFragment> paths) throws IOException {
    throw new IOException("Native output stat batching is not enabled");
  }

  @Override
  @SuppressWarnings("try")
  public int createDirectories(Path root, List<PathFragment> directories) throws IOException {
    if (directories.isEmpty() || directories.size() < mkdirMinEntries) {
      return -1;
    }
    int created = 0;
    while (created < directories.size()) {
      int end = created;
      int bytes = 0;
      while (end < directories.size() && end - created < MAX_ENTRIES) {
        long size = (long) directories.get(end).getPathString().length() + 2;
        if (size > MAX_BYTES - bytes) {
          break;
        }
        bytes += (int) size;
        end++;
      }
      if (end == created) {
        return created == 0 ? -1 : created;
      }
      int count;
      try (var ignored = Profiler.instance().profile("Native batch mkdir")) {
        count =
            localFileSystem.createDirectoriesBatch(
                root.asFragment(), directories.subList(created, end));
      }
      if (count < -1 || count > end - created) {
        throw new IOException("Native batch mkdir returned an invalid created count");
      }
      if (count < end - created) {
        return count < 0 && created == 0 ? -1 : created + Math.max(count, 0);
      }
      created = end;
    }
    return created;
  }

  @Override
  public void createSymlinks(Path root, Map<PathFragment, PathFragment> symlinks)
      throws IOException {
    if (symlinks.size() < symlinkMinEntries) {
      for (var entry : symlinks.entrySet()) {
        FileSystemUtils.ensureSymbolicLink(root.getRelative(entry.getKey()), entry.getValue());
      }
      return;
    }
    List<PathFragment> paths = new ArrayList<>();
    List<PathFragment> targets = new ArrayList<>();
    int bytes = 0;
    for (var entry : symlinks.entrySet()) {
      long size =
          (long) entry.getKey().getPathString().length()
              + entry.getValue().getSafePathString().length()
              + 2;
      if (!paths.isEmpty() && (paths.size() == MAX_ENTRIES || size > MAX_BYTES - bytes)) {
        createSymlinkBatch(root, paths, targets);
        paths.clear();
        targets.clear();
        bytes = 0;
      }
      if (size > MAX_BYTES) {
        FileSystemUtils.ensureSymbolicLink(root.getRelative(entry.getKey()), entry.getValue());
      } else {
        paths.add(entry.getKey());
        targets.add(entry.getValue());
        bytes += (int) size;
      }
    }
    if (!paths.isEmpty()) {
      createSymlinkBatch(root, paths, targets);
    }
  }

  @SuppressWarnings("try")
  private void createSymlinkBatch(Path root, List<PathFragment> paths, List<PathFragment> targets)
      throws IOException {
    int[] errors;
    try (var ignored = Profiler.instance().profile("Native batch symlink")) {
      errors = localFileSystem.createSymlinksBatch(root.asFragment(), paths, targets);
    }
    if (errors != null && errors.length != paths.size()) {
      throw new IOException("Native batch symlink returned an invalid result count");
    }
    for (int i = 0; i < paths.size(); i++) {
      if (errors == null || errors[i] != 0) {
        FileSystemUtils.ensureSymbolicLink(root.getRelative(paths.get(i)), targets.get(i));
      }
    }
  }
}
