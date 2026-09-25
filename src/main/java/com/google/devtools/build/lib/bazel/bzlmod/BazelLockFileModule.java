// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.gson.JsonIOException;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * Module collecting Bazel module and module extensions resolution results and updating the
 * lockfile.
 */
public class BazelLockFileModule extends BlazeModule {

  private CommandEnvironment env;
  // Reused by afterCommand when tidy --diff has already checked the workspace lockfile.
  @Nullable private LockfileUpdate lockfileUpdate;

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ImmutableSet<LockfileMode> ENABLED_IN_MODES =
      Sets.immutableEnumSet(LockfileMode.UPDATE, LockfileMode.REFRESH, LockfileMode.ERROR);

  @Override
  public void beforeCommand(CommandEnvironment env) {
    this.env = env;
    lockfileUpdate = null;
  }

  @Override
  public void afterCommand() {
    CommandEnvironment env = this.env;
    this.env = null;
    LockfileUpdate update = lockfileUpdate != null ? lockfileUpdate : collectLockfileUpdate(env);
    lockfileUpdate = null;
    if (update == null) {
      return;
    }

    // Suppress workspace writes even when tidy --diff fails before computing a preview.
    ModOptions modOptions = env.getOptions().getOptions(ModOptions.class);
    boolean writeWorkspaceLockfile =
        (modOptions == null || !modOptions.getDiff()) && update.workspaceLockfileNeedsUpdate();
    Thread updateLockfile =
        Thread.startVirtualThread(
            () -> {
              if (writeWorkspaceLockfile) {
                updateLockfile(
                    env.getWorkspace().getRelative(LabelConstants.MODULE_LOCKFILE_NAME),
                    update.newLockfile());
              }
            });
    Thread updateHiddenLockfile =
        Thread.startVirtualThread(
            () ->
                updateHiddenLockfiles(
                    env.getOutputBase(),
                    update.usedExtensions(),
                    update.newExtensions(),
                    update.oldHiddenLockfiles()));
    try {
      updateLockfile.join();
      updateHiddenLockfile.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.atSevere().withCause(e).log(
          "Interrupted while updating MODULE.bazel.lock file: %s", e.getMessage());
    }
  }

  /**
   * Returns whether the selected lockfile mode would update the workspace lockfile.
   *
   * <p>Call after all Skyframe evaluations of the current command have completed.
   */
  public boolean workspaceLockfileNeedsUpdate() {
    lockfileUpdate = collectLockfileUpdate(env);
    return lockfileUpdate != null && lockfileUpdate.workspaceLockfileNeedsUpdate();
  }

  private record LockfileUpdate(
      BazelLockFileValue oldLockfile,
      BazelLockFileValue newLockfile,
      Set<ModuleExtensionId> usedExtensions,
      Map<ModuleExtensionId, SingleExtensionValue> newExtensions,
      Map<ModuleExtensionId, BazelLockFileValue> oldHiddenLockfiles) {
    boolean workspaceLockfileNeedsUpdate() {
      return !newLockfile.equals(oldLockfile);
    }
  }

