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

package com.google.devtools.build.lib.buildtool;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.ExtraActionArtifactsProvider;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests of scoped configured queries as build outputs. */
@RunWith(TestParameterInjector.class)
public final class GenCqueryIntegrationTest extends BuildIntegrationTestCase {
  @TestParameter private boolean keepGoing;

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();
    runtimeWrapper.addOptions(keepGoing ? "--keep_going" : "--nokeep_going");
  }

  @Test
  public void testSelectedDependenciesChangeWithBuildConfiguration() throws Exception {
    write("pkg/a.txt", "a");
    write("pkg/b.txt", "b");
    write(
        "pkg/BUILD",
        """
        config_setting(name = "choose_a", define_values = {"choice": "a"})
        config_setting(name = "broken", define_values = {"choice": "broken"})
        filegroup(
            name = "root",
            srcs = select({
                ":choose_a": ["a.txt"],
                ":broken": ["//missing:target"],
                "//conditions:default": ["b.txt"],
            }),
        )
        gencquery(
            name = "q",
            expression = "labels(srcs, //pkg:root)",
            scope = [":root"],
            output = "starlark",
        )
        """);
    assertQueryResult("//pkg:q", "@@//pkg:b.txt");
    addOptions("--define=choice=a");
    assertQueryResult("//pkg:q", "@@//pkg:a.txt");
    addOptions("--define=choice=broken");
    assertFailure("//pkg:q", "no such package 'missing'");
  }

  @Test
  public void testScopeRetainsTestOptionsAndTracksChanges() throws Exception {
    write("pkg/one.txt", "one");
    write("pkg/two.txt", "two");
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            executable = ctx.actions.declare_file(ctx.label.name + ".sh")
            ctx.actions.write(executable, "exit 1", is_executable = True)
            return [DefaultInfo(executable = executable)]
        example_test = rule(implementation = _impl, test = True, attrs = {
            "srcs": attr.label_list(allow_files = True),
        })
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "example_test")
        config_setting(name = "one", values = {"test_filter": "one"})
        example_test(name = "root", srcs = select({
            ":one": ["one.txt"],
            "//conditions:default": ["two.txt"],
        }))
        test_suite(name = "suite", tests = [":root"])
        gencquery(name = "options", expression = "//pkg:suite", scope = [":suite"],
                 output = "starlark",
                 starlark_expr = "build_options(target)['//command_line_option:test_filter']")
        gencquery(name = "deps", expression = "labels(srcs, //pkg:root)", scope = [":root"],
                 output = "starlark")
        """);
    addOptions("--test_filter=one");
    assertQueryResult("//pkg:options", "one");
    assertQueryResult("//pkg:deps", "@@//pkg:one.txt");
    addOptions("--test_filter=two");
    assertQueryResult("//pkg:options", "two");
    assertQueryResult("//pkg:deps", "@@//pkg:two.txt");
  }

  @Test
  public void testOutputIsConsumableWithoutExecutingScopeActions(
      @TestParameter boolean actionListeners) throws Exception {
    write(
        "private/BUILD",
        "genrule(name = 'producer', testonly = True, outs = ['generated.txt'], cmd = 'exit 1')");
    write(
        "pkg/BUILD",
        """
        gencquery(
            name = "q",
            expression = "//private:producer",
            scope = ["//private:producer"],
            output = "starlark",
            starlark_expr = "target.files.to_list()[0].basename",
        )
        genrule(name = "consumer", srcs = [":q"], outs = ["copied.txt"], cmd = "cat $(location :q) > $@")
        """);
    if (actionListeners) {
      write(
          "listener/BUILD",
          """
          extra_action(
              name = "extra",
              requires_action_output = True,
              out_templates = ["$(ACTION_ID).extra"],
              cmd = "touch $(output $(ACTION_ID).extra)",
          )
          action_listener(
              name = "listener",
              mnemonics = ["Genrule", "FileWrite"],
              extra_actions = [":extra"],
              visibility = ["//visibility:public"],
          )
          """);
      addOptions("--experimental_action_listener=//listener:listener");
    }
    assertQueryResult("//pkg:consumer", "generated.txt");
    if (actionListeners) {
      var queryExtraActions =
          getConfiguredTarget("//pkg:q")
              .getProvider(ExtraActionArtifactsProvider.class)
              .getExtraActionArtifacts()
              .toList();
      assertThat(queryExtraActions).isNotEmpty();
      for (var artifact : queryExtraActions) {
        assertThat(artifact.getPath().exists()).isTrue();
      }
    }
  }

  @Test
  public void testIncompatibleScopeTargetsCanBeInspected() throws Exception {
    write(
        "pkg/BUILD",
        """
        constraint_setting(name = "setting")
        constraint_value(name = "unavailable", constraint_setting = ":setting")
        filegroup(name = "portable")
        filegroup(name = "incompatible", srcs = [":portable"],
                  target_compatible_with = [":unavailable"])
        gencquery(name = "portable_report", expression = "//pkg:portable",
                  scope = [":portable", ":incompatible"], output = "starlark")
        gencquery(name = "incompatible_report", expression = "kind('filegroup', deps(//pkg:incompatible))",
                  scope = [":incompatible"], output = "starlark")
        """);
    assertQueryResult("//pkg:portable_report", "@@//pkg:portable");
    assertQueryResult("//pkg:incompatible_report", "@@//pkg:incompatible");
  }

  @Test
  public void testReportDoesNotInheritScopeTransitiveVisibility() throws Exception {
    addOptions("--experimental_enforce_transitive_visibility");
    write(
        "private/BUILD",
        """
        package(transitive_visibility = ":allowed")
        package_group(name = "allowed", packages = ["//private"])
        filegroup(name = "root", visibility = ["//visibility:public"])
        """);
    write(
        "reports/BUILD",
        """
        gencquery(name = "q", expression = "//private:root", scope = ["//private:root"],
                  output = "starlark", visibility = ["//visibility:public"])
        """);
    write(
        "consumer/BUILD",
        """
        filegroup(name = "report", srcs = ["//reports:q"])
        filegroup(name = "direct", srcs = ["//private:root"])
        """);
    assertQueryResult("//consumer:report", "@@//private:root");
    assertFailure("//consumer:direct", "Transitive visibility error");
  }

  @Test
  public void testScopeCoverageDoesNotPropagateToConsumers() throws Exception {
    addOptions("--collect_code_coverage");
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            metadata = ctx.actions.declare_file(ctx.label.name + ".metadata")
            ctx.actions.run_shell(outputs = [metadata], command = "exit 1")
            return [coverage_common.instrumented_files_info(ctx, metadata_files = [metadata])]
        covered = rule(implementation = _impl)
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "covered")
        covered(name = "root")
        gencquery(name = "q", expression = "//pkg:root", scope = [":root"])
        filegroup(name = "consumer", srcs = [":q"])
        """);
    buildTarget("//pkg:consumer");
    assertThat(
            getConfiguredTarget("//pkg:consumer")
                .get(InstrumentedFilesInfo.STARLARK_CONSTRUCTOR)
                .getInstrumentationMetadataFiles()
                .toList())
        .isEmpty();
  }

  @Test
  public void testStarlarkFileFormattingAndInvalidation(@TestParameter boolean discardAnalysisCache)
      throws Exception {
    addOptions("--platform_suffix=example");
    if (discardAnalysisCache) {
      addOptions("--discard_analysis_cache");
    }
    write(unicodeToInternal("pkg/café.txt"), "content");
    write("format/BUILD", "exports_files(['format.cquery'])");
    write(
        "format/format.cquery",
        """
        def format(target):
            files = providers(target)["DefaultInfo"].files.to_list()
            suffix = build_options(target)["//command_line_option:platform_suffix"]
            return "%s:%s" % (suffix, files[0].basename == "café.txt")
        """);
    write(
        "pkg/BUILD",
        """
        filegroup(name = "café", srcs = ["café.txt"])
        gencquery(
            name = "q",
            expression = "//pkg:café",
            scope = [":café"],
            output = "starlark",
            starlark_file = "//format:format.cquery",
            opts = ["--line_terminator_null"],
        )
        """);
    assertThat(getQueryResult("//pkg:q")).isEqualTo("example:True\0");
    write("format/format.cquery", "def format(target): return 'café:' + str(target.label)");
    assertThat(getQueryResult("//pkg:q")).isEqualTo("café:@@//pkg:café\0");
  }

  @Test
  public void testResultsSortedAcrossCleanBuilds() throws Exception {
    write("one/BUILD", "filegroup(name = 'root', visibility = ['//visibility:public'])");
    write("two/BUILD", "filegroup(name = 'root', visibility = ['//visibility:public'])");
    write(
        "pkg/BUILD",
        """
        filegroup(name = "leaf")
        filegroup(name = "root", srcs = ["//two:root", ":leaf", "//one:root"])
        gencquery(name = "q", expression = "deps(//pkg:root)", scope = [":root"],
                 output = "starlark", opts = ["--noimplicit_deps"])
        """);
    assertQueryResult("//pkg:q", "@@//one:root", "@@//pkg:leaf", "@@//pkg:root", "@@//two:root");
    createFilesAndMocks(); // Reanalyze the same graph without cached query results.
    assertQueryResult("//pkg:q", "@@//one:root", "@@//pkg:leaf", "@@//pkg:root", "@@//two:root");
  }

  @Test
  public void testGenCqueryEncountersAnotherGenCquery() throws Exception {
    write(
        "inner/BUILD",
        """
        filegroup(name = "leaf")
        filegroup(name = "root", srcs = [":leaf"])
        gencquery(name = "q", expression = "deps(//inner:root)", scope = [":root"])
        """);
    write(
        "outer/BUILD",
        """
        filegroup(name = "root")
        gencquery(
            name = "q",
            expression = "deps(//outer:root) + deps(//inner:q)",
            scope = [":root", "//inner:q"],
            output = "starlark",
            opts = ["--noimplicit_deps"],
        )
        """);
    assertQueryResult(
        "//outer:q", "@@//inner:leaf", "@@//inner:q", "@@//inner:root", "@@//outer:root");
  }

  @Test
  public void testTransitiveBuildFileChangesInvalidateResult() throws Exception {
    write(
        "leaf/BUILD",
        "filegroup(name = 'one')",
        "filegroup(name = 'two')",
        "filegroup(name = 'leaf', srcs = [':one'], visibility = ['//visibility:public'])");
    write(
        "pkg/BUILD",
        """
        filegroup(name = "root", srcs = ["//leaf:leaf"])
        gencquery(name = "q", expression = "deps(//pkg:root)", scope = [":root"],
                 output = "starlark", opts = ["--noimplicit_deps"])
        """);
    assertQueryResult("//pkg:q", "@@//leaf:leaf", "@@//leaf:one", "@@//pkg:root");
    write(
        "leaf/BUILD",
        "filegroup(name = 'one')",
        "filegroup(name = 'two')",
        "filegroup(name = 'leaf', srcs = [':two'], visibility = ['//visibility:public'])");
    assertQueryResult("//pkg:q", "@@//leaf:leaf", "@@//leaf:two", "@@//pkg:root");
  }

  @Test
  public void testOutOfScopeTargetFromPreviousBuildIsRejected() throws Exception {
    write(
        "pkg/BUILD",
        """
        filegroup(name = "inside")
        filegroup(name = "outside")
        gencquery(name = "q", expression = "//pkg:outside", scope = [":inside"])
        """);
    buildTarget("//pkg:outside");
    assertFailure("//pkg:q", "is not within the scope of the query");
  }

  @Test
  public void testNonStrictScopeWarnsAndPreservesOtherResults() throws Exception {
    write(
        "pkg/BUILD",
        """
        filegroup(name = "inside")
        gencquery(
            name = "q",
            expression = "//pkg:inside + //does_not_exist:outside",
            scope = [":inside"],
            strict = False,
            output = "starlark",
        )
        """);
    assertQueryResult("//pkg:q", "@@//pkg:inside");
    assertContainsEvent("is not within the scope of the query");
  }

  @Test
  @TestParameters("{scope: '//missing:target', message: \"no such package 'missing'\"}")
  @TestParameters("{scope: '//other:missing', message: \"no such target '//other:missing'\"}")
  @TestParameters("{scope: '//pkg:broken', message: \"no such package 'missing'\"}")
  @TestParameters("{scope: '//pkg:cycle', message: 'cycle in dependency graph'}")
  public void testInvalidScopeFailsEvenWhenNonStrict(String scope, String message)
      throws Exception {
    write("other/BUILD", "filegroup(name = 'root')");
    write(
        "pkg/BUILD",
        "filegroup(name = 'broken', srcs = ['//missing:target'])",
        "filegroup(name = 'cycle', srcs = [':cycle'])",
        "gencquery(name = 'q', expression = 'set()', strict = False, scope = ['" + scope + "'])");
    assertFailure("//pkg:q", message);
  }

  @Test
  public void testInvalidQueryExpression() throws Exception {
    write("pkg/BUILD", "gencquery(name = 'q', expression = 'deps(', scope = [])");
    assertFailure("//pkg:q", "cquery failed");
  }

  @Test
  public void testReverseDependenciesAreRestrictedToScope() throws Exception {
    write(
        "pkg/BUILD",
        """
        filegroup(name = "leaf")
        filegroup(name = "root", srcs = [":leaf"])
        filegroup(name = "outside", srcs = [":leaf"])
        gencquery(
            name = "q",
            expression = "rdeps(deps(//pkg:root), //pkg:leaf)",
            scope = [":root"],
            output = "starlark",
        )
        """);
    buildTarget("//pkg:outside");
    assertQueryResult("//pkg:q", "@@//pkg:leaf", "@@//pkg:root");
  }

  @Test
  public void testAliasesAndOutputFilesRetainTheirDependencyEdges() throws Exception {
    write(
        "pkg/BUILD",
        """
        genrule(name = "producer", outs = ["generated.txt"], cmd = "exit 1")
        alias(name = "alias", actual = ":generated.txt")
        gencquery(
            name = "q",
            expression = "deps(//pkg:alias, 1)",
            scope = [":alias"],
        )
        gencquery(
            name = "path",
            expression = "somepath(//pkg:generated.txt, //pkg:producer)",
            scope = [":generated.txt"],
            output = "starlark",
        )
        """);
    assertThat(getQueryResult("//pkg:q")).contains("//pkg:generated.txt (");
    assertQueryResult("//pkg:path", "@@//pkg:generated.txt", "@@//pkg:producer");
  }

  @Test
  public void testTransitionsAndConfigurationSelection() throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _split(settings, attr):
            return [{"//command_line_option:platform_suffix": suffix} for suffix in ["one", "two"]]
        split = transition(implementation = _split, inputs = [], outputs = ["//command_line_option:platform_suffix"])
        def _toggle(settings, attr):
            suffix = settings["//command_line_option:platform_suffix"]
            return {"//command_line_option:platform_suffix": "two" if suffix == "one" else "one"}
        toggle = transition(implementation = _toggle,
                            inputs = ["//command_line_option:platform_suffix"],
                            outputs = ["//command_line_option:platform_suffix"])
        def _impl(ctx):
            return []
        root = rule(implementation = _impl, cfg = toggle, attrs = {
            "dep": attr.label(cfg = split),
            "_allowlist_function_transition": attr.label(default = "@bazel_tools//tools/allowlists/function_transition_allowlist"),
        })
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "root")
        filegroup(name = "leaf")
        root(name = "root", dep = ":leaf")
        gencquery(
            name = "split", expression = "//pkg:leaf", scope = [":root"],
            output = "starlark",
            starlark_expr = "build_options(target)['//command_line_option:platform_suffix']",
        )
        gencquery(name = "target", expression = "config(//pkg:root, target)", scope = [":root"],
                 output = "starlark",
                 starlark_expr = "build_options(target)['//command_line_option:platform_suffix']")
        gencquery(name = "labels", expression = "//pkg:leaf", scope = [":root"], output = "starlark")
        """);
    assertThat(getQueryResult("//pkg:split").split("\n")).asList().containsExactly("one", "two");
    // config(..., target) must select the analyzed root without applying its transition again.
    assertQueryResult("//pkg:target", "one");
    assertQueryResult("//pkg:labels", "@@//pkg:leaf", "@@//pkg:leaf");
  }

  @Test
  public void testConfigurationSelectionPreservesExecutionPlatforms() throws Exception {
    write("pkg/a.txt", "a");
    write("pkg/b.txt", "b");
    write(
        "pkg/rules.bzl",
        """
        def _toolchain(ctx):
            return [platform_common.ToolchainInfo(),
                    DefaultInfo(files = depset([ctx.file.tool]))]
        implementation = rule(implementation = _toolchain, attrs = {
            "tool": attr.label(allow_single_file = True, cfg = "exec"),
        })
        def _consumer(ctx):
            return []
        consumer = rule(implementation = _consumer, toolchains = ["//pkg:type"])
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "consumer", "implementation")
        constraint_setting(name = "os")
        constraint_value(name = "a", constraint_setting = ":os")
        constraint_value(name = "b", constraint_setting = ":os")
        platform(name = "exec_a", constraint_values = [":a"])
        platform(name = "exec_b", constraint_values = [":b"])
        config_setting(name = "on_a", constraint_values = [":a"])
        filegroup(name = "tool", srcs = select({":on_a": ["a.txt"], "//conditions:default": ["b.txt"]}))
        toolchain_type(name = "type")
        implementation(name = "implementation", tool = ":tool")
        toolchain(name = "registered", toolchain_type = ":type", toolchain = ":implementation")
        consumer(name = "left", exec_compatible_with = [":a"])
        consumer(name = "right", exec_compatible_with = [":b"])
        gencquery(name = "all", expression = "//pkg:implementation", scope = [":left", ":right"],
                  output = "starlark", starlark_expr = "target.files.to_list()[0].basename")
        gencquery(name = "selected", expression = "config(//pkg:implementation, target)",
                  scope = [":left", ":right"], output = "starlark",
                  starlark_expr = "target.files.to_list()[0].basename")
        """);
    addOptions(
        "--extra_toolchains=//pkg:registered",
        "--extra_execution_platforms=//pkg:exec_a,//pkg:exec_b");
    assertQueryResult("//pkg:all", "a.txt", "b.txt");
    assertQueryResult("//pkg:selected", "a.txt", "b.txt");
  }

  @Test
  public void testLabelAndKindOutput(@TestParameter boolean consistentLabels) throws Exception {
    write("pkg/input.txt", "content");
    write(
        "pkg/BUILD",
        """
        filegroup(name = "røøt", srcs = ["input.txt"])
        gencquery(
            name = "q",
            expression = "config(//pkg:røøt, target) + config(//pkg:input.txt, null)",
            scope = [":røøt"],
            opts = ["--consistent_labels=%s"],
        )
        gencquery(name = "kind", expression = "//pkg:røøt", scope = [":røøt"], output = "label_kind")
        gencquery(name = "starlark", expression = "//pkg:røøt", scope = [":røøt"],
                 output = "starlark", starlark_expr = "'é:' + str(target.label)")
        """
            .formatted(consistentLabels));
    String prefix = consistentLabels ? "@@" : "";
    assertThat(getQueryResult("//pkg:q"))
        .matches(prefix + "//pkg:input.txt \\(null\\)\n" + prefix + "//pkg:røøt \\([a-f0-9]+\\)\n");
    assertThat(getQueryResult("//pkg:kind")).matches("filegroup rule //pkg:røøt \\([a-f0-9]+\\)\n");
    assertQueryResult("//pkg:starlark", "é:@@//pkg:røøt");
  }

  @Test
  public void testCompressedOutput() throws Exception {
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'], output = 'starlark',"
            + " compressed_output = True)");
    buildTarget("//pkg:q");
    try (var input =
        new GZIPInputStream(
            Iterables.getOnlyElement(getArtifacts("//pkg:q")).getPath().getInputStream())) {
      assertThat(new String(input.readAllBytes(), UTF_8)).isEqualTo("@@//pkg:root\n");
    }
  }

  @Test
  @TestParameters("{expression: '1 // 0', fromFile: false, message: 'Starlark evaluation error'}")
  @TestParameters("{expression: '(', fromFile: false, message: 'invalid starlark_expr'}")
  @TestParameters("{expression: '1 // 0', fromFile: true, message: 'Starlark evaluation error'}")
  @TestParameters("{expression: '(', fromFile: true, message: 'invalid starlark_file'}")
  public void testStarlarkFormattingErrorsFailTheBuild(
      String expression, boolean fromFile, String message) throws Exception {
    String formatter = "starlark_expr = '" + expression + "'";
    if (fromFile) {
      write("pkg/format.cquery", "def format(target): return " + expression);
      formatter = "starlark_file = ':format.cquery'";
    }
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'], output = 'starlark', "
            + formatter
            + ")");
    assertFailure("//pkg:q", message);
    assertContainsEvent(fromFile ? "pkg/format.cquery" : "starlark_expr");
    assertDoesNotContainEvent("--starlark:");
  }

  @Test
  public void testStarlarkAttributesRequireStarlarkOutput(
      @TestParameter({"starlark_expr = ''", "starlark_file = ':format.cquery'"}) String formatter)
      throws Exception {
    write("pkg/format.cquery", "def format(target): return str(target.label)");
    write(
        "pkg/BUILD", "gencquery(name = 'q', expression = 'set()', scope = [], " + formatter + ")");
    assertFailure("//pkg:q", "requires output = \"starlark\"");
  }

  @Test
  public void testConflictingStarlarkAttributesFail() throws Exception {
    write("pkg/f.cquery", "def format(target): return ''");
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'], output = 'starlark',"
            + " starlark_expr = 'str(target.label)', starlark_file = ':f.cquery')");
    assertFailure("//pkg:q", "starlark_expr and starlark_file are mutually exclusive");
  }

  @Test
  public void testGeneratedFormatterIsRejected() throws Exception {
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "genrule(name = 'formatter', outs = ['f.cquery'], cmd = 'exit 1')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'], output = 'starlark',"
            + " starlark_file = ':formatter')");
    assertFailure("//pkg:q", "must be a source file, not a generated file");
  }

  @Test
  public void testTargetPatternsAreRejected(
      @TestParameter({"//pkg:*", "//pkg:all", "//pkg/..."}) String pattern) throws Exception {
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '" + pattern + "', scope = [':root'])");
    assertFailure("//pkg:q", "target patterns are not allowed in gencquery");
  }

  @Test
  public void testUnsupportedOptionsAreRejected(
      @TestParameter({
            "--output=starlark",
            "--universe_scope=//pkg:root",
            "--infer_universe_scope",
            "--query_file=somewhere",
            "--output_file=somewhere",
            "--starlark:file=somewhere",
            "--starlark:expr=target.label",
            "--transitions=full",
            "--show_config_fragments=direct",
            "--keep_going",
            "--cpu=other"
          })
          String option)
      throws Exception {
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'], opts = ['"
            + option
            + "'])");
    assertFailure("//pkg:q", "in opts attribute");
  }

  @Test
  public void testToolAndImplicitDependencyFiltersAndExecConfiguration() throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            return []
        root = rule(implementation = _impl, attrs = {
            "dep": attr.label(),
            "tool": attr.label(cfg = "exec"),
            "_hidden": attr.label(default = "//pkg:hidden"),
        })
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "root")
        filegroup(name = "leaf")
        filegroup(name = "hidden")
        root(name = "root", dep = ":leaf", tool = ":leaf")
        gencquery(name = "unfiltered", expression = "filter('^//pkg:', deps(//pkg:root))",
                 scope = [":root"], output = "starlark")
        gencquery(name = "filtered", expression = "filter('^//pkg:', deps(//pkg:root))",
                 scope = [":root"], output = "starlark", opts = ["--notool_deps", "--noimplicit_deps"])
        gencquery(name = "exec", expression = "config(//pkg:leaf, anyexec)",
                 scope = [":root"], output = "starlark")
        """);
    assertQueryResult(
        "//pkg:unfiltered", "@@//pkg:hidden", "@@//pkg:leaf", "@@//pkg:leaf", "@@//pkg:root");
    assertQueryResult("//pkg:filtered", "@@//pkg:leaf", "@@//pkg:root");
    assertQueryResult("//pkg:exec", "@@//pkg:leaf");
  }

  @Test
  public void testAspectDependencyChain() throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _aspect_impl(target, ctx):
            return []
        hidden = aspect(implementation = _aspect_impl, attrs = {
            "_dep": attr.label(default = "//pkg:middle"),
        })
        terminal = aspect(implementation = _aspect_impl, attrs = {
            "_dep": attr.label(default = "//pkg:end"),
        })
        def _impl(ctx):
            return []
        root = rule(implementation = _impl, attrs = {
            "dep": attr.label(aspects = [hidden]),
        })
        middle = rule(implementation = _impl, attrs = {
            "dep": attr.label(aspects = [terminal]),
        })
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "middle", "root")
        filegroup(name = "leaf")
        filegroup(name = "end")
        middle(name = "middle", dep = ":leaf")
        root(name = "root", dep = ":leaf")
        gencquery(name = "q", expression = "filter('^//pkg:', deps(//pkg:root))",
                 scope = [":root"], output = "starlark")
        gencquery(name = "without_aspects", expression = "filter('^//pkg:', deps(//pkg:root))",
                 scope = [":root"], output = "starlark", opts = ["--noinclude_aspects"])
        gencquery(name = "explicit_aspects", expression = "filter('^//pkg:', deps(//pkg:root))",
                 scope = [":root"], output = "starlark", starlark_expr = "'node'",
                 opts = ["--experimental_explicit_aspects"])
        """);
    assertQueryResult("//pkg:q", "@@//pkg:end", "@@//pkg:leaf", "@@//pkg:middle", "@@//pkg:root");
    assertQueryResult("//pkg:without_aspects", "@@//pkg:leaf", "@@//pkg:root");
    assertQueryResult("//pkg:explicit_aspects", "node", "node", "node", "node", "node", "node");
  }

  @Test
  public void testQueryAndFormatterInExternalRepository() throws Exception {
    write(
        "MODULE.bazel",
        "bazel_dep(name = 'other', repo_name = 'renamed')",
        "local_path_override(module_name = 'other', path = 'other')");
    write("other/MODULE.bazel", "module(name = 'other')");
    write("other/pkg/format.cquery", "def format(target): return target.label.name");
    write(
        "other/pkg/BUILD",
        "exports_files(['format.cquery'])",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'], output = 'starlark',"
            + " starlark_file = ':format.cquery')");
    assertQueryResult("@renamed//pkg:q", "root");
    write(
        "pkg/BUILD",
        """
        gencquery(
            name = "q",
            expression = "@renamed//pkg:root",
            scope = ["@renamed//pkg:root"],
            output = "starlark",
            starlark_file = "@renamed//pkg:format.cquery",
        )
        """);
    assertQueryResult("//pkg:q", "root");
  }

  @Test
  public void testEmptyScopeAndEmptyResult() throws Exception {
    write("pkg/BUILD", "gencquery(name = 'q', expression = 'set()', scope = [])");
    assertThat(getQueryResult("//pkg:q")).isEmpty();
  }

  @Test
  public void testRelativeLabelsAreResolvedFromRepositoryRoot() throws Exception {
    write("BUILD", "filegroup(name = 'root')");
    write(
        "pkg/BUILD",
        "gencquery(name = 'q', expression = ':root', scope = ['//:root'], output = 'starlark')");
    assertQueryResult("//pkg:q", "@@//:root");
  }

  @Test
  public void testMissingIncrementalGraphFailsClearly() throws Exception {
    addOptions(
        "--nokeep_state_after_build", "--discard_analysis_cache", "--notrack_incremental_state");
    write(
        "pkg/BUILD",
        "filegroup(name = 'root')",
        "gencquery(name = 'q', expression = '//pkg:root', scope = [':root'])");
    assertFailure("//pkg:q", "gencquery requires --track_incremental_state");
  }

  private void assertQueryResult(String target, String... expected) throws Exception {
    assertThat(getQueryResult(target).split("\n"))
        .asList()
        .containsExactlyElementsIn(ImmutableList.copyOf(expected))
        .inOrder();
  }

  private String getQueryResult(String target) throws Exception {
    buildTarget(target);
    return readContentAsByteArray(Iterables.getOnlyElement(getArtifacts(target))).toStringUtf8();
  }

  private void assertFailure(String target, String message) throws Exception {
    assertThrows(expectedExceptionClass(), () -> buildTarget(target));
    events.assertContainsError(message);
  }

  private Class<? extends Throwable> expectedExceptionClass() {
    return keepGoing ? BuildFailedException.class : ViewCreationFailedException.class;
  }
}
