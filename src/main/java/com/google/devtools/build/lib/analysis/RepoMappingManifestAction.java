// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.devtools.build.lib.actions.ActionKeyContext.describeNestedSetFingerprint;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.analysis.actions.AbstractFileWriteAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.unsafe.StringUnsafe;
import com.google.devtools.build.lib.util.DeterministicWriter;
import com.google.devtools.build.lib.util.Fingerprint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;

/** Creates a manifest file describing the repos and mappings relevant for a runfile tree. */
public final class RepoMappingManifestAction extends AbstractFileWriteAction
    implements AbstractFileWriteAction.FileContentsProvider {

  private static final UUID MY_UUID = UUID.fromString("458e351c-4d30-433d-b927-da6cddd4737f");

  private static final LoadingCache<ImmutableMap<String, RepositoryName>, String>
      repoMappingFingerprintCache =
          Caffeine.newBuilder()
              .weakKeys()
              .build(
                  repoMapping -> {
                    Fingerprint fp = new Fingerprint();
                    fp.addInt(repoMapping.size());
                    repoMapping.forEach(
                        (apparentName, canonicalName) -> {
                          fp.addString(apparentName);
                          fp.addString(canonicalName.getName());
                        });
                    return fp.hexDigestAndReset();
                  });

  // Uses MapFn's args parameter just like Fingerprint#addString to compute a cacheable fingerprint
  // of just the repo name and mapping of a given Package.
  private static final CommandLineItem.ExceptionlessMapFn<Package.Metadata>
      REPO_AND_MAPPING_DIGEST_FN =
          (pkgMetadata, args) -> {
            args.accept(pkgMetadata.packageIdentifier().getRepository().getName());
            var mapping = pkgMetadata.repositoryMapping();
            args.accept(repoMappingFingerprintCache.get(mapping.entries()));
            if (mapping.runfilesSource() == RepositoryMapping.RunfilesSource.SCOPED_EXTENSION) {
              args.accept("scoped extension mapping");
            }
          };

  private static final CommandLineItem.ExceptionlessMapFn<Artifact> OWNER_REPO_FN =
      (artifact, args) -> {
        args.accept(
            artifact.getOwner() != null ? artifact.getOwner().getRepository().getName() : "");
      };

  private static final CommandLineItem.ExceptionlessMapFn<SymlinkEntry> FIRST_SEGMENT_FN =
      (symlink, args) -> args.accept(symlink.getPath().getSegment(0));

  private final NestedSet<Package.Metadata> transitivePackages;
  private final NestedSet<Artifact> runfilesArtifacts;
  private final boolean hasRunfilesSymlinks;
  private final NestedSet<SymlinkEntry> runfilesRootSymlinks;
  private final String workspaceName;
  private final boolean emitCompactRepoMapping;

  public RepoMappingManifestAction(
      ActionOwner owner,
      Artifact output,
      NestedSet<Package.Metadata> transitivePackages,
      NestedSet<Artifact> runfilesArtifacts,
      NestedSet<SymlinkEntry> runfilesSymlinks,
      NestedSet<SymlinkEntry> runfilesRootSymlinks,
      String workspaceName,
      boolean emitCompactRepoMapping) {
    super(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), output);
    this.transitivePackages = transitivePackages;
    this.runfilesArtifacts = runfilesArtifacts;
    this.hasRunfilesSymlinks = !runfilesSymlinks.isEmpty();
    this.runfilesRootSymlinks = runfilesRootSymlinks;
    this.workspaceName = workspaceName;
    this.emitCompactRepoMapping = emitCompactRepoMapping;
  }

  @Override
  public String getMnemonic() {
    return "RepoMappingManifest";
  }

  @Override
  protected String getRawProgressMessage() {
    return "Writing repo mapping manifest for " + getOwner().getLabel();
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable InputMetadataProvider inputMetadataProvider,
      Fingerprint fp)
      throws CommandLineExpansionException, EvalException, InterruptedException {
    fp.addUUID(MY_UUID);
    actionKeyContext.addNestedSetToFingerprint(REPO_AND_MAPPING_DIGEST_FN, fp, transitivePackages);
    actionKeyContext.addNestedSetToFingerprint(OWNER_REPO_FN, fp, runfilesArtifacts);
    fp.addBoolean(hasRunfilesSymlinks);
    actionKeyContext.addNestedSetToFingerprint(FIRST_SEGMENT_FN, fp, runfilesRootSymlinks);
    fp.addString(workspaceName);
    fp.addBoolean(emitCompactRepoMapping);
  }

  @Override
  public String describeKey() {
    return """
    GUID: %s
    transitivePackages: %s
    runfilesArtifacts: %s
    hasRunfilesSymlinks: %s
    runfilesRootSymlinks: %s
    workspaceName: %s
    emitCompactRepoMapping: %s\
    """
        .formatted(
            MY_UUID,
            describeNestedSetFingerprint(REPO_AND_MAPPING_DIGEST_FN, transitivePackages),
            describeNestedSetFingerprint(OWNER_REPO_FN, runfilesArtifacts),
            hasRunfilesSymlinks,
            describeNestedSetFingerprint(FIRST_SEGMENT_FN, runfilesRootSymlinks),
            workspaceName,
            emitCompactRepoMapping);
  }

  /**
   * Get the contents of a file internally using an in memory output stream.
   *
   * @return returns the file contents as a string.
   */
  @Override
  public String getFileContents(@Nullable EventHandler eventHandler) throws IOException {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    newDeterministicWriter().writeTo(stream);
    return stream.toString(ISO_8859_1);
  }

  // The separator character used to combine the segments of a canonical repository name.
  // LINT.IfChange
  private static final char REPO_NAME_SEPARATOR = '+';

  // LINT.ThenChange(//src/main/java/com/google/devtools/build/lib/bazel/bzlmod/BazelDepGraphFunction.java)

  @Override
  public DeterministicWriter newDeterministicWriter(ActionExecutionContext ctx) {
    return newDeterministicWriter();
  }

  public DeterministicWriter newDeterministicWriter() {
    return writer -> {
      var reposInRunfilesPathsBuilder = ImmutableSet.<String>builder();
      // The runfiles paths of symlinks are always prefixed with the main workspace name, *not* the
      // name of the repository adding the symlink.
      if (hasRunfilesSymlinks) {
        reposInRunfilesPathsBuilder.add(RepositoryName.MAIN.getName());
      }

      // Since root symlinks are the only way to stage a runfile at a specific path under the
      // current repository's runfiles directory, recognize canonical repository names that appear
      // as the first segment of their runfiles paths.
      for (SymlinkEntry symlink : runfilesRootSymlinks.toList()) {
        reposInRunfilesPathsBuilder.add(symlink.getPath().getSegment(0));
      }

      for (Artifact artifact : runfilesArtifacts.toList()) {
        Label owner = artifact.getOwner();
        if (owner != null) {
          reposInRunfilesPathsBuilder.add(owner.getRepository().getName());
        }
      }
      var reposInRunfilesPaths = reposInRunfilesPathsBuilder.build();

      // All packages in a repository share its mapping. Sort only the distinct repository names.
      var repoMappings = new HashMap<String, RepositoryMapping>();
      var repoNames = new ArrayList<String>();
      for (Package.Metadata pkgMetadata : transitivePackages.toList()) {
        String repoName = pkgMetadata.packageIdentifier().getRepository().getName();
        if (repoMappings.putIfAbsent(repoName, pkgMetadata.repositoryMapping()) == null) {
          repoNames.add(repoName);
        }
      }
      repoNames.sort(null);
      IdentityHashMap<ImmutableMap<String, RepositoryName>, List<Entry<String, RepositoryName>>>
          cachedRelevantEntries = new IdentityHashMap<>();
      for (int start = 0; start < repoNames.size(); ) {
        String first = repoNames.get(start);
        int end = start + 1;
        // A wildcard also matches sibling sources absent from these runfiles. Only use it when
        // every repository from the extension has the same mapping.
        if (emitCompactRepoMapping
            && repoMappings.get(first).runfilesSource()
                == RepositoryMapping.RunfilesSource.SHARED_EXTENSION) {
          while (end < repoNames.size() && haveSamePrefix(first, repoNames.get(end))) {
            end++;
          }
        }
        boolean wildcard = end > start + 1;
        for (int i = start; i < (wildcard ? start + 1 : end); i++) {
          String repo = repoNames.get(i);
          String source = wildcard ? replaceLastSegmentWithAsterisk(repo) : repo;
          var entries =
              cachedRelevantEntries.computeIfAbsent(
                  repoMappings.get(repo).entries(),
                  e -> computeRelevantEntries(reposInRunfilesPaths, e));
          for (var entry : entries) {
            writeEntry(writer, source, entry.getKey(), entry.getValue());
          }
        }
        start = end;
      }
    };
  }

  /** Returns whether the two repository names agree up to the last segment of their names. */
  private static boolean haveSamePrefix(String first, String second) {
    int firstSeparatorPos = first.lastIndexOf(REPO_NAME_SEPARATOR);
    int secondSeparatorPos = second.lastIndexOf(REPO_NAME_SEPARATOR);
    if (firstSeparatorPos == -1 || firstSeparatorPos != secondSeparatorPos) {
      return false;
    }
    return first.regionMatches(0, second, 0, firstSeparatorPos);
  }

  private static String replaceLastSegmentWithAsterisk(String repoName) {
    return repoName.substring(0, repoName.lastIndexOf(REPO_NAME_SEPARATOR) + 1) + "*";
  }

  private static List<Entry<String, RepositoryName>> computeRelevantEntries(
      ImmutableSet<String> reposInRunfilesPaths,
      ImmutableMap<String, RepositoryName> mappingEntries) {
    // TODO: If this becomes a hotspot, consider iterating over reposInRunfilesPaths and looking
    //  up the apparent name in the inverse of mappingEntries, which ensures that the runtime is
    //  always linear in the number of entries ultimately emitted into the manifest and independent
    //  of the size of the individual mappings. This requires making RepositoryMapping#entries() an
    //  ImmutableBiMap, or even ImmutableMultimap since repositories can have multiple apparent
    //  names.
    var entries = new ArrayList<Entry<String, RepositoryName>>();
    for (var entry : mappingEntries.entrySet()) {
      // Skip empty apparent names, which Rlocation paths cannot reference, and repos that do
      // not appear in runfiles paths.
      if (!entry.getKey().isEmpty() && reposInRunfilesPaths.contains(entry.getValue().getName())) {
        entries.add(entry);
      }
    }
    entries.sort(Entry.comparingByKey());
    return entries;
  }

  private void writeEntry(
      OutputStream writer,
      String source,
      String targetApparentName,
      RepositoryName targetCanonicalName)
      throws IOException {
    // The canonical name of the main repo is the empty string, which is not a valid
    // name for a directory, so the "workspace name" is used the name of the
    // directory under the runfiles tree for it.
    String targetRepoDirectoryName =
        targetCanonicalName.isMain() ? workspaceName : targetCanonicalName.getName();
    writer.write(StringUnsafe.getInternalStringBytes(source));
    writer.write(',');
    writer.write(StringUnsafe.getInternalStringBytes(targetApparentName));
    writer.write(',');
    writer.write(StringUnsafe.getInternalStringBytes(targetRepoDirectoryName));
    writer.write('\n');
  }
}
