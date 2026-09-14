#!/usr/bin/env bash
#
# Copyright 2026 The Bazel Authors. All rights reserved.
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

set -eu

CURRENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${CURRENT_DIR}/../integration_test_setup.sh" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }

JQ="$(rlocation "$JQ_RLOCATIONPATH")"
[[ -x "$JQ" ]] || fail "jq not found at $JQ"

function set_up() {
  control_dir="$(mktemp -d "${TEST_TMPDIR}/cache-probe.XXXXXXXX")"
  manifest="${control_dir}/manifest.json"
  marker="${control_dir}/executed"
}

function write_build_fixture() {
  mkdir -p pkg
  echo original > pkg/input.txt
  cat > pkg/BUILD <<EOF
genrule(
    name = "producer",
    srcs = ["input.txt"],
    outs = ["produced.txt"],
    cmd = "echo producer >> '${marker}'; cat \$< > \$@",
)
genrule(
    name = "consumer",
    srcs = [":produced.txt"],
    outs = ["consumed.txt"],
    cmd = "echo consumer >> '${marker}'; cat \$< > \$@",
)
filegroup(name = "wrapper", srcs = [":produced.txt"])
alias(name = "aliased", actual = ":producer")
filegroup(name = "empty")
EOF
}

function write_test_fixture() {
  local exit_code="${1:-0}"
  local shards="${2:-0}"
  add_rules_shell MODULE.bazel
  mkdir -p pkg
  cat > pkg/test.sh <<EOF
#!/usr/bin/env bash
set -eu
if [[ -n "\${TEST_SHARD_STATUS_FILE:-}" ]]; then
  touch "\${TEST_SHARD_STATUS_FILE}"
fi
echo test >> '${marker}'
exit ${exit_code}
EOF
  chmod +x pkg/test.sh
  cat > pkg/BUILD <<EOF
load("@rules_shell//shell:sh_test.bzl", "sh_test")

sh_test(
    name = "test",
    srcs = ["test.sh"],
    shard_count = ${shards},
)
test_suite(name = "suite", tests = [":test"])
EOF
}

function run_probe() {
  local command="$1"
  shift
  # Standalone execution makes an accidentally executed workload observable
  # outside its outputs. The outer integration test keeps its own sandbox.
  bazel "$command" --spawn_strategy=standalone --test_strategy=standalone \
    --keep_going --test_keep_going \
    --experimental_cache_probe_output="$manifest" "$@" >& "$TEST_log" \
    || fail "Cache probe failed"
  assert_exists "$manifest"
  "$JQ" -e '
    .schema_version == 1 and .complete == true and .target_kind == "all" and
    (.total_targets | type == "number") and
    (.affected | type == "array") and
    (.excluded_targets | type == "array") and
    (has("configurations") | not) and
    (all(.affected[]; has("caused_by") | not)) and
    ([.affected[].label] | length == (unique | length))
  ' "$manifest" > /dev/null || fail "Invalid complete cache-probe manifest"
}

function assert_affected() {
  local actual
  local expected
  actual="$("$JQ" -r '.affected[].label' "$manifest" | LC_ALL=C sort)"
  expected="$(printf '%s\n' "$@" | LC_ALL=C sort)"
  assert_equals "$expected" "$actual"
}

function test_cold_probe_reports_generated_dependents_without_execution() {
  write_build_fixture
  run_probe build //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_affected //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_not_exists "$marker"
  assert_not_exists bazel-bin/pkg/produced.txt
  assert_not_exists bazel-bin/pkg/consumed.txt
  expect_not_log '^ERROR:'
}

function test_warm_build_reuses_success() {
  write_build_fixture
  bazel build --spawn_strategy=standalone //pkg:consumer >& "$TEST_log" \
    || fail "Initial build failed"
  assert_equals $'producer\nconsumer' "$(cat "$marker")"
  rm "$marker"

  run_probe build //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_affected
  assert_not_exists "$marker"
}

function test_probe_miss_does_not_poison_subsequent_build() {
  write_build_fixture
  run_probe build //pkg:consumer
  assert_affected //pkg:consumer
  assert_not_exists "$marker"

  bazel build --spawn_strategy=standalone //pkg:consumer >& "$TEST_log" \
    || fail "Build after probe miss failed"
  assert_equals $'producer\nconsumer' "$(cat "$marker")"
  rm "$marker"

  run_probe build //pkg:consumer
  assert_affected
  assert_not_exists "$marker"
}

