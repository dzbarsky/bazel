# dzbarsky/bazel: master and rolling releases

`master` in `dzbarsky/bazel` is the maintained fork: all custom Bazel patches
must be present on top of a recent `bazelbuild/bazel:master`. Keep new fixes
on the fork's `master`, then cut each release branch from that commit.
The local remote names used here are `origin` for `bazelbuild/bazel` and
`fork` for `dzbarsky/bazel`; check `git remote -v` before pushing.

## Updating master and keeping the patches

1. Fetch `origin/master`, `fork/master`, and
   `fork/codex/bazel-9.4-jvm-buildbuddy-release`. Work in a clean worktree
   branched from `fork/master`. Never reset, stash, or switch a dirty user
   checkout just to update the fork's remote `master`.
2. Record `git merge-base origin/master fork/master`, the old `fork/master`
   SHA, and the old upstream base. Rebase the complete fork commit series onto
   the fetched `origin/master`. Inspect `git range-diff` between the old and
   new series; account for every omitted or changed commit. Only omit a patch
   if upstream provides the same behavior or the relevant upstream code was
   removed. Resolve BUILD dependencies against the current upstream targets.
3. Inspect new commits on the 9.4 fork release branch. Cherry-pick any
   functional fix that is missing from both upstream and the fork's rebased
   `master`. Prefer the upstream original when the 9.4 commit is a backport.
   Do not import 9.4 version stamps or changes that only repair a 9.4 BUILD
   label. Verify omitted fixes using ancestor checks, stable patch IDs, or the
   actual code and tests when APIs differ.
4. The last 9.4 commit fully accounted for on 2026-10-21 is
   `936b87fb56f12c7be262c4a5bbe853f4fc9bbd45`. The older patches were ported
   in the October 20 branch. Since that port, CrashContext redaction is upstream
   `4eb356e39d` and bundled JDK root removal is upstream `efaf902c25`; their
   9.4 backports need no duplicate cherry-picks. Update this baseline when
   further 9.4 commits are inspected.
5. Commit the rebase adaptations and any new patches on master. Test the
   changed behavior and the release scripts. Push the staging commit to
   `fork/master`. Use a normal push when it fast-forwards; when rebasing
   existing fork commits requires a rewrite, use
   `--force-with-lease=refs/heads/master:<recorded-old-fork-master-SHA>`.
   Never force-push to upstream.

## Cutting a rolling release

Use upstream's rolling-release names. Get the major from the root
`MODULE.bazel` (currently `10.0.0-prerelease`) and the release date in the
user's time zone, America/New_York. Each release on a date gets a new positive
integer revision. Check existing `fork` branches and tags first.

For example, the first release on 2026-10-21 is:

- Branch: `release-10.0.0-pre.20261021.1rc1`
- GitHub tag, release name, and compiled `--embed_label`:
  `10.0.0-pre.20261021.1`
- Consumer `.bazelversion` or `USE_BAZEL_VERSION`:
  `dzbarsky/10.0.0-pre.20261021.1`
- Bazelisk's fork URL:
  `https://github.com/dzbarsky/bazel/releases/download/10.0.0-pre.20261021.1/bazel-10.0.0-pre.20261021.1-<os>-<arch>[.exe]`

`rc1` belongs only in the branch name, as in upstream rolling branches.
Do not add a `dzbarsky` suffix to the version or stamp: `bazel_features`
compares `native.bazel_version` to upstream `-pre.YYYYMMDD.N` values and
Bazelisk validates the rolling syntax. Keep the root `MODULE.bazel` at the
upstream development version; the release build supplies `--embed_label`.

After updating and pushing master, cut the branch from that exact master SHA:

```sh
git switch -c release-10.0.0-pre.20261021.1rc1 fork/master
git push fork HEAD:refs/heads/release-10.0.0-pre.20261021.1rc1
```

The dated branch push starts `.github/workflows/build-and-publish-bazel.yml`.
A push to `master` does not publish. `.github/scripts/bazel-release-tag.sh`
must derive the exact tag from the branch and reject a mismatched manual
`release_tag`. The workflow tests release publication, builds and checks
Linux, macOS, and Windows on x86_64 and ARM64, validates fork regressions,
and publishes only when every required job passes.

Check the Actions run against the exact source SHA. After publication, use the
GitHub API to verify the release tag points to that SHA, the release is
published and immutable, and all 18 assets exist (six binaries, six SHA256
files, six attestation files). Verify Bazelisk downloads the fork binary and
reports the exact rolling version. Do not overwrite a published release or
move its tag. For another release on the same date, update master, then cut
`release-10.0.0-pre.20261021.2rc1` for `10.0.0-pre.20261021.2`.

# Bazel Codebase Guide for Gemini

Welcome to the Bazel repository. This document provides essential context and
workflows for working with the Bazel codebase.

## 🏗️ Building & Running Bazel

To build Bazel from source, use a pre-installed `bazel` or `bazelisk` binary.

*   **Fast Iteration Build (Recommended):** `bazel build //src:bazel-dev`
*   **Standard Build:** `bazel build //src:bazel`

### ⚡ Performance Tips

*   **Remote Execution:** If you have access to a Remote Build Execution (RBE)
cluster, use `--config=remote` to significantly speed up your builds:
    ```bash
    bazel build --config=remote //src:bazel-dev
    ```

### 🔄 Iterative Development Workflow

To test changes safely without interfering with your primary workspace,
use a separate binary and output base:

1.  **Build and Copy:**
    ```bash
    bazel build //src:bazel-dev && cp bazel-bin/src/bazel-dev /tmp/bazel
    ```
2.  **Run commands:**
    ```bash
    /tmp/bazel --output_base=/tmp/ob-dev <command>
    ```

**Note:** Using a custom `--output_base` (e.g., `/tmp/ob-dev`) is crucial to
avoid locking your main workspace server and ensures your development
environment remains isolated.

## 🧪 Testing

Tests are located primarily in `src/test`.

### Finding Relevant Tests
If you modify a file, find tests that transitively depend on it:
```bash
bazel query "rdeps(//src/test/..., path/to/file.java)"
```

### Running Tests

*   **Unit Tests:** Typically `java_test` targets.
*   **Integration Tests:**
    *   Java-based: Subclasses of `BuildIntegrationTestCase`.
    *   Shell-based: Located in `src/test/shell`, using a bash test framework.

## 📜 Architecture & Codebase

Bazel uses a **Client/Server** architecture. The client (C++) is a lightweight
wrapper that starts and communicates with a long-lived Java server.

*   **Skyframe:** The incremental evaluation framework. Most core logic is
implemented as `SkyFunction`s evaluating `SkyKey`s into `SkyValue`s.
*   **Starlark:** The configuration language used for `BUILD` and `.bzl` files.
*   **Loading/Analysis/Execution:** The three phases of a Bazel command.

For architectural details or introspection capabilities,
see: `docs/contribute/codebase.mdx`

## 🧹 Linting & Formatting

*   **Java:** Follows Google Java Style.
*   **Starlark:** Use `buildifier` for `BUILD` and `.bzl` files.
