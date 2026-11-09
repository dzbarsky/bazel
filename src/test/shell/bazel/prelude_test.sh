#!/usr/bin/env bash
#
# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# An end-to-end test of the behavior of tools/build_rules/prelude_bazel.

# Load the test setup defined in the parent directory
CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

#### SETUP #############################################################

set -e

#### TESTS #############################################################

function test_prelude() {

  mkdir -p tools/build_rules
  touch tools/build_rules/BUILD

  cat > tools/build_rules/prelude_bazel << EOF
PRELUDE_VAR = 'from test_prelude'
EOF

  cat > BUILD << EOF
genrule(
  name = 'gr',
  srcs = [],
  outs = ['gr.out'],
  cmd = 'echo "%s" > \$(location gr.out)' % PRELUDE_VAR,
)
EOF

  bazel build --noincompatible_disable_build_prelude :gr >&"$TEST_log" 2>&1 || fail "build failed"

  output=$(cat bazel-genfiles/gr.out)
  check_eq "from test_prelude" "$output" "unexpected output in gr.out"
}

function test_prelude_external_repository() {
  cat > MODULE.bazel << EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(
  name = "imported_workspace",
  path = "$PWD/imported_workspace",
)
EOF

  mkdir -p tools/build_rules
  touch tools/build_rules/BUILD

  cat > tools/build_rules/prelude_bazel << EOF
PRELUDE_VAR = 'from test_prelude_external_repository, outer workspace'
EOF

  cat > BUILD << EOF
genrule(
  name = 'gr',
  srcs = [],
  outs = ['gr.out'],
  cmd = 'echo "outer %s" > \$(location gr.out)' % PRELUDE_VAR,
)
EOF

  mkdir -p imported_workspace
  touch imported_workspace/REPO.bazel

  mkdir -p imported_workspace/tools/build_rules
  touch imported_workspace/tools/build_rules/BUILD

  cat > imported_workspace/tools/build_rules/prelude_bazel << EOF
PRELUDE_VAR = 'from test_prelude_external_repository, inner workspace'
EOF

  cat > imported_workspace/BUILD << EOF
genrule(
  name = 'gr_inner',
  srcs = [],
  outs = ['gr_inner.out'],
  cmd = 'echo "inner %s" > \$(location gr_inner.out)' % PRELUDE_VAR,
)
EOF

  bazel build --noincompatible_disable_build_prelude :gr @imported_workspace//:gr_inner \
    >&"$TEST_log" 2>&1 || fail "build failed"

  output=$(cat bazel-genfiles/gr.out)
  check_eq "outer from test_prelude_external_repository, outer workspace" "$output" "unexpected output in gr.out"

  output=$(cat bazel-genfiles/external/+local_repository+imported_workspace/gr_inner.out)
  check_eq "inner from test_prelude_external_repository, inner workspace" "$output" "unexpected output in gr_inner.out"

  bazel query :gr >&"$TEST_log" 2>&1 \
    && fail "main repository still sees the prelude"
  expect_log "name 'PRELUDE_VAR' is not defined"
  bazel query @imported_workspace//:gr_inner \
    >&"$TEST_log" 2>&1 && fail "external repository still sees the prelude"
  expect_log "name 'PRELUDE_VAR' is not defined"

  bazel query --noincompatible_disable_build_prelude 'set(:gr @imported_workspace//:gr_inner)' \
    >&"$TEST_log" 2>&1 || fail "re-enabling the prelude failed"

  cat > defs.bzl <<'EOF'
TARGET_NAME = "gr"
EOF
  cat > BUILD <<'EOF'
load(":defs.bzl", "TARGET_NAME")
filegroup(name = TARGET_NAME)
EOF
  echo 'filegroup(name = "gr_inner")' > imported_workspace/BUILD
  echo 'fail("main prelude evaluated")' > tools/build_rules/prelude_bazel
  echo 'fail("external prelude evaluated")' > imported_workspace/tools/build_rules/prelude_bazel

  bazel query 'set(:gr @imported_workspace//:gr_inner)' \
    >&"$TEST_log" 2>&1 || fail "Bazel evaluated a disabled prelude"
}

function test_repository_marker_survives_prelude_toggle() {
  mkdir -p tools/build_rules
  touch tools/build_rules/BUILD
  echo 'PRELUDE_NAME = "prelude_target"' > tools/build_rules/prelude_bazel
  echo 'filegroup(name = PRELUDE_NAME)' > BUILD
  echo 'fixture payload' > payload.txt
  cat > MODULE.bazel <<'EOF'
fixture = use_extension("//:defs.bzl", "fixture")
use_repo(fixture, "fixture_dep")
EOF
  cat > defs.bzl <<'EOF'
_INPUT = Label("//:payload.txt")

def _repo_impl(ctx):
    print("prelude test repository executed")
    text = ctx.read(_INPUT)
    if text != ctx.attr.expected:
        fail("different inputs in the repository and extension")
    ctx.file("BUILD", "exports_files(['data.txt'])")
    ctx.file("data.txt", text)
    return ctx.repo_metadata(reproducible = True)

_repo = repository_rule(implementation = _repo_impl, attrs = {"expected": attr.string()})

def _extension(ctx):
    _repo(name = "fixture_dep", expected = ctx.read(_INPUT))
    return ctx.extension_metadata(reproducible = True)

fixture = module_extension(implementation = _extension)
EOF

  bazel query --repo_contents_cache= --noincompatible_disable_build_prelude \
    'set(:prelude_target @fixture_dep//:data.txt)' >&"$TEST_log" 2>&1 \
    || fail "prelude or repository query failed"
  expect_log "prelude test repository executed"

  bazel query --repo_contents_cache= @fixture_dep//:data.txt >&"$TEST_log" 2>&1 \
    || fail "repository query failed with the prelude disabled"
  expect_not_log "prelude test repository executed"

  bazel query :prelude_target >&"$TEST_log" 2>&1 \
    && fail "BUILD evaluated with the prelude disabled"
  expect_log "name 'PRELUDE_NAME' is not defined"
}

run_suite "prelude"
