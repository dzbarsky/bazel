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

package com.google.devtools.build.lib.query2.query.hash;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException;
import com.google.devtools.build.lib.bazel.repository.RepoDefinition;
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageArgs;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleFunction;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.skyframe.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.protobuf.ByteString;
import java.net.URI;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

/** Creates the hash-only targets from the built-in checksum-pinned http_file recipe. */
public final class PinnedHttpFileQueryFunction implements SkyFunction {
  public static final SkyFunctionName NAME = SkyFunctionName.createHermetic("QUERY_PINNED_HTTP_FILE");

  private static final Label HTTP_BZL =
      Label.parseCanonicalUnchecked("@@bazel_tools//tools/build_defs/repo:http.bzl");
  // Binds the prototype to the trusted .19.2 definition and its transitive loads.
  private static final ByteString RECIPE_DIGEST = ByteString.copyFrom(HexFormat.of().parseHex(
      "328691865a362a3807e36edf3529831fa6432aacafa838cc11390cd539913128"));
  private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
  private static final Pattern PATH_COMPONENT = Pattern.compile("[a-zA-Z0-9_+][a-zA-Z0-9_.+-]*");
  private static final Set<String> RESERVED =
      Set.of("BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel", "REPO.bazel");
  private static final PathFragment FILE = PathFragment.create("file");
  private static final Value NOT_ELIGIBLE = new Value(ImmutableMap.of(), null);

  private final BlazeDirectories directories;
  private final PackageFactory packageFactory;

  public PinnedHttpFileQueryFunction(BlazeDirectories directories, PackageFactory packageFactory) {
    this.directories = directories;
    this.packageFactory = packageFactory;
  }

  static boolean containsPackage(PathFragment fragment) {
    return fragment.isEmpty() || fragment.equals(FILE);
  }

  static SkyKey key(RepositoryName name) {
    return Key.interner.intern(new Key(name));
  }

  private static final class Key
      extends AbstractSkyKey.WithCachedHashCode<RepositoryName> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(RepositoryName name) {
      super(name);
    }

