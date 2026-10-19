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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.EvaluationResult;
import java.util.Optional;
import net.starlark.java.eval.Dict;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Exercises the optional capsule layer through real extension evaluation and recorded inputs. */
@RunWith(JUnit4.class)
public class ExtensionCapsuleTest extends BuildViewTestCase {
  private ModuleExtensionId extensionId;
  private Path capsuleDirectory;
  private Path capsulePath;

  @Before
  public void setupCapsuleFixture() throws Exception {
    System.clearProperty(SingleExtensionEvalFunction.EXTENSION_CAPSULE_DIRECTORY_PROPERTY);
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(BazelLockFileFunction.LOCKFILE_MODE, LockfileMode.UPDATE)));
    extensionId =
        ModuleExtensionId.create(
            Label.parseCanonical("//:capsule_defs.bzl"), "ext", Optional.empty());
    capsuleDirectory = scratch.dir("/extension-capsules");
    capsulePath =
        capsuleDirectory.getRelative(
            Hashing.sha256().hashString(extensionId.toString(), UTF_8) + ".json");
    FileSystemUtils.writeContent(
        capsuleDirectory.getRelative("workspace-root.txt"),
        UTF_8,
        rootDirectory.getPathString() + "\n");
    writeModule("one");
    scratch.overwriteFile("BUILD");
    scratch.file("input.txt", "first");
    writeExtension(true, "");
  }

  @After
  public void clearCapsuleProperty() {
    System.clearProperty(SingleExtensionEvalFunction.EXTENSION_CAPSULE_DIRECTORY_PROPERTY);
  }

  private void writeModule(String value) throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "ext = use_extension('//:capsule_defs.bzl', 'ext')",
        "ext.tag(value='" + value + "')",
        "use_repo(ext, 'result')");
  }

  private void writeExtension(boolean reproducible, String suffix) throws Exception {
    scratch.overwriteFile(
        "capsule_defs.bzl",
        "def _repo_impl(ctx):",
        "  ctx.file('BUILD', '')",
        "repo = repository_rule(implementation=_repo_impl, attrs={'data': attr.string()})",
        "tag = tag_class(attrs={'value': attr.string()})",
        "def _impl(ctx):",
        "  print('CAPSULE_EXTENSION_RAN')",
        "  ctx.getenv('BAZEL_CAPSULE_TEST_INPUT_ENV')",
        "  value = ','.join([tag.value for mod in ctx.modules for tag in mod.tags.tag])",
        "  repo(name='result', data=ctx.read(Label('//:input.txt')) + ':' + value)",
        "  return ctx.extension_metadata(reproducible="
            + (reproducible ? "True" : "False")
            + ", facts={'fixed': 'expected'})",
        "ext = module_extension(implementation=_impl, tag_classes={'tag': tag})",
        "# " + suffix);
  }

  private SingleExtensionValue evaluate() throws Exception {
    invalidatePackages(false);
    var key = SingleExtensionValue.evalKey(extensionId);
    EvaluationResult<SingleExtensionValue> result =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, key, false, reporter);
    if (result.hasError()) throw result.getError().getException();
    return result.get(key);
  }

  private BazelLockFileValue capsuleFor(SingleExtensionValue value) {
    var locked = value.lockFileInfo().orElseThrow();
    return BazelLockFileValue.EMPTY_LOCKFILE.toBuilder()
        .setModuleExtensions(
            ImmutableMap.of(
                extensionId, ImmutableMap.of(locked.extensionFactors(), locked.moduleExtension())))
        .setFacts(ImmutableMap.of(extensionId, value.facts()))
        .setFactsVersions(ImmutableMap.of(extensionId, value.factsVersion()))
        .build();
  }

  private void writeCapsule(BazelLockFileValue value) throws Exception {
    FileSystemUtils.writeContent(
        capsulePath, UTF_8, GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(value));
  }

  private void retryWithCapsule() {
    System.setProperty(
        SingleExtensionEvalFunction.EXTENSION_CAPSULE_DIRECTORY_PROPERTY,
        capsuleDirectory.getPathString());
    skyframeExecutor
        .getEvaluator()
        .delete(key -> key.equals(SingleExtensionValue.evalKey(extensionId)));
    eventCollector.clear();
  }

  private void assertHit() {
    assertContainsEvent("Reused validated extension capsule: " + extensionId);
    assertDoesNotContainEvent("CAPSULE_EXTENSION_RAN");
  }

  private void assertMiss() {
    assertDoesNotContainEvent("Reused validated extension capsule:");
    assertContainsEvent("CAPSULE_EXTENSION_RAN");
  }

  @Test
  public void validCapsuleUsesNormalValidationAndReusesResult() throws Exception {
    var original = evaluate();
    writeCapsule(capsuleFor(original));
    retryWithCapsule();
    assertThat(evaluate().generatedRepoSpecs()).isEqualTo(original.generatedRepoSpecs());
    assertHit();
  }

  @Test
  public void missingCapsuleFallsBack() throws Exception {
    evaluate();
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void malformedCapsuleFallsBack() throws Exception {
    evaluate();
    FileSystemUtils.writeContent(capsulePath, UTF_8, "{invalid");
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void inconsistentOptionalMetadataFallsBack() throws Exception {
    var capsule = capsuleFor(evaluate());
    var factors = capsule.getModuleExtensions().get(extensionId).keySet().iterator().next();
    var entry = capsule.getModuleExtensions().get(extensionId).get(factors);
    var badMetadata =
        GsonTypeAdapterUtil.LOCKFILE_GSON.fromJson(
            "{\"explicitRootModuleDirectDeps\":[\"result\"],"
                + "\"explicitRootModuleDirectDevDeps\":null,"
                + "\"useAllRepos\":\"NO\",\"reproducible\":true}",
            LockfileModuleExtensionMetadata.class);
    var badEntry =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(entry.getBzlTransitiveDigest())
            .setUsagesDigest(entry.getUsagesDigest())
            .setRecordedInputs(entry.getRecordedInputs())
            .setGeneratedRepoSpecs(entry.getGeneratedRepoSpecs())
            .setModuleExtensionMetadata(Optional.of(badMetadata))
            .build();
    writeCapsule(
        capsule.toBuilder()
            .setModuleExtensions(ImmutableMap.of(extensionId, ImmutableMap.of(factors, badEntry)))
            .build());
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void obsoleteSchemaFallsBack() throws Exception {
    writeCapsule(capsuleFor(evaluate()).toBuilder().setLockFileVersion(0).build());
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void changedRecordedFileFallsBack() throws Exception {
    writeCapsule(capsuleFor(evaluate()));
    scratch.overwriteFile("input.txt", "second");
    retryWithCapsule();
    assertThat(evaluate().generatedRepoSpecs().get("result").attributes().attributes().get("data"))
        .isEqualTo("second\n:one");
    assertMiss();
  }

  @Test
  public void changedProducerEnvironmentFallsBack() throws Exception {
    var capsule = capsuleFor(evaluate());
    var factors = capsule.getModuleExtensions().get(extensionId).keySet().iterator().next();
    var entry = capsule.getModuleExtensions().get(extensionId).get(factors);
    var records = ImmutableList.<RepoRecordedInput.WithValue>builder();
    var variable = new RepoRecordedInput.EnvVar("BAZEL_CAPSULE_TEST_INPUT_ENV");
    boolean replaced = false;
    for (var record : entry.getRecordedInputs()) {
      if (record.input().equals(variable)) {
        assertThat(record.value()).isNull();
        records.add(new RepoRecordedInput.WithValue(variable, "producer-value"));
        replaced = true;
      } else {
        records.add(record);
      }
    }
    assertThat(replaced).isTrue();
    var changedEntry =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(entry.getBzlTransitiveDigest())
            .setUsagesDigest(entry.getUsagesDigest())
            .setRecordedInputs(records.build())
            .setGeneratedRepoSpecs(entry.getGeneratedRepoSpecs())
            .setModuleExtensionMetadata(entry.getModuleExtensionMetadata())
            .build();
    writeCapsule(
        capsule.toBuilder()
            .setModuleExtensions(
                ImmutableMap.of(extensionId, ImmutableMap.of(factors, changedEntry)))
            .build());
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void changedUsageFallsBack() throws Exception {
    writeCapsule(capsuleFor(evaluate()));
    writeModule("two");
    retryWithCapsule();
    assertThat(evaluate().generatedRepoSpecs().get("result").attributes().attributes().get("data"))
        .isEqualTo("first\n:two");
    assertMiss();
  }

  @Test
  public void changedBzlFallsBack() throws Exception {
    writeCapsule(capsuleFor(evaluate()));
    writeExtension(true, "new implementation digest");
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void editedWorkspaceFactsRemainAuthoritative() throws Exception {
    var capsule = capsuleFor(evaluate());
    writeCapsule(capsule);
    var edited =
        BazelLockFileValue.EMPTY_LOCKFILE.toBuilder()
            .setFacts(
                ImmutableMap.of(
                    extensionId,
                    Facts.validateAndCreate(
                        Dict.immutableCopyOf(ImmutableMap.of("fixed", "edited")))))
            .setFactsVersions(ImmutableMap.of(extensionId, 0))
            .build();
    scratch.overwriteFile("MODULE.bazel.lock", GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(edited));
    retryWithCapsule();
    assertThat(evaluate().facts()).isEqualTo(capsule.getFacts().get(extensionId));
    assertMiss();
  }

  @Test
  public void oldWorkspaceFactsSchemaUsesNormalRepair() throws Exception {
    var capsule = capsuleFor(evaluate());
    writeCapsule(capsule);
    var oldWorkspace =
        BazelLockFileValue.EMPTY_LOCKFILE.toBuilder()
            .setFacts(capsule.getFacts())
            .setFactsVersions(ImmutableMap.of(extensionId, 99))
            .build();
    scratch.overwriteFile(
        "MODULE.bazel.lock", GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(oldWorkspace));
    retryWithCapsule();
    var result = evaluate();
    assertThat(result.facts()).isEqualTo(capsule.getFacts().get(extensionId));
    assertThat(result.factsVersion()).isEqualTo(0);
    assertMiss();
  }

  @Test
  public void nonReproducibleCapsuleIsNeverUsed() throws Exception {
    writeExtension(false, "");
    writeCapsule(capsuleFor(evaluate()));
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void wrongPlatformFallsBack() throws Exception {
    var capsule = capsuleFor(evaluate());
    var locked = capsule.getModuleExtensions().get(extensionId).values().iterator().next();
    writeCapsule(
        capsule.toBuilder()
            .setModuleExtensions(
                ImmutableMap.of(
                    extensionId,
                    ImmutableMap.of(
                        ModuleExtensionEvalFactors.create("other-os", "other-arch"), locked)))
            .build());
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void oldFactsVersionFallsBack() throws Exception {
    writeCapsule(
        capsuleFor(evaluate()).toBuilder()
            .setFactsVersions(ImmutableMap.of(extensionId, 99))
            .build());
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void anotherWorkspaceRootIsNeverUsed() throws Exception {
    writeCapsule(capsuleFor(evaluate()));
    FileSystemUtils.writeContent(
        capsuleDirectory.getRelative("workspace-root.txt"), UTF_8, "/other-workspace\n");
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void missingWorkspaceMarkerFallsBack() throws Exception {
    writeCapsule(capsuleFor(evaluate()));
    capsuleDirectory.getRelative("workspace-root.txt").delete();
    retryWithCapsule();
    evaluate();
    assertMiss();
  }

  @Test
  public void lockfileOffDoesNotReadCapsules() throws Exception {
    writeCapsule(capsuleFor(evaluate()));
    retryWithCapsule();
    skyframeExecutor.injectExtraPrecomputedValues(
        ImmutableList.of(
            PrecomputedValue.injected(BazelLockFileFunction.LOCKFILE_MODE, LockfileMode.OFF)));
    evaluate();
    assertMiss();
  }

  @Test
  public void replacingCapsuleWithSameSizeAndMtimeIsObserved() throws Exception {
    var capsule = capsuleFor(evaluate());
    writeCapsule(capsule);
    retryWithCapsule();
    evaluate();
    assertHit();
    long oldTime = capsulePath.getLastModifiedTime();
    long oldSize = capsulePath.getFileSize();
    var factors = capsule.getModuleExtensions().get(extensionId).keySet().iterator().next();
    var entry = capsule.getModuleExtensions().get(extensionId).get(factors);
    byte[] changedUsage = entry.getUsagesDigest().clone();
    changedUsage[0] ^= 1;
    var changedEntry =
        LockFileModuleExtension.builder()
            .setBzlTransitiveDigest(entry.getBzlTransitiveDigest())
            .setUsagesDigest(changedUsage)
            .setRecordedInputs(entry.getRecordedInputs())
            .setGeneratedRepoSpecs(entry.getGeneratedRepoSpecs())
            .setModuleExtensionMetadata(entry.getModuleExtensionMetadata())
            .build();
    writeCapsule(
        capsule.toBuilder()
            .setModuleExtensions(
                ImmutableMap.of(extensionId, ImmutableMap.of(factors, changedEntry)))
            .build());
    assertThat(capsulePath.getFileSize()).isEqualTo(oldSize);
    capsulePath.setLastModifiedTime(oldTime);
    retryWithCapsule();
    evaluate();
    assertMiss();
  }
}
