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
package com.google.devtools.build.lib.bazel.repository;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.StringLiteral;

/**
 * Bootstraps the source root for {@code merged_git_repository}. Module resolution itself needs
 * packages (for includes and bootstrap repo rules), so the input of a merge must be known before
 * normal module evaluation. Only the literal declaration in the main MODULE.bazel is interpreted
 * here. Both module files and their fragments are evaluated by Bzlmod.
 */
public final class MergedGitRepository {
  private static final String DIRECTIVE = "merged_git_repository";
  private static final String MODULE = LabelConstants.MODULE_DOT_BAZEL_FILE_NAME.getPathString();
  private static final String LOCKFILE = LabelConstants.MODULE_LOCKFILE_NAME.getPathString();

  /** The combined package path and the pinned source of its additional module declarations. */
  public record Prepared(Path root, Path snapshot) {}

  private record Spec(String remote, String commit, ImmutableSet<PathFragment> preferImported) {}

  // Accessed only during package preparation with the command lock held.
  private Map<Directory, Listing> listings = Map.of();
  @Nullable private Spec lastSpec;

  @Nullable
  public Prepared prepare(
      Path workspace,
      Path outputBase,
      Map<String, String> repoEnv,
      boolean canFetch,
      EventHandler reporter)
      throws IOException, InterruptedException {
    Spec spec = readSpec(workspace);
    if (!Objects.equals(spec, lastSpec)) {
      listings = Map.of();
      lastSpec = spec;
    }
    if (spec == null) {
      return null;
    }
    PathFragment outputPath = outputBase.resolveSymbolicLinks().asFragment();
    PathFragment workspacePath = workspace.resolveSymbolicLinks().asFragment();
    if (outputPath.startsWith(workspacePath) || workspacePath.startsWith(outputPath)) {
      throw error("workspace and output base must be disjoint");
    }
    // This tree is private to this output base, and the command holds its lock. Publish a snapshot
    // only after a successful checkout. Keep the path of the projection stable across pin changes
    // so that symlinks created by repository rules continue to refer to the active sources.
    Path cache = outputBase.getChild("merged-git");
    ensureDirectory(cache);
    String key =
        Hashing.sha256().hashString(spec.remote() + "\n" + spec.commit(), ISO_8859_1).toString();
    Path snapshot = cache.getChild(key);
    if (!snapshot.isDirectory(Symlinks.NOFOLLOW)) {
      listings = Map.of();
      if (!canFetch) {
        throw error("commit " + spec.commit() + " is not cached and fetching is disabled");
      }
      reporter.handle(Event.info("Checking out merged Git source " + spec.commit()));
      Path staging = cache.getChild("staging");
      staging.deleteTree();
      checkout(spec, repoEnv, staging);
      if (!staging.getChild(MODULE).isFile(Symlinks.NOFOLLOW)) {
        throw error("expected a regular MODULE.bazel at commit " + spec.commit());
      }
      staging.renameTo(snapshot);
    }
    for (PathFragment preferred : spec.preferImported()) {
      if (!snapshot.getRelative(preferred).exists(Symlinks.NOFOLLOW)) {
        throw error("prefer_imported path is missing at " + spec.commit() + ": " + preferred);
      }
    }
    // Keep the basename of the remote checkout. Tools interpreting sibling-relative manifest
    // entries (../<remote>/...) can then find those paths in the same combined workspace.
    String name = PathFragment.create(spec.remote()).getBaseName().replaceFirst("\\.git$", "");
    Path views = cache.getChild("workspace");
    ensureDirectory(views);
    Path root = views.getChild(name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") ? name : "root");
    project(
        new Directory(workspace, snapshot, root, PathFragment.EMPTY_FRAGMENT),
        spec.preferImported());
    return new Prepared(root, snapshot);
  }

  @Nullable
  private static CallExpression findDeclaration(Path module) throws IOException {
    if (!module.isFile()) {
      return null;
    }
    String source = FileSystemUtils.readContent(module, ISO_8859_1);
    if (!source.contains(DIRECTIVE)) {
      return null;
    }
    StarlarkFile file = StarlarkFile.parse(ParserInput.fromString(source, module.getPathString()));
    if (!file.ok()) {
      // Let normal parsing report syntax errors.
      return null;
    }
    CallExpression declaration = null;
    boolean inHeader = true;
    for (var statement : file.getStatements()) {
      if (statement instanceof ExpressionStatement expr) {
        if (expr.getExpression() instanceof StringLiteral) {
          continue;
        }
        if (expr.getExpression() instanceof CallExpression call
            && call.getFunction() instanceof Identifier id) {
          if (id.getName().equals("module")) {
            continue;
          }
          if (id.getName().equals(DIRECTIVE)) {
            if (!inHeader) {
              throw error(
                  call.getStartLocation()
                      + ": expected a single declaration at the "
                      + "start of the root MODULE.bazel, after the optional module() call");
            }
            declaration = call;
          }
        }
      }
      inHeader = false;
    }
    return declaration;
  }

  @Nullable
  private static Spec readSpec(Path workspace) throws IOException {
    CallExpression declaration = findDeclaration(workspace.getChild(MODULE));
    if (declaration == null) {
      return null;
    }

    String remote = null;
    String commit = null;
    String commitFile = null;
    var prefer = ImmutableSet.<PathFragment>builder();
    Set<String> seen = new HashSet<>();
    for (var arg : declaration.getArguments()) {
      if (!(arg instanceof Argument.Keyword kw) || !seen.add(kw.getName())) {
        throw error(arg.getStartLocation() + ": expected distinct keyword arguments");
      }
      if (kw.getName().equals("prefer_imported") && kw.getValue() instanceof ListExpression list) {
        for (var item : list.getElements()) {
          if (!(item instanceof StringLiteral s)) {
            throw error("prefer_imported must be a list of literal relative paths");
          }
          PathFragment path = relativePath(s.getValue());
          if (path.getSegment(0).equals(MODULE) || path.getSegment(0).equals(LOCKFILE)) {
            throw error("the main repository owns " + path);
          }
          prefer.add(path);
        }
        continue;
      }
      if (!(kw.getValue() instanceof StringLiteral value)) {
        throw error(kw.getStartLocation() + ": " + kw.getName() + " must be a string literal");
      }
      switch (kw.getName()) {
        case "remote" -> remote = value.getValue();
        case "commit" -> commit = value.getValue();
        case "commit_file" -> commitFile = value.getValue();
        default -> throw error("unexpected argument: " + kw.getName());
      }
    }
    return new Spec(
        resolveRemote(workspace, remote),
        resolveCommit(workspace, commit, commitFile),
        prefer.build());
  }

  private static String resolveRemote(Path workspace, @Nullable String remote) throws IOException {
    if (remote == null || remote.isBlank() || remote.indexOf('\0') != -1) {
      throw error("expected a Git remote");
    }
    if (remote.startsWith("https://")
        || remote.startsWith("ssh://")
        || remote.startsWith("file://")) {
      return remote;
    }
    if (remote.contains(":")) {
      throw error("remote must be an https://, ssh:// or file:// URL or a local path");
    }
    return workspace.getRelative(remote).getPathString();
  }

  private static String resolveCommit(
      Path workspace, @Nullable String commit, @Nullable String commitFile) throws IOException {
    if ((commit == null) == (commitFile == null)) {
      throw error("specify exactly one of commit and commit_file");
    }
    if (commitFile != null) {
      Path pin = workspace.getRelative(relativePath(commitFile));
      if (!pin.resolveSymbolicLinks().startsWith(workspace.resolveSymbolicLinks())
          || !pin.isFile()) {
        throw error("commit_file must be a file in the main repository");
      }
      commit = FileSystemUtils.readContent(pin, ISO_8859_1).strip();
    }
    if (!commit.matches("[0-9a-f]{40}")) {
      throw error(
          "expected a full 40-character lowercase Git commit ID"
              + (commitFile == null ? "" : " in " + commitFile));
    }
    return commit;
  }

  private static PathFragment relativePath(String value) throws IOException {
    var path = PathFragment.create(value);
    if (path.isEmpty()
        || path.isAbsolute()
        || path.containsUplevelReferences()
        || !path.getPathString().equals(value)
        || value.indexOf('\0') != -1) {
      throw error("expected a normalized path relative to the workspace, got '" + value + "'");
    }
    return path;
  }

  private static void checkout(Spec spec, Map<String, String> repoEnv, Path staging)
      throws IOException, InterruptedException {
    Map<String, String> env = new HashMap<>(repoEnv);
    // As in git_worker.bzl, discard variables inherited from an enclosing Git invocation.
    for (String name :
        ImmutableList.of(
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_COMMON_DIR",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_NAMESPACE",
            "GIT_SHALLOW_FILE",
            "GIT_PREFIX")) {
      env.remove(name);
    }
    env.put("GIT_TERMINAL_PROMPT", "0");
    env.put("GIT_NO_REPLACE_OBJECTS", "1");
    String dest = staging.getPathString();
    if (PathFragment.isAbsolute(spec.remote())) {
      // Borrow objects only while exporting the snapshot. The sibling's branch, index and
      // working files are irrelevant. The resulting source tree has no alternates or .git.
      git(
          env,
          "clone",
          "--quiet",
          "--shared",
          "--no-checkout",
          "--single-branch",
          "--no-tags",
          "--",
          spec.remote(),
          dest);
    } else {
      git(env, "init", "--quiet", dest);
      git(
          env,
          "-C",
          dest,
          "fetch",
          "--quiet",
          "--no-tags",
          "--no-recurse-submodules",
          "--depth=1",
          "--",
          spec.remote(),
          spec.commit());
    }
    if (!git(env, "-C", dest, "cat-file", "-t", spec.commit()).strip().equals("commit")) {
      throw error(spec.commit() + " does not name a Git commit");
    }
    git(
        env,
        "-C",
        dest,
        "-c",
        "checkout.workers=4",
        "checkout",
        "--quiet",
        "--detach",
        spec.commit());
    staging.getChild(".git").deleteTree();
  }

  private static String git(Map<String, String> env, String... arguments)
      throws IOException, InterruptedException {
    var argv = ImmutableList.<String>builder().add("git").add(arguments).build();
    try {
      return new String(
          new Command(argv, env, null, Duration.ofMinutes(30), env).execute().getStdout(),
          ISO_8859_1);
    } catch (CommandException e) {
      throw error(e.getMessage());
    }
  }

  private record Directory(
      @Nullable Path workspace, @Nullable Path snapshot, Path root, PathFragment relative) {}

  private record Listing(
      @Nullable FileContentsProxy sourceStamp,
      FileContentsProxy viewStamp,
      ImmutableList<Directory> children) {}

  @Nullable
  private static FileContentsProxy stamp(@Nullable Path path) throws IOException {
    var stat = path == null ? null : path.statIfFound(Symlinks.NOFOLLOW);
    return stat == null ? null : FileContentsProxy.create(stat);
  }

  /**
   * Reconcile directory entries, leaving file contents and whole disjoint subtrees linked to their
   * owner. For an unchanged pin, only entry additions, removals and replacements in live
   * directories can change the link layout. Stat the expanded directories so a warm build can skip
   * readdir and readlink for every source file, even when the trees overlap extensively.
   */
  private void project(Directory root, Set<PathFragment> prefer)
      throws IOException, InterruptedException {
    var pending = new ArrayDeque<Directory>();
    var next = new HashMap<Directory, Listing>();
    pending.add(root);
    while (!pending.isEmpty()) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      Directory dir = pending.removeLast();
      Listing listing = listings.get(dir);
      var current = stamp(dir.workspace());
      if (listing == null
          || !Objects.equals(listing.sourceStamp(), current)
          || !listing.viewStamp().equals(stamp(dir.root()))) {
        var children = reconcile(dir, prefer);
        listing =
            new Listing(
                current, FileContentsProxy.create(dir.root().stat(Symlinks.NOFOLLOW)), children);
      }
      next.put(dir, listing);
      pending.addAll(listing.children());
    }
    listings = next;
  }

