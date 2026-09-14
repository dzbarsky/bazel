# Requested Bazel 9.3 backports

Base: `9.3.0-dzbarsky25` (`603c2ee3ef5d812fdc9ed0b7f7917fd7606883bc`).
These backports are included in `9.3.0-dzbarsky26` after rebasing onto
upstream `release-9.3.0`. The commit IDs below identify the pre-rebase commits.

## Changes

| Fork PR | Change | Release commits |
| --- | --- | --- |
| [#21](https://github.com/dzbarsky/bazel/pull/21) | Intern unresolved symlink destination strings. | `3acea0cb32` |
| [#23](https://github.com/dzbarsky/bazel/pull/23) | Share sorted field names in schemaless structs. | `5da613b1e7` |
| [#24](https://github.com/dzbarsky/bazel/pull/24) | Compare literal glob segments directly. | `ce78b55438` |
| [#25](https://github.com/dzbarsky/bazel/pull/25) | Compare and hash providers using shared field names and values. | `cb045cecb9`; includes #23 once |
| [#26](https://github.com/dzbarsky/bazel/pull/26) | Remove provider creation locations except for DefaultInfo. | `c41761cf38` |
| [#27](https://github.com/dzbarsky/bazel/pull/27) | Share paths in runfiles symlink entries. | `dc98e076c3` |
| [#28](https://github.com/dzbarsky/bazel/pull/28) | Store package targets in sorted lists; adapt the fork toolchain lookup. | `0a13c3da73`, `7195c0a0ab` |
| [#29](https://github.com/dzbarsky/bazel/pull/29) | Cache Starlark builtin ancestors. | `290abea0d6` |
| [#30](https://github.com/dzbarsky/bazel/pull/30) | Share frozen rule/macro attribute metadata. | `e77364d9d3` |
| [#31](https://github.com/dzbarsky/bazel/pull/31) | Store small schemaful provider values inline and test every layout. | `ccb9d3e4b1`, `e16e0965d3`; includes #26 once |
| [#32](https://github.com/dzbarsky/bazel/pull/32) | Represent repeatable native options with a private subtype. | `2eac5e9684` |

The shared-field-name backport keeps the provider constructors introduced by
#26. Commit `cf7aff0cdc` limits field-name-list identity assertions to
schemaless providers; schemaful providers retain value/order roundtrip checks.
The adapted comparison from #25 includes field names in equality and hashing.
No upstream provider-instance interning was added.

## Upstream submissions

All five pull requests target `bazelbuild/bazel:master`, were submitted by
`dzbarsky`, and contain commits attributed to David Zbarsky's dzbarsky account.

| Fork PR | Upstream PR |
| --- | --- |
| #21 | [#31122](https://github.com/bazelbuild/bazel/pull/31122) |
| #23 | [#31124](https://github.com/bazelbuild/bazel/pull/31124) |
| #24 | [#31123](https://github.com/bazelbuild/bazel/pull/31123) |
| #27 | [#31121](https://github.com/bazelbuild/bazel/pull/31121) |
| #32 | [#31120](https://github.com/bazelbuild/bazel/pull/31120) |

The upstream #23 adaptation also preserves the current lookup threshold and
checks every value for hashability. Its tests include a cyclic value in the
first of two fields. Upstream #32 keeps the current `getOptionsClass()` API.

## Validation

Linux x86_64 remote execution passed these complete combined-release targets:

- `//src/test/java/com/google/devtools/build/lib/packages:PackagesTests`
- `//src/test/java/net/starlark/java/eval:EvalTests`
- `//src/test/java/com/google/devtools/build/lib/analysis/config:BuildOptionDetailsTest`
- `//src/test/java/com/google/devtools/build/lib/rules/config:ConfigRulesTests`
- `//src/test/java/com/google/devtools/build/lib/skyframe/serialization:StarlarkInfoCodecTest`
- `//src/test/java/com/google/devtools/build/lib/rules/java:JavaInfoCodecTest`
- `//src/test/java/com/google/devtools/build/lib/analysis:SymbolicMacroTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:PackageFunctionTest`
- `//src/test/java/com/google/devtools/build/lib/rules/cpp:StarlarkCcCommonTest`
- `//src/test/java/com/google/devtools/build/lib/analysis:RunfilesTest`
- `//src/test/java/com/google/devtools/build/lib/analysis/starlark:UnresolvedSymlinkActionTest`
- `//src/test/java/com/google/devtools/build/lib/starlark:StarlarkRuleClassFunctionsTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe/toolchains:RegisteredToolchainsFunctionTest`
- `//src/test/java/com/google/devtools/build/lib/cmdline:LabelInternerIntegrationTest`

The selected `GlobTest`, `RecursiveGlobTest`, and `IgnoredSubdirectoriesTest`
cases passed in `VfsTests` and `CmdLineTests`. The full VFS suite encountered
45 permission-denial assertion failures. The exact same 45 test names fail
on unchanged release 25; no additional VFS failures were observed.

Each upstream branch passed its focused tests independently: configuration
suites for #32, RunfilesTest for #27, UnresolvedSymlinkActionTest for #21,
glob/ignored-directory tests for #24, and provider/codec/Starlark rule tests
for #23. Changed upstream Java lines were formatted with google-java-format
1.36.1. No combined performance measurement was run; individual PR benchmark
results must not be added together.