function test_empty_probe_output_restores_ordinary_build() {
  write_build_fixture
  bazel build --spawn_strategy=standalone \
    --experimental_cache_probe_output="$manifest" --experimental_cache_probe_output= \
    //pkg:producer >& "$TEST_log" || fail "Empty probe override prevented a normal build"
  assert_equals producer "$(cat "$marker")"
  assert_not_exists "$manifest"
}

function test_changed_input_invalidates_warm_success() {
  write_build_fixture
  bazel build --spawn_strategy=standalone //pkg:consumer >& "$TEST_log" \
    || fail "Initial build failed"
  rm "$marker"
  echo changed-input > pkg/input.txt

  run_probe build //pkg:consumer
  assert_affected //pkg:consumer
  assert_not_exists "$marker"
}

function test_test_command_includes_non_test_roots() {
  write_build_fixture
  run_probe test --build_tests_only=false //pkg/...
  assert_affected //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_equals 5 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function test_build_tests_only_fails_without_manifest() {
  write_test_fixture
  printf '{}\n' > "$manifest"
  if bazel test --build_tests_only --keep_going --test_keep_going \
      --experimental_cache_probe_output="$manifest" \
      //pkg:suite >& "$TEST_log"; then
    fail "Cache probe accepted --build_tests_only"
  fi
  expect_log 'Cache probing requires --build_tests_only=false'
  assert_not_exists "$manifest"
  assert_not_exists "$marker"
}

function test_probe_without_keep_going_fails_without_manifest() {
  write_build_fixture
  if bazel build --experimental_cache_probe_output="$manifest" //pkg:producer \
      >& "$TEST_log"; then
    fail "Cache probe accepted a build without --keep_going"
  fi
  expect_log 'Cache probing requires --keep_going and --test_keep_going'
  assert_not_exists "$manifest"
  assert_not_exists "$marker"
}