    @Override
    public SkyFunctionName functionName() {
      return NAME;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  record Value(
      ImmutableMap<Label, Target> targets, @Nullable SourceFileDigestFunction.Value sourceDigest)
      implements SkyValue {}

  @Override
  @Nullable
  public Value compute(SkyKey skyKey, Environment env) throws InterruptedException {
    var fileSystem = directories.getOutputBase().getFileSystem();
    if (fileSystem.getDigestFunction() != DigestHashFunction.SHA256
        || fileSystem.mayBeCaseOrNormalizationInsensitive()) {
      return NOT_ELIGIBLE;
    }
    RepositoryName repository = (RepositoryName) skyKey.argument();
    RepoDefinitionValue definition;
    try {
      definition = (RepoDefinitionValue) env.getValueOrThrow(
          RepoDefinitionValue.key(repository), BzlLoadFailedException.class, ExternalDepsException.class);
    } catch (BzlLoadFailedException | ExternalDepsException e) {
      return NOT_ELIGIBLE;
    }
    if (definition == null) {
      return null;
    }
    if (!(definition instanceof RepoDefinitionValue.Found(RepoDefinition repo))
        || !repo.repoRule().id().bzlFileLabel().equals(HTTP_BZL)
        || !repo.repoRule().id().ruleName().equals("http_file")
        || !repo.repoRule().transitiveBzlDigest().equals(RECIPE_DIGEST)
        || !(repo.repoRule().impl() instanceof StarlarkFunction impl)
        || repo.originalName() == null
        || !PATH_COMPONENT.matcher(repo.originalName()).matches()
        || !(repo.getValue("downloaded_file_path") instanceof String path)
        || !supportedPath(path)
        || !(repo.getValue("sha256") instanceof String sha)
        || !SHA256.matcher(sha).matches()
        || !(repo.getValue("executable") instanceof Boolean executable)
        || !"".equals(repo.getValue("integrity"))
        || !"".equals(repo.getValue("netrc"))
        || !(repo.getValue("auth_patterns") instanceof Map<?, ?> authPatterns)
        || !authPatterns.isEmpty()
        || !httpUrls(repo)) {
      return NOT_ELIGIBLE;
    }

    // All three are regular dependencies. None requires the generated repository's directory.
    var builtins = (StarlarkBuiltinsValue) env.getValue(StarlarkBuiltinsValue.key());
    var mapping = (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(repository));
    var defaultVisibility = PrecomputedValue.DEFAULT_VISIBILITY.get(env);
    var visibilityPolicy = PrecomputedValue.CONFIG_SETTING_VISIBILITY_POLICY.get(env);
    if (env.valuesMissing()) {
      return null;
    }
    var semantics = builtins.starlarkSemantics;
    if (mapping.repositoryMapping() == null
        || semantics.get(BuildLanguageOptions.MAX_COMPUTATION_STEPS) != 0
        || (!semantics.getBool(BuildLanguageOptions.INCOMPATIBLE_DISABLE_BUILD_PRELUDE)
            && packageFactory.getRuleClassProvider().getPreludeLabel() != null)) {
      return NOT_ELIGIBLE;
    }
    var predeclared = builtins.predeclaredForBuild;
    if (semantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH).isEmpty()) {
      predeclared = ImmutableMap.copyOf(
          packageFactory.getRuleClassProvider().getBazelStarlarkEnvironment().getUninjectedBuildEnv());
    }
    if (!isNativeRule(predeclared.get("alias"), "alias")
        || !isNativeRule(predeclared.get("filegroup"), "filegroup")) {
      return NOT_ELIGIBLE;
    }

    String name = repo.originalName();
    String basename = PathFragment.create(path).getBaseName();
    Module recipe = impl.getModule();
    var globals = recipe.getGlobals();
    if (!(globals.get("_HTTP_FILE_ROOT_BUILD") instanceof String rootTemplate)
        || !(globals.get("_HTTP_FILE_ROOT_REPO_ALIAS") instanceof String repoAlias)
        || !(globals.get("_HTTP_FILE_ROOT_NAME_ALIAS") instanceof String basenameAlias)
        || !(globals.get("_HTTP_FILE_BUILD") instanceof String fileTemplate)) {
      return NOT_ELIGIBLE;
    }
    String rootSource = rootTemplate
        .replace("{repo_name_alias}",
            name.equals("file") ? "" : repoAlias.replace("{target_name}", Starlark.repr(name, semantics)))
        .replace("{downloaded_file_alias}", basename.equals("file") || basename.equals(name)
            ? "" : basenameAlias.replace("{target_name}", Starlark.repr(basename, semantics)));
    String fileSource = fileTemplate.replace("{path}", Starlark.repr(path, semantics));
    var root = Root.fromPath(directories.getOutputBase().getRelative("external").getChild(repository.getName()));
    var targets = ImmutableMap.<Label, Target>builder();
    try {
      for (boolean filePackage : new boolean[] {false, true}) {
        var fragment = filePackage ? FILE : PathFragment.EMPTY_FRAGMENT;
        var pkgId = PackageIdentifier.create(repository, fragment);
        var filename = RootedPath.toRootedPath(root, fragment.getRelative(filePackage ? "BUILD" : "BUILD.bazel"));
        var input = ParserInput.fromString(filePackage ? fileSource : rootSource, filename.asPath().getPathString());
        var module = Module.withPredeclared(semantics, predeclared);
        var program = Program.compileFile(StarlarkFile.parse(input, FileOptions.DEFAULT), module);
        var pkg = packageFactory.newPackageBuilder(
            pkgId, filename, mapping.associatedModuleName(), mapping.associatedModuleVersion(),
            semantics, mapping.repositoryMapping(), null, ImmutableMap.of(), visibilityPolicy,
            NoGlobs.INSTANCE);
        pkg.mergePackageArgsFrom(PackageArgs.builder().setDefaultVisibility(defaultVisibility));
        packageFactory.executeBuildFile(
            pkg, program, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
            predeclared, ImmutableMap.of(), semantics);
        Package built = pkg.build();
        if (built.containsErrors()) {
          return NOT_ELIGIBLE;
        }
        for (Rule rule : built.getTargets(Rule.class)) {
          targets.put(rule.getLabel(), rule);
        }
        if (filePackage && built.getTarget(path) instanceof InputFile leaf) {
          targets.put(leaf.getLabel(), leaf);
        }
      }
    } catch (NoSuchThingException | SyntaxError.Exception e) {
      return NOT_ELIGIBLE;
    }
    return new Value(targets.buildOrThrow(),
        SourceFileDigestFunction.Value.pinned(HexFormat.of().parseHex(sha), executable));
  }

  private static boolean isNativeRule(@Nullable Object value, String name) {
    return value instanceof RuleFunction rule
        && rule.getRuleClass().getName().equals(name)
        && !rule.getRuleClass().isStarlark();
  }

  private static boolean supportedPath(String path) {
    for (String component : path.split("/", -1)) {
      if (!PATH_COMPONENT.matcher(component).matches() || RESERVED.contains(component)) {
        return false;
      }
    }
    return !path.equals("file");
  }

  private static boolean httpUrls(RepoDefinition repo) {
    if (!(repo.getValue("url") instanceof String url)
        || !(repo.getValue("urls") instanceof List<?> urls)
        || (url.isEmpty() && urls.isEmpty())) {
      return false;
    }
    return (url.isEmpty() || httpUrl(url))
        && urls.stream().allMatch(item -> item instanceof String value && httpUrl(value));
  }

  private static boolean httpUrl(String value) {
    try {
      URI uri = URI.create(value);
      return uri.getHost() != null
          && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private enum NoGlobs implements Globber {
    INSTANCE;

    @Override
    public Token runAsync(List<String> includes, List<String> excludes, Operation operation, boolean allowEmpty)
        throws BadGlobException {
      if (!includes.isEmpty()) {
        throw new BadGlobException("http_file metadata does not support globbing");
      }
      return null;
    }

    @Override
    public List<String> fetchUnsorted(Token token) {
      return ImmutableList.of();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onCompletion() {}
  }
}