  @Nullable
  private static LockfileUpdate collectLockfileUpdate(@Nullable CommandEnvironment env) {
    if (env == null || !env.hasSyncedPackageLoading()) {
      // The current command (e.g. shutdown) didn't evaluate the lockfile values so they may
      // be stale, e.g., if a server with a different output base changed the lockfile
      // in the meantime.
      return null;
    }
    LockfileMode lockfileMode =
        env.getOptions().getOptions(RepositoryOptions.class).getLockfileMode();
    if (!ENABLED_IN_MODES.contains(lockfileMode)) {
      return null;
    }
    MemoizingEvaluator evaluator = env.getSkyframeExecutor().getEvaluator();
    BazelModuleResolutionValue moduleResolutionValue;
    BazelDepGraphValue depGraphValue;
    BazelLockFileValue oldLockfile;
    try {
      moduleResolutionValue =
          (BazelModuleResolutionValue) evaluator.getExistingValue(BazelModuleResolutionValue.KEY);
      depGraphValue = (BazelDepGraphValue) evaluator.getExistingValue(BazelDepGraphValue.KEY);
      oldLockfile = (BazelLockFileValue) evaluator.getExistingValue(BazelLockFileValue.KEY);
    } catch (InterruptedException e) {
      // Not thrown in Bazel.
      throw new IllegalStateException(e);
    }
    if (moduleResolutionValue == null || depGraphValue == null || oldLockfile == null) {
      // Since these values are required to compute the main repo mapping, which happens in every
      // build, an error must have occurred that prevented the evaluation of these values and that
      // has already been reported at this point.
      return null;
    }
    // All nodes corresponding to module extensions that have been evaluated in the current build
    // are done at this point. Look up entries by eval keys to record results even if validation
    // later fails due to invalid imports.
    // Note: This also picks up up-to-date results from previous builds that are not in the
    // transitive closure of the current build. Since extensions are potentially costly to evaluate,
    // this is seen as an advantage. Full reproducibility can be ensured by running 'bazel shutdown'
    // first if needed.
    var usedExtensions = depGraphValue.getExtensionUsagesTable().rowKeySet();
    var numExtensions = usedExtensions.size();
    var newExtensions = new HashMap<ModuleExtensionId, SingleExtensionValue>(numExtensions);
    var oldHiddenLockfiles = new HashMap<ModuleExtensionId, BazelLockFileValue>(numExtensions);
    var combinedFacts = new HashMap<ModuleExtensionId, Facts>(numExtensions);
    combinedFacts.putAll(oldLockfile.getFacts());
    var combinedFactsVersions = new HashMap<ModuleExtensionId, Integer>(numExtensions);
    combinedFactsVersions.putAll(oldLockfile.getFactsVersions());
    var doneValues = evaluator.getDoneValues();
    for (var extensionId : usedExtensions) {
      if (extensionId.isInnate()) {
        // The innate extensions are implemented in Java and don't benefit from a lockfile entry.
        continue;
      }
      var value = (SingleExtensionValue) doneValues.get(SingleExtensionValue.evalKey(extensionId));
      if (value != null) {
        newExtensions.put(extensionId, value);
        var oldHiddenLockfile =
            (BazelLockFileValue) doneValues.get(BazelLockFileValue.hiddenKey(extensionId));
        if (oldHiddenLockfile != null) {
          oldHiddenLockfiles.put(extensionId, oldHiddenLockfile);
        }
        combinedFacts.put(extensionId, value.facts());
        combinedFactsVersions.put(extensionId, value.factsVersion());
      }
    }

    // ERROR keeps the workspace lockfile read-only while still updating the extension cache.
    BazelLockFileValue newLockfile = oldLockfile;
    if (lockfileMode != LockfileMode.ERROR) {
      var relevantFacts = filterRelevantFacts(combinedFacts, depGraphValue);
      var relevantFactsVersions = filterRelevantFactsVersions(combinedFactsVersions, relevantFacts);
      var newExtensionInfos =
          Maps.transformValues(newExtensions, value -> value.lockFileInfo().get());
      var notReproducibleExtensionInfos =
          combineModuleExtensions(
              oldLockfile.getModuleExtensions(),
              newExtensionInfos,
              /* hasUsages= */ usedExtensions::contains,
              /* reproducible= */ false);

      // Local registries are under the user's control, and their URLs contain absolute paths.
      var remoteRegistryFileHashes =
          ImmutableSortedMap.copyOf(
              Maps.filterKeys(
                  moduleResolutionValue.getRegistryFileHashes(), url -> !url.startsWith("file:")));
      newLockfile =
          BazelLockFileValue.builder()
              .setRegistryFileHashes(remoteRegistryFileHashes)
              .setSelectedYankedVersions(moduleResolutionValue.getSelectedYankedVersions())
              .setModuleExtensions(notReproducibleExtensionInfos)
              .setFacts(relevantFacts)
              .setFactsVersions(relevantFactsVersions)
              .build();
    }
    return new LockfileUpdate(
        oldLockfile, newLockfile, usedExtensions, newExtensions, oldHiddenLockfiles);
  }