function test_negative_pattern_excludes_root_but_not_required_dependency() {
  write_build_fixture
  run_probe build -- //pkg/... -//pkg:producer
  assert_affected //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_equals 4 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function test_cold_test_and_suite_are_selected_without_execution() {
  write_test_fixture
  run_probe test --build_tests_only=false //pkg:test //pkg:suite
  assert_affected //pkg:test //pkg:suite
  assert_equals 2 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function test_uncached_build_input_selects_test_without_execution() {
  write_test_fixture
  cat >> pkg/BUILD <<'EOF'
genrule(name = "test_data", outs = ["generated.txt"], cmd = "exit 99")
sh_test(name = "generated_input_test", srcs = ["test.sh"], data = [":test_data"])
EOF

  run_probe test //pkg:generated_input_test
  assert_affected //pkg:generated_input_test
  assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists bazel-bin/pkg/generated.txt
  assert_not_exists "$marker"
}

function test_warm_passing_test_reuses_success() {
  write_test_fixture
  bazel test --spawn_strategy=standalone --test_strategy=standalone \
    //pkg:test >& "$TEST_log" || fail "Initial test failed"
  assert_equals test "$(cat "$marker")"
  rm "$marker"

  run_probe test //pkg:test
  assert_affected
  assert_not_exists "$marker"
}

function assert_failed_cached_test_is_selected() {
  local cache_policy="$1"
  local exit_code=0
  write_test_fixture 1
  bazel test --spawn_strategy=standalone --test_strategy=standalone \
    --cache_test_results="$cache_policy" //pkg:test >& "$TEST_log" \
    || exit_code=$?
  assert_equals 3 "$exit_code"
  assert_equals test "$(cat "$marker")"
  rm "$marker"

  run_probe test --cache_test_results="$cache_policy" //pkg:test
  assert_affected //pkg:test
  assert_not_exists "$marker"
}

function test_failed_cached_test_auto_is_selected() {
  assert_failed_cached_test_is_selected auto
}

function test_failed_cached_test_yes_is_selected() {
  assert_failed_cached_test_is_selected yes
}

function test_no_cache_test_is_selected_without_reexecution() {
  write_test_fixture
  bazel test --spawn_strategy=standalone --test_strategy=standalone \
    --nocache_test_results //pkg:test >& "$TEST_log" \
    || fail "Initial test failed"
  assert_equals test "$(cat "$marker")"
  rm "$marker"

  run_probe test --nocache_test_results //pkg:test
  assert_affected //pkg:test
  assert_not_exists "$marker"
}

function test_warm_sharded_test_reuses_every_required_result() {
  write_test_fixture 0 2
  bazel test --spawn_strategy=standalone --test_strategy=standalone \
    //pkg:test >& "$TEST_log" || fail "Initial sharded test failed"
  assert_equals $'test\ntest' "$(cat "$marker")"
  rm "$marker"

  run_probe test //pkg:test
  assert_affected
  assert_not_exists "$marker"
}

function test_warm_repeated_test_preserves_flaky_aggregate_success() {
  write_test_fixture
  cat > pkg/test.sh <<EOF
#!/usr/bin/env bash
set -eu
echo test >> '${marker}'
[[ "\$(wc -l < '${marker}')" -eq 1 ]]
EOF
  bazel test --spawn_strategy=standalone --test_strategy=standalone \
    --jobs=1 --runs_per_test=2 --runs_per_test_detects_flakes \
    --cache_test_results=yes //pkg:test >& "$TEST_log" \
    || fail "Repeated test should have aggregate FLAKY success"
  expect_log "FLAKY"
  assert_equals $'test\ntest' "$(cat "$marker")"
  rm "$marker"

  run_probe test --jobs=1 --runs_per_test=2 --runs_per_test_detects_flakes \
    --cache_test_results=yes //pkg:test
  assert_affected
  assert_not_exists "$marker"
}

function test_analysis_error_does_not_publish_complete_manifest() {
  write_build_fixture
  run_probe build //pkg:empty
  assert_affected
  mkdir -p broken
  cat > broken/BUILD <<'EOF'
genrule(name = "broken", srcs = ["//missing:input"], outs = ["out"], cmd = "exit 1")
EOF

  bazel build --keep_going --test_keep_going --spawn_strategy=standalone \
    --experimental_cache_probe_output="$manifest" \
    //pkg:producer //broken:broken >& "$TEST_log" \
    && fail "Probe with an analysis error unexpectedly succeeded"
  assert_not_exists "$manifest"
  assert_not_exists "$marker"
}

function test_filtered_positive_suites_do_not_load_members() {
  write_test_fixture
  mkdir -p filtered_manual filtered_noci
  echo 'fail("FILTERED_SUITE_MEMBER_WAS_LOADED")' > filtered_manual/BUILD
  echo 'fail("FILTERED_SUITE_MEMBER_WAS_LOADED")' > filtered_noci/BUILD
  cat >> pkg/BUILD <<'EOF'
test_suite(name = "manual_suite", tests = ["//filtered_manual:test"], tags = ["manual"])
test_suite(name = "noci_suite", tests = ["//filtered_noci:test"], tags = ["noci"])
EOF

  run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual \
    //pkg/... //pkg:manual_suite
  assert_affected //pkg:test //pkg:suite
  assert_equals 2 "$("$JQ" -r .total_targets "$manifest")"
  expect_not_log "FILTERED_SUITE_MEMBER_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_negative_tagged_suite_still_subtracts_tests() {
  write_test_fixture 1
  cat >> pkg/BUILD <<'EOF'
test_suite(name = "negative_suite", tests = [":suite"], tags = ["manual", "noci"])
EOF
  bazel build --spawn_strategy=standalone //pkg:test >& "$TEST_log" \
    || fail "Initial build failed"

  run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual \
    -- //pkg:test -//pkg:negative_suite
  assert_affected
  assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function test_negative_suite_with_excluded_member_fails_without_manifest() {
  write_test_fixture
  mkdir -p ignored
  echo 'fail("EXCLUDED_BUILD_WAS_LOADED")' > ignored/BUILD
  cat >> pkg/BUILD <<'EOF'
sh_test(name = "excluded_test", srcs = ["test.sh"], data = ["//ignored:input"])
test_suite(name = "mixed_suite", tests = [":test", ":excluded_test"])
EOF

  bazel test --build_tests_only=false --keep_going --test_keep_going \
    --spawn_strategy=standalone --test_strategy=standalone \
    --experimental_cache_probe_output="$manifest" \
    --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual \
    -- //pkg:test -//pkg:mixed_suite >& "$TEST_log" \
    && fail "Probe silently ignored an excluded negative test suite"
  assert_not_exists "$manifest"
  expect_log "Cannot subtract excluded test suite //pkg:mixed_suite"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_negative_suite_in_excluded_package_fails_without_manifest() {
  write_test_fixture
  mkdir -p ignored
  cat > ignored/BUILD <<'EOF'
print("EXCLUDED_BUILD_WAS_LOADED")
test_suite(name = "suite", tests = ["//pkg:test"])
EOF

  bazel test --build_tests_only=false --keep_going --test_keep_going \
    --spawn_strategy=standalone --test_strategy=standalone \
    --experimental_cache_probe_output="$manifest" \
    --experimental_cache_probe_exclude_deps=//ignored \
    -- //pkg:test -//ignored:suite >& "$TEST_log" \
    && fail "Probe silently ignored a negative suite in an excluded package"
  assert_not_exists "$manifest"
  expect_log "Cannot subtract excluded target pattern -//ignored:suite"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_filtered_suite_members_do_not_exclude_good_members_or_load_dependencies() {
  write_test_fixture
  mkdir -p ignored filtered_manual filtered_noci
  local dependency
  for dependency in ignored filtered_manual filtered_noci; do
    echo 'fail("FILTERED_SUITE_DEPENDENCY_WAS_LOADED")' > "${dependency}/BUILD"
  done
  cat >> pkg/BUILD <<'EOF'
sh_test(name = "personal_test", srcs = ["test.sh"], data = ["//ignored:input"], tags = ["noci"])
sh_test(name = "manual_test", srcs = ["test.sh"], data = ["//filtered_manual:input"], tags = ["manual"])
sh_test(name = "noci_test", srcs = ["test.sh"], data = ["//filtered_noci:input"], tags = ["noci"])
test_suite(name = "explicit_suite", tests = [":test", ":personal_test", ":manual_test", ":noci_test"])
test_suite(name = "implicit_suite")
EOF

  local suite
  for suite in explicit_suite implicit_suite; do
    run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
      --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual "//pkg:${suite}"
    assert_affected "//pkg:${suite}"
    assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"
    expect_not_log "FILTERED_SUITE_DEPENDENCY_WAS_LOADED"
  done
  assert_not_exists "$marker"
}

function test_suite_tags_filter_direct_members_but_not_nested_suites() {
  write_test_fixture
  mkdir -p ignored
  echo 'fail("FILTERED_SUITE_DEPENDENCY_WAS_LOADED")' > ignored/BUILD
  cat >> pkg/BUILD <<'EOF'
sh_test(name = "nested_test", srcs = ["test.sh"], tags = ["skip"])
sh_test(name = "filtered_test", srcs = ["test.sh"], data = ["//ignored:input"], tags = ["skip"])
test_suite(name = "nested_suite", tests = [":nested_test"])
test_suite(name = "selected_suite", tests = [":test", ":filtered_test", ":nested_suite"], tags = ["-skip"])
EOF
  bazel test --spawn_strategy=standalone --test_strategy=standalone //pkg:test >& "$TEST_log" \
    || fail "Initial test failed"
  rm -f "$marker"

  run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual //pkg:selected_suite
  assert_affected //pkg:selected_suite
  assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"
  expect_not_log "FILTERED_SUITE_DEPENDENCY_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_expanded_suite_later_required_by_non_suite_rule_keeps_member_dependencies() {
  write_test_fixture
  mkdir -p ignored
  echo 'fail("EXCLUDED_BUILD_WAS_LOADED")' > ignored/BUILD
  cat >> pkg/BUILD <<'EOF'
sh_test(name = "manual_test", srcs = ["test.sh"], data = ["//ignored:input"], tags = ["manual"])
test_suite(name = "required_suite", tests = [":test", ":manual_test"])
filegroup(name = "suite_bridge", srcs = [":required_suite"], testonly = True)
filegroup(name = "suite_consumer", srcs = [":suite_bridge"], testonly = True)
EOF

  # The bridge reaches required_suite after its initial filtered expansion.
  run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual \
    //pkg:required_suite //pkg:suite_consumer
  assert_affected
  assert_equals 0 "$("$JQ" -r .total_targets "$manifest")"
  "$JQ" -e '.excluded_targets | index("//pkg:suite_consumer") != null' "$manifest" > /dev/null \
    || fail "Required suite dependency did not exclude its consumer"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_filtered_roots_do_not_load_dependencies() {
  write_build_fixture
  mkdir -p filtered_manual filtered_noci filtered_negative
  local dependency
  for dependency in filtered_manual filtered_noci filtered_negative; do
    echo 'fail("FILTERED_DEPENDENCY_WAS_LOADED")' > "${dependency}/BUILD"
  done
  cat >> pkg/BUILD <<'EOF'
filegroup(name = "manual", srcs = ["//filtered_manual:input"], tags = ["manual"])
filegroup(name = "noci", srcs = ["//filtered_noci:input"], tags = ["noci"])
filegroup(name = "removed", srcs = ["//filtered_negative:input"])
config_setting(name = "implicit_manual", define_values = {"probe": "yes"})
EOF

  run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual --test_tag_filters=-noci,-manual \
    -- //pkg/... -//pkg:removed
  assert_affected //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_equals 5 "$("$JQ" -r .total_targets "$manifest")"
  "$JQ" -e '.excluded_targets | index("//pkg:implicit_manual") != null' "$manifest" > /dev/null \
    || fail "Implicitly manual configuration target was included in the wildcard"
  expect_not_log "FILTERED_DEPENDENCY_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_unfiltered_probe_preserves_explicit_manual_root() {
  write_build_fixture
  cat >> pkg/BUILD <<'EOF'
filegroup(name = "manual", srcs = [":produced.txt"], tags = ["manual"])
EOF

  # The normal full-repo probe excludes manual; this fixture checks the bare CLI's
  # explicit-target behavior without executing the manual target's actions.
  run_probe build --build_tag_filters=-noci --test_tag_filters=-noci,-manual \
    -- //pkg:manual //pkg/...
  assert_affected //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased //pkg:manual
  assert_equals 6 "$("$JQ" -r .total_targets "$manifest")"

  run_probe build --build_tag_filters=-noci --test_tag_filters=-noci,-manual //pkg/...
  assert_affected //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_equals 5 "$("$JQ" -r .total_targets "$manifest")"
}

function test_changed_dependency_invalidates_probe_exclusions() {
  write_build_fixture
  mkdir -p bridge mid ignored
  echo 'fail("EXCLUDED_BUILD_WAS_LOADED")' > ignored/BUILD
  echo input > mid/input.txt
  echo 'filegroup(name = "leaf", srcs = ["input.txt"], visibility = ["//visibility:public"])' > mid/BUILD
  echo 'filegroup(name = "changing", srcs = ["//mid:leaf"])' > bridge/BUILD

  run_probe build --experimental_cache_probe_exclude_deps=//ignored //bridge:changing
  assert_affected
  assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"

  echo 'filegroup(name = "leaf", srcs = ["//ignored:input"], visibility = ["//visibility:public"])' > mid/BUILD
  run_probe build --experimental_cache_probe_exclude_deps=//ignored //bridge:changing
  assert_affected
  assert_equals 0 "$("$JQ" -r .total_targets "$manifest")"
  "$JQ" -e '.excluded_targets | index("//bridge:changing") != null' "$manifest" > /dev/null \
    || fail "Changed dependency did not update excluded roots"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_excluded_package_dependents_do_not_load_excluded_build_file() {
  write_build_fixture
  mkdir -p ignored blocked
  echo 'fail("EXCLUDED_BUILD_WAS_LOADED")' > ignored/BUILD
  cat > blocked/BUILD <<'EOF'
genrule(
    name = "manual_source",
    srcs = ["//ignored:input"],
    outs = ["generated.txt"],
    cmd = "exit 1",
    tags = ["manual"],
)
filegroup(name = "consumer", srcs = [":generated.txt"])
alias(name = "aliased", actual = ":consumer")
EOF

  run_probe build --experimental_cache_probe_exclude_deps=//ignored \
    //pkg/... //blocked/... //ignored/...
  assert_affected //pkg:producer //pkg:consumer //pkg:wrapper //pkg:aliased
  assert_equals 5 "$("$JQ" -r .total_targets "$manifest")"
  "$JQ" -e '.excluded_targets | index("//blocked:consumer") != null and
      index("//blocked:aliased") != null' "$manifest" > /dev/null \
    || fail "Excluded dependency roots missing from manifest"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_tag_filters_exclude_suite_members_but_keep_required_dependencies() {
  write_test_fixture
  cat > pkg/bad.bzl <<'EOF'
def _fail(ctx):
    fail("EXCLUDED_RULE_WAS_ANALYZED")

bad_test = rule(implementation = _fail, test = True)
bad_rule = rule(implementation = _fail)
EOF
  cat >> pkg/BUILD <<'EOF'
load(":bad.bzl", "bad_rule", "bad_test")

bad_test(name = "noci_test", tags = ["noci"])
bad_rule(name = "manual_rule", tags = ["manual"])
test_suite(name = "filtered_suite", tests = [":test", ":noci_test"])
genrule(name = "manual_dep", outs = ["manual.txt"], cmd = "echo dep > $@", tags = ["manual"])
genrule(name = "consumer", srcs = [":manual.txt"], outs = ["out"], cmd = "cat $< > $@")
EOF

  run_probe test --build_tests_only=false --experimental_cache_probe_exclude_deps=//ignored \
    --build_tag_filters=-noci,-manual \
    --test_tag_filters=-noci,-manual //pkg/...
  assert_affected //pkg:test //pkg:suite //pkg:filtered_suite //pkg:consumer
  assert_equals 4 "$("$JQ" -r .total_targets "$manifest")"
  expect_not_log "EXCLUDED_RULE_WAS_ANALYZED"
  assert_not_exists "$marker"
}

function test_excluded_dependency_through_external_rule_does_not_load_package() {
  mkdir -p root ignored unreachable "${control_dir}/bridge"
  echo 'fail("EXCLUDED_BUILD_WAS_LOADED")' > ignored/BUILD
  echo 'fail("UNREACHABLE_BUILD_WAS_LOADED")' > unreachable/BUILD
  echo 'module(name = "bridge")' > "${control_dir}/bridge/MODULE.bazel"
  cat > "${control_dir}/bridge/BUILD" <<'EOF'
filegroup(
    name = "bridge",
    srcs = ["@@//ignored:input"],
    visibility = ["//visibility:public"],
)
filegroup(name = "unreachable", srcs = ["@@//unreachable:input"])
EOF
  cat >> MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "bridge", path = "${control_dir}/bridge")
EOF
  cat > root/BUILD <<'EOF'
alias(name = "root", actual = "@bridge//:bridge")
EOF

  run_probe build --experimental_cache_probe_exclude_deps=//ignored \
    //root/... //ignored/...
  assert_affected
  assert_equals 0 "$("$JQ" -r .total_targets "$manifest")"
  "$JQ" -e '.excluded_targets | index("//root:root") != null' "$manifest" > /dev/null \
    || fail "Root with an external path to the excluded package was not excluded"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  expect_not_log "UNREACHABLE_BUILD_WAS_LOADED"
}

function test_validation_aspect_miss_selects_successfully_built_target() {
  mkdir -p pkg
  cat > pkg/validation.bzl <<'EOF'
def _validated_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".out")
    ctx.actions.write(output, "main output")
    validation = ctx.actions.declare_file(ctx.label.name + ".validation")
    ctx.actions.run_shell(
        outputs = [validation],
        arguments = [ctx.attr.marker, validation.path],
        command = 'echo validation >> "$1"; touch "$2"',
    )
    return [
        DefaultInfo(files = depset([output])),
        OutputGroupInfo(_validation = depset([validation])),
    ]

validated = rule(
    implementation = _validated_impl,
    attrs = {"marker": attr.string()},
)
EOF
  cat > pkg/BUILD <<EOF
load(":validation.bzl", "validated")

validated(name = "checked", marker = "${marker}")
EOF

  run_probe build --experimental_use_validation_aspect //pkg:checked
  assert_affected //pkg:checked
  assert_exists bazel-bin/pkg/checked.out
  assert_not_exists "$marker"

  bazel build --spawn_strategy=standalone --experimental_use_validation_aspect \
    //pkg:checked >& "$TEST_log" || fail "Initial validation build failed"
  assert_equals validation "$(cat "$marker")"
  rm "$marker"

  run_probe build --experimental_use_validation_aspect //pkg:checked
  assert_affected
  assert_not_exists "$marker"
}

function test_test_command_publishes_successful_bep_completion() {
  write_build_fixture
  local bep="${control_dir}/bep.json"
  run_probe test --build_event_json_file="$bep" //pkg:empty
  assert_affected
  "$JQ" -se '
    [.[] | select(.id.buildFinished != null)] |
    length == 1 and .[0].finished.exitCode.name == "SUCCESS" and
    .[0].finished.overallSuccess == true
  ' "$bep" > /dev/null || fail "Probe test command omitted successful BEP completion"
  assert_not_exists "$marker"
}

function test_target_pattern_file_error_removes_previous_manifest() {
  write_build_fixture
  local command
  for command in build test; do
    run_probe "$command" //pkg:empty
    assert_affected

    bazel "$command" --keep_going --test_keep_going \
      --experimental_cache_probe_output="$manifest" \
      --target_pattern_file="${control_dir}/missing-patterns" >& "$TEST_log" \
      && fail "Probe with a missing target-pattern file unexpectedly succeeded"
    assert_not_exists "$manifest"
    assert_not_exists "$marker"
  done
}

function test_failed_target_pattern_is_not_hidden_by_cache_miss() {
  write_build_fixture
  mkdir -p mid
  echo 'filegroup(name = "leaf", srcs = ["input.txt"], visibility = ["//visibility:public"])' > mid/BUILD
  echo input > mid/input.txt
  echo 'filegroup(name = "with_mid", srcs = ["//mid:leaf"])' >> pkg/BUILD
  run_probe build //pkg:empty
  assert_affected

  bazel build --keep_going --test_keep_going \
    --experimental_cache_probe_output="$manifest" \
    --experimental_cache_probe_exclude_deps=//ignored \
    //pkg:with_mid //missing:target >& "$TEST_log" \
    && fail "Probe with a failed target pattern unexpectedly succeeded"
  expect_log "Skipping '//missing:target'"
  assert_not_exists "$manifest"
  assert_not_exists "$marker"
}

function test_warm_alias_and_nested_suites_account_for_all_roots() {
  write_test_fixture
  cat >> pkg/BUILD <<'EOF'
alias(name = "test_alias", actual = ":test")
test_suite(name = "nested_suite", tests = [":suite"])
test_suite(name = "empty_suite", tests = [], tags = ["empty"])
EOF
  bazel test --spawn_strategy=standalone --test_strategy=standalone \
    //pkg:test >& "$TEST_log" || fail "Initial test failed"
  assert_equals test "$(cat "$marker")"
  rm "$marker"

  run_probe test //pkg:test_alias //pkg:nested_suite //pkg:empty_suite
  assert_affected
  assert_equals 3 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function check_inactive_external_dependency() {
  local available="$1"
  write_build_fixture
  if [[ "$available" == yes ]]; then
    mkdir -p "${control_dir}/inactive"
    echo 'module(name = "inactive")' > "${control_dir}/inactive/MODULE.bazel"
    echo 'fail("INACTIVE_EXTERNAL_BUILD_ERROR")' > "${control_dir}/inactive/BUILD"
  fi
  cat >> MODULE.bazel <<EOF
local_repository = use_repo_rule("@bazel_tools//tools/build_defs/repo:local.bzl", "local_repository")
local_repository(name = "inactive", path = "${control_dir}/inactive")
EOF
  cat >> pkg/BUILD <<'EOF'
config_setting(name = "inactive", values = {"define": "inactive=true"})
filegroup(
    name = "conditional",
    srcs = select({
        ":inactive": ["@inactive//:never"],
        "//conditions:default": [":produced.txt"],
    }),
)
EOF

  run_probe build --experimental_cache_probe_exclude_deps=//ignored //pkg:conditional
  assert_affected //pkg:conditional
  assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function test_inactive_external_broken_build_does_not_fail_probe() {
  check_inactive_external_dependency yes
}

function test_inactive_external_unavailable_repository_does_not_fail_probe() {
  check_inactive_external_dependency no
}

function test_unavailable_repository_retry_updates_excluded_root() {
  mkdir -p ignored
  echo 'fail("EXCLUDED_BUILD_WAS_LOADED")' > ignored/BUILD
  check_inactive_external_dependency no

  mkdir -p "${control_dir}/inactive"
  echo 'module(name = "inactive")' > "${control_dir}/inactive/MODULE.bazel"
  cat > "${control_dir}/inactive/BUILD" <<'EOF'
filegroup(
    name = "never",
    srcs = ["@@//ignored:input"],
    visibility = ["//visibility:public"],
)
EOF

  run_probe build --experimental_cache_probe_exclude_deps=//ignored //pkg:conditional
  assert_affected
  assert_equals 0 "$("$JQ" -r .total_targets "$manifest")"
  "$JQ" -e '.excluded_targets | index("//pkg:conditional") != null' "$manifest" > /dev/null \
    || fail "Recovered repository dependency did not exclude its root"
  expect_not_log "EXCLUDED_BUILD_WAS_LOADED"
  assert_not_exists "$marker"
}

function test_explicit_file_roots_are_accounted_for() {
  write_build_fixture
  run_probe build //pkg:produced.txt //pkg:input.txt
  assert_affected //pkg:produced.txt
  assert_equals 2 "$("$JQ" -r .total_targets "$manifest")"
  expect_log '^CACHE_PROBE_MISS //pkg:produced.txt$'
  assert_not_exists "$marker"

  bazel build --spawn_strategy=standalone //pkg:produced.txt >& "$TEST_log" \
    || fail "Initial output-file build failed"
  assert_equals producer "$(cat "$marker")"
  rm "$marker"

  run_probe build //pkg:produced.txt //pkg:input.txt
  assert_affected
  assert_equals 2 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
}

function test_file_path_included_when_excluded_subdirectory_is_not_a_package() {
  mkdir -p pkg/sub
  echo source > pkg/sub/input.txt
  echo 'exports_files(["sub/input.txt"])' > pkg/BUILD

  run_probe build --experimental_cache_probe_exclude_deps=//pkg/sub pkg/sub/input.txt
  assert_affected
  assert_equals 1 "$("$JQ" -r .total_targets "$manifest")"
}

function test_deleted_output_invalidates_successful_probe() {
  write_build_fixture
  bazel build --spawn_strategy=standalone --disk_cache= //pkg:consumer \
    >& "$TEST_log" || fail "Initial build failed"
  rm "$marker"

  run_probe build --disk_cache= //pkg:consumer
  assert_affected
  assert_not_exists "$marker"
  assert_exists bazel-bin/pkg/produced.txt
  rm bazel-bin/pkg/produced.txt

  run_probe build --disk_cache= //pkg:consumer
  assert_affected //pkg:consumer
  expect_log '^CACHE_PROBE_MISS //pkg:consumer$'
  assert_not_exists "$marker"
  assert_not_exists bazel-bin/pkg/produced.txt
}

function test_deleted_test_status_invalidates_successful_probe() {
  write_test_fixture
  bazel test --spawn_strategy=standalone --test_strategy=standalone --disk_cache= \
    //pkg:test >& "$TEST_log" || fail "Initial test failed"
  rm "$marker"

  run_probe test --disk_cache= //pkg:test //pkg:suite
  assert_affected
  assert_not_exists "$marker"
  assert_exists bazel-testlogs/pkg/test/test.cache_status
  rm bazel-testlogs/pkg/test/test.cache_status

  run_probe test --disk_cache= //pkg:test //pkg:suite
  assert_affected //pkg:test //pkg:suite
  assert_not_exists "$marker"
}

function test_shared_cache_fill_invalidates_probe_miss() {
  write_test_fixture
  local cache="${control_dir}/shared-cache"
  local probe_output_base
  probe_output_base="$(bazel info output_base 2> "$TEST_log")" \
    || fail "Cannot locate probe output base"

  run_probe test --disk_cache="$cache" //pkg:test //pkg:suite
  assert_affected //pkg:test //pkg:suite
  assert_not_exists "$marker"
  local server_pid
  server_pid="$(cat "${probe_output_base}/server/server.pid.txt")"

  bazel --batch --output_base="${control_dir}/cache-fill-output-base" test \
    --spawn_strategy=standalone --test_strategy=standalone --disk_cache="$cache" \
    //pkg:test >& "$TEST_log" || fail "Shared-cache fill failed"
  assert_equals test "$(cat "$marker")"
  rm "$marker"

  run_probe test --disk_cache="$cache" //pkg:test //pkg:suite
  assert_affected
  assert_equals 2 "$("$JQ" -r .total_targets "$manifest")"
  assert_not_exists "$marker"
  assert_equals "$server_pid" "$(cat "${probe_output_base}/server/server.pid.txt")"
}

run_suite "Bazel native cache probe tests"