  private static ImmutableList<Directory> reconcile(Directory dir, Set<PathFragment> prefer)
      throws IOException {
    Path root = dir.root();
    Path workspace = dir.workspace();
    Path snapshot = dir.snapshot();
    PathFragment relative = dir.relative();
    ensureDirectory(root);
    var children = ImmutableList.<Directory>builder();
    Map<String, Dirent.Type> local = entries(workspace);
    Map<String, Dirent.Type> imported = entries(snapshot);
    Map<String, Dirent.Type> existing = entries(root);

    Set<String> names = new HashSet<>(local.keySet());
    names.addAll(imported.keySet());
    if (relative.isEmpty() && !local.containsKey(LOCKFILE)) {
      names.remove(LOCKFILE);
    }
    boolean expandDirectories = names.contains(LabelConstants.DONT_FOLLOW_SYMLINKS_FILE);
    for (String name : names) {
      Path child = root.getChild(name);
      Dirent.Type previous = existing.remove(name);
      PathFragment path = relative.getChild(name);
      Dirent.Type upper = prefer.contains(path) ? null : local.get(name);
      Dirent.Type lower = imported.get(name);
      Dirent.Type type = upper != null ? upper : lower;
      if (type == Dirent.Type.DIRECTORY
          && (expandDirectories
              || (upper == Dirent.Type.DIRECTORY && lower == Dirent.Type.DIRECTORY))) {
        ensureDirectory(child);
        children.add(
            new Directory(
                upper == Dirent.Type.DIRECTORY ? workspace.getChild(name) : null,
                lower == Dirent.Type.DIRECTORY ? snapshot.getChild(name) : null,
                child,
                path));
      } else {
        if (previous != null && previous != Dirent.Type.SYMLINK) {
          child.deleteTree();
        }
        Path source = (upper != null ? workspace : snapshot).getChild(name);
        PathFragment target =
            type == Dirent.Type.SYMLINK ? source.readSymbolicLink() : source.asFragment();
        if (relative.getRelative(target).containsUplevelReferences()) {
          target = source.getParentDirectory().getRelative(target).asFragment();
        }
        if (previous == null) {
          child.createSymbolicLink(target);
        } else {
          FileSystemUtils.ensureSymbolicLink(child, target);
        }
      }
    }
    for (String name : existing.keySet()) {
      root.getChild(name).deleteTree();
    }
    return children.build();
  }

  private static void ensureDirectory(Path path) throws IOException {
    if (!path.isDirectory(Symlinks.NOFOLLOW)) {
      path.delete();
      path.createDirectory();
    }
  }

  private static Map<String, Dirent.Type> entries(@Nullable Path path) throws IOException {
    if (path == null) {
      return Map.of();
    }
    var result = new HashMap<String, Dirent.Type>();
    for (Dirent entry : path.readdir(Symlinks.NOFOLLOW)) {
      result.put(entry.getName(), entry.getType());
    }
    return result;
  }

  private static IOException error(String message) {
    return new IOException(DIRECTIVE + ": " + message);
  }
}
