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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.Mutability;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Behavioral tests for the bounded digest-verified lockfile cache. */
@RunWith(JUnit4.class)
public final class LockfileMemoizationTest {
  private static final JavaIoFileSystem FS = new JavaIoFileSystem(DigestHashFunction.SHA256);

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static RootedPath rooted(Path directory) {
    return RootedPath.toRootedPath(
        Root.fromPath(FS.getPath(directory.toString())), PathFragment.create("MODULE.bazel.lock"));
  }

  private static BazelLockFileValue fixture(String key) {
    return BazelLockFileValue.EMPTY_LOCKFILE.toBuilder()
        .setRegistryFileHashes(ImmutableMap.of("https://example.invalid/" + key, Optional.empty()))
        .build();
  }

  private static void writeExternal(Path directory, BazelLockFileValue value) throws Exception {
    Files.writeString(
        directory.resolve("MODULE.bazel.lock"), GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(value));
  }

  @Test
  public void digestVerifiedReadAndWriteReuse() throws Exception {
    Path a = Files.createTempDirectory("bazel-lock-memo-a-");
    Path b = Files.createTempDirectory("bazel-lock-memo-b-");
    Path c = Files.createTempDirectory("bazel-lock-memo-c-");
    try {
      BazelLockFileValue alpha = fixture("alpha");
      BazelLockFileValue bravo = fixture("bravo");
      writeExternal(a, alpha);
      BazelLockFileValue first =
          BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE);
      check(first.equals(alpha), "first external read parses correctly");
      check(
          first == BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE),
          "unchanged reread reuses parsed object");
      check(
          first == BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.ERROR),
          "valid reuse preserves ERROR mode");

      var file = a.resolve("MODULE.bazel.lock");
      var timestamp = Files.getLastModifiedTime(file);
      long size = Files.size(file);
      writeExternal(a, bravo);
      Files.setLastModifiedTime(file, timestamp);
      check(size == Files.size(file), "mutation has same size");
      var changed = BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE);
      check(
          changed.equals(bravo) && changed != first,
          "same-size same-mtime edit invalidates cache by digest");

      BazelLockFileModule.updateLockfile(FS.getPath(a.toString()), alpha);
      check(
          BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE) == alpha,
          "successful write seeds exact constructed value");
      BazelLockFileModule.updateLockfile(FS.getPath(b.toString()), bravo);
      check(
          BazelLockFileFunction.getLockfileValue(rooted(b), LockfileMode.UPDATE) == bravo,
          "second path reuses its own value");
      check(
          BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE) == alpha,
          "paths do not contaminate each other");
      BazelLockFileModule.updateLockfile(FS.getPath(c.toString()), bravo);
      var bAfterEviction = BazelLockFileFunction.getLockfileValue(rooted(b), LockfileMode.UPDATE);
      check(
          bAfterEviction.equals(bravo) && bAfterEviction != bravo,
          "cache is bounded and eviction preserves parsed value");

      BazelLockFileModule.updateLockfile(FS.getPath(a.toString()), alpha);
      Files.delete(file);
      check(
          BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE)
              == BazelLockFileValue.EMPTY_LOCKFILE,
          "deletion never returns cached nonempty value");
      Files.writeString(
          file, "{\"lockFileVersion\":" + BazelLockFileValue.LOCK_FILE_VERSION + ",malformed-json");
      boolean invalidThrew = false;
      try {
        BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE);
      } catch (com.google.gson.JsonSyntaxException expected) {
        invalidThrew = true;
      }
      check(invalidThrew, "invalid replacement retains parser error behavior");

      var oldVersion = alpha.toBuilder().setLockFileVersion(0).build();
      BazelLockFileModule.updateLockfile(FS.getPath(a.toString()), oldVersion);
      check(
          BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE)
              == BazelLockFileValue.EMPTY_LOCKFILE,
          "obsolete written format not cached as valid");
      boolean oldThrew = false;
      try {
        BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.ERROR);
      } catch (BazelLockFileFunction.BazelLockfileFunctionException expected) {
        oldThrew = true;
      }
      check(oldThrew, "obsolete format still fails ERROR mode");
      var extensionId =
          ModuleExtensionId.create(
              Label.parseCanonicalUnchecked("//:memo.bzl"), "memo", Optional.empty());
      var factors = ModuleExtensionEvalFactors.create("", "");
      byte[] bzlDigest = {1, 2, 3};
      byte[] usagesDigest = {4, 5, 6};
      var extension =
          LockFileModuleExtension.builder()
              .setBzlTransitiveDigest(bzlDigest)
              .setUsagesDigest(usagesDigest)
              .setRecordedInputs(ImmutableList.of())
              .setGeneratedRepoSpecs(ImmutableMap.of())
              .build();
      var withExtension =
          alpha.toBuilder()
              .setModuleExtensions(
                  ImmutableMap.of(extensionId, ImmutableMap.of(factors, extension)))
              .build();
      BazelLockFileModule.updateLockfile(FS.getPath(a.toString()), withExtension);
      bzlDigest[0] = 99;
      usagesDigest[0] = 99;
      var snapshotRead = BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE);
      var snapshotExtension = snapshotRead.getModuleExtensions().get(extensionId).get(factors);
      check(
          snapshotExtension.getBzlTransitiveDigest()[0] == 1
              && snapshotExtension.getUsagesDigest()[0] == 4,
          "mutating writer digest arrays does not change cached serialized snapshot");
      snapshotExtension.getBzlTransitiveDigest()[0] = 88;
      snapshotExtension.getUsagesDigest()[0] = 88;
      var anotherRead = BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE);
      var anotherExtension = anotherRead.getModuleExtensions().get(extensionId).get(factors);
      check(
          anotherExtension.getBzlTransitiveDigest()[0] == 1
              && anotherExtension.getUsagesDigest()[0] == 4,
          "mutating reader digest arrays does not corrupt cached snapshot");
      try (var mutability = Mutability.create("lock-memo-test")) {
        Dict<String, Object> attributes = Dict.of(mutability);
        attributes.putEntry("value", "before");
        var spec =
            new RepoSpec(
                new RepoRuleId(Label.parseCanonicalUnchecked("//:repo.bzl"), "repository"),
                AttributeValues.create(attributes));
        var mutableExtension =
            LockFileModuleExtension.builder()
                .setBzlTransitiveDigest(new byte[] {1})
                .setUsagesDigest(new byte[] {2})
                .setRecordedInputs(ImmutableList.of())
                .setGeneratedRepoSpecs(ImmutableMap.of("example", spec))
                .build();
        var mutableValue =
            alpha.toBuilder()
                .setModuleExtensions(
                    ImmutableMap.of(extensionId, ImmutableMap.of(factors, mutableExtension)))
                .build();
        check(
            BazelLockFileFunction.snapshotLockfileValue(mutableValue) == null,
            "unfrozen attributes decline caching");
        BazelLockFileModule.updateLockfile(FS.getPath(a.toString()), mutableValue);
        attributes.putEntry("value", "after");
        var parsed = BazelLockFileFunction.getLockfileValue(rooted(a), LockfileMode.UPDATE);
        check(
            parsed
                .getModuleExtensions()
                .get(extensionId)
                .get(factors)
                .getGeneratedRepoSpecs()
                .get("example")
                .attributes()
                .attributes()
                .get("value")
                .equals("before"),
            "unfrozen writer mutation leaves serialized snapshot intact");
      }

    } finally {
      for (var directory : new Path[] {a, b, c}) {
        Files.deleteIfExists(directory.resolve("MODULE.bazel.lock"));
        Files.delete(directory);
      }
    }
  }
}