  private static void updateHiddenLockfiles(
      Path outputBase,
      Set<ModuleExtensionId> usedExtensions,
      Map<ModuleExtensionId, SingleExtensionValue> newExtensions,
      Map<ModuleExtensionId, BazelLockFileValue> oldHiddenLockfiles) {
    Path cacheDirectory = outputBase.getRelative(LabelConstants.MODULE_EXTENSION_CACHE_DIRECTORY);
    try {
      if (!newExtensions.isEmpty()) {
        cacheDirectory.createDirectoryAndParents();
      }
      for (var entry : newExtensions.entrySet()) {
        var extensionId = entry.getKey();
        var value = entry.getValue();
        var oldLockfile =
            oldHiddenLockfiles.getOrDefault(extensionId, BazelLockFileValue.EMPTY_LOCKFILE);
        // These facts must come from an evaluation, never from possibly edited workspace facts.
        var facts = value.facts();
        var newLockfile =
            BazelLockFileValue.builder()
                .setModuleExtensions(
                    combineModuleExtensions(
                        oldLockfile.getModuleExtensions(),
                        ImmutableMap.of(extensionId, value.lockFileInfo().get()),
                        extensionId::equals,
                        /* reproducible= */ true))
                .setFacts(
                    facts.equals(Facts.EMPTY)
                        ? ImmutableMap.of()
                        : ImmutableMap.of(extensionId, facts))
                .setFactsVersions(
                    facts.equals(Facts.EMPTY) || value.factsVersion() == 0
                        ? ImmutableMap.of()
                        : ImmutableMap.of(extensionId, value.factsVersion()))
                .build();
        if (!newLockfile.equals(oldLockfile)) {
          updateLockfile(
              BazelLockFileFunction.hiddenLockfilePath(outputBase, extensionId), newLockfile);
        }
      }
      if (cacheDirectory.exists()) {
        var usedPaths =
            usedExtensions.stream()
                .filter(id -> !id.isInnate())
                .map(id -> BazelLockFileFunction.hiddenLockfilePath(outputBase, id))
                .collect(ImmutableSet.toImmutableSet());
        for (Path cacheFile : cacheDirectory.getDirectoryEntries()) {
          if (!usedPaths.contains(cacheFile)) {
            cacheFile.delete();
          }
        }
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("Error updating module extension cache: %s", e.getMessage());
    }
  }

  private static ImmutableSortedMap<ModuleExtensionId, Facts> filterRelevantFacts(
      Map<ModuleExtensionId, Facts> combinedFacts, BazelDepGraphValue depGraphValue) {
    return ImmutableSortedMap.copyOf(
        Maps.filterEntries(
            combinedFacts,
            entry ->
                depGraphValue.getExtensionUsagesTable().containsRow(entry.getKey())
                    && !entry.getValue().equals(Facts.EMPTY)),
        ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
  }

  /** Only keeps non-zero versions for extensions that have facts persisted; the default is 0. */
  private static ImmutableSortedMap<ModuleExtensionId, Integer> filterRelevantFactsVersions(
      Map<ModuleExtensionId, Integer> combinedFactsVersions,
      ImmutableSortedMap<ModuleExtensionId, Facts> relevantFacts) {
    return ImmutableSortedMap.copyOf(
        Maps.filterEntries(
            combinedFactsVersions,
            entry ->
                relevantFacts.containsKey(entry.getKey())
                    && entry.getValue() != null
                    && entry.getValue() != 0),
        ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
  }

  /**
   * Combines the old extensions stored in the lockfile -if they are still used and have the same
   * dependence on os/arch - with the new extensions from the events (if any)
   */
  @VisibleForTesting
  static ImmutableMap<
          ModuleExtensionId, ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
      combineModuleExtensions(
          Map<ModuleExtensionId, ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
              oldExtensionInfos,
          Map<ModuleExtensionId, LockFileModuleExtension.WithFactors> newExtensionInfos,
          Predicate<ModuleExtensionId> hasUsages,
          boolean reproducible) {
    Map<ModuleExtensionId, ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension>>
        updatedExtensionMap = new HashMap<>();

    // Keep those per factor extension results that are still used according to the static
    // information given in the extension declaration (dependence on os and arch).
    // Other information such as transitive .bzl hash and usages hash are *not* checked here.
    for (var entry : oldExtensionInfos.entrySet()) {
      var moduleExtensionId = entry.getKey();
      if (!hasUsages.test(moduleExtensionId)) {
        // Extensions without any usages are not needed anymore.
        continue;
      }
      var newExtensionInfo = newExtensionInfos.get(moduleExtensionId);
      if (newExtensionInfo == null) {
        // No information based on which we could invalidate old entries, keep all of them.
        updatedExtensionMap.put(moduleExtensionId, entry.getValue());
        continue;
      }
      var newFactors = newExtensionInfo.extensionFactors();
      // Prefer the new result for its particular set of factors.
      var perFactorsResultsToKeep =
          ImmutableSortedMap.copyOf(
              Maps.filterKeys(
                  entry.getValue(),
                  oldFactors ->
                      oldFactors.hasSameDependenciesAs(newFactors)
                          && !oldFactors.equals(newFactors)));
      if (perFactorsResultsToKeep.isEmpty()) {
        continue;
      }
      updatedExtensionMap.put(moduleExtensionId, perFactorsResultsToKeep);
    }

    // Add the new resolved extensions
    for (var extensionIdAndInfo : newExtensionInfos.entrySet()) {
      LockFileModuleExtension extension = extensionIdAndInfo.getValue().moduleExtension();
      if (extension.isReproducible() != reproducible) {
        continue;
      }

      var oldExtensionEntries = updatedExtensionMap.get(extensionIdAndInfo.getKey());
      ImmutableMap<ModuleExtensionEvalFactors, LockFileModuleExtension> extensionEntries;
      var factors = extensionIdAndInfo.getValue().extensionFactors();
      if (oldExtensionEntries != null) {
        // extension exists, add the new entry to the existing map
        extensionEntries =
            ImmutableSortedMap.copyOf(
                ImmutableMap.<ModuleExtensionEvalFactors, LockFileModuleExtension>builder()
                    .putAll(oldExtensionEntries)
                    .put(factors, extension)
                    .buildKeepingLast());
      } else {
        // new extension
        extensionEntries = ImmutableMap.of(factors, extension);
      }
      updatedExtensionMap.put(extensionIdAndInfo.getKey(), extensionEntries);
    }

    // The order in which extensions are added to extensionResolutionEvents depends on the order
    // in which their Skyframe evaluations finish, which is non-deterministic. We ensure a
    // deterministic lockfile by sorting.
    return ImmutableSortedMap.copyOf(
        updatedExtensionMap, ModuleExtensionId.LEXICOGRAPHIC_COMPARATOR);
  }

  /**
   * Updates the data stored in the lockfile (MODULE.bazel.lock)
   *
   * @param lockfilePath File to update
   * @param updatedLockfile The updated lockfile data to save
   */
  @VisibleForTesting
  static void updateLockfile(Path lockfilePath, BazelLockFileValue updatedLockfile) {
    try (var outputStream = lockfilePath.getOutputStream();
        var outputStreamWriter = new OutputStreamWriter(outputStream, UTF_8);
        var writer = new BufferedWriter(outputStreamWriter)) {
      try {
        GsonTypeAdapterUtil.LOCKFILE_GSON.toJson(updatedLockfile, writer);
      } catch (JsonIOException e) {
        // Gson.toJson(Object, Appendable) documents JsonIOException for writer failures.
        if (e.getCause() instanceof IOException ioException) {
          throw ioException;
        }
        throw new IOException(e);
      }
      writer.append('\n');
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Error while updating MODULE.bazel.lock file: %s", e.getMessage());
    }
  }
}
