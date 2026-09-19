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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.BuildFailedException;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.protobuf.ByteString;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end contract of action queries as build outputs. */
@RunWith(TestParameterInjector.class)
public final class GenAqueryIntegrationTest extends BuildIntegrationTestCase {
  @TestParameter private boolean keepGoing;

  @Override
  protected void setupOptions() throws Exception {
    super.setupOptions();
    runtimeWrapper.addOptions(keepGoing ? "--keep_going" : "--nokeep_going");
  }

  private void writeActions() throws Exception {
    write("pkg/a.h", "a");
    write("pkg/b.h", "b");
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            one = ctx.actions.declare_file(ctx.label.name + ".one")
            two = ctx.actions.declare_file(ctx.label.name + ".two")
            ctx.actions.run_shell(inputs = ctx.files.srcs, outputs = [one, two],
                                  command = "exit 1", mnemonic = "ExampleAction")
            return [DefaultInfo(files = depset([one, two]))]
        producer = rule(implementation = _impl, attrs = {
            "srcs": attr.label_list(allow_files = True),
        })
        """);
    write(
        "pkg/BUILD",
        "load(':rules.bzl', 'producer')",
        "producer(name = 'root', srcs = ['a.h', 'b.h'])");
  }

  private void report(String name, String expression, String attributes) throws Exception {
    write(
        "reports/" + name + "/BUILD",
        "genaquery(name = 'q', expression = \""
            + expression
            + "\", scope = ['//pkg:root'], "
            + attributes
            + ")");
  }

  @Test
  public void testFormatsDescribeSelectedActions(
      @TestParameter({
            "text",
            "commands",
            "summary",
            "proto",
            "streamed_proto",
            "textproto",
            "jsonproto"
          })
          String format)
      throws Exception {
    writeActions();
    report("formats", "//pkg:root", "output = '" + format + "'");
    ByteString result = result("//reports/formats:q");
    switch (format) {
      case "text" -> {
        assertThat(result.toStringUtf8()).contains("Mnemonic: ExampleAction");
        assertThat(result.toStringUtf8()).contains("pkg/root.one");
        assertThat(result.toStringUtf8()).contains("pkg/a.h");
      }
      case "commands" -> assertThat(result.toStringUtf8()).contains("'exit 1'");
      case "summary" -> {
        assertThat(result.toStringUtf8()).contains("1 total action.");
        assertThat(result.toStringUtf8()).contains("ExampleAction: 1");
      }
      default -> {
        ActionGraphContainer graph = parse(format, result);
        assertThat(graph.getActionsList()).hasSize(1);
        assertThat(graph.getActions(0).getMnemonic()).isEqualTo("ExampleAction");
        assertThat(graph.getActions(0).getArgumentsList()).contains("exit 1");
        assertThat(graph.getActions(0).getOutputIdsList()).hasSize(2);
        assertThat(graph.getTargetsList()).hasSize(1);
        assertThat(graph.getTargets(0).getLabel()).isEqualTo("//pkg:root");
      }
    }
    assertThat(
            getFilesToBuild(getConfiguredTarget("//pkg:root")).toList().get(0).getPath().exists())
        .isFalse();
  }

  @Test
  public void testJsonPreservesUtf8LabelsPathsArgumentsAndTemplateContents() throws Exception {
    write(unicodeToInternal("pkg/café.h"), "header");
    write("pkg/template", "héllo");
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file("café.out")
            ctx.actions.run_shell(inputs = ctx.files.srcs, outputs = [out], command = "echo café")
            expanded = ctx.actions.declare_file("expanded")
            ctx.actions.expand_template(template = ctx.file.template, output = expanded, substitutions = {})
            return []
        producer = rule(implementation = _impl, attrs = {
            "srcs": attr.label_list(allow_files = True),
            "template": attr.label(allow_single_file = True),
        })
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "producer")
        producer(name = "café", srcs = ["café.h"], template = "template")
        genaquery(name = "report", expression = "//pkg:café", scope = [":café"], output = "jsonproto")
        """);
    var graph = parse("jsonproto", result("//pkg:report"));
    assertThat(graph.getTargets(0).getLabel()).isEqualTo("//pkg:café");
    assertThat(graph.getPathFragmentsList().stream().map(p -> p.getLabel()))
        .containsAtLeast("café.h", "café.out");
    assertThat(graph.getActionsList().stream().flatMap(a -> a.getArgumentsList().stream()))
        .contains("echo café");
    assertThat(graph.getActionsList().stream().map(a -> a.getTemplateContent()))
        .contains("héllo\n");
  }

  @Test
  public void testOutputIsConsumableWithoutExecutingScope() throws Exception {
    writeActions();
    write(
        "reports/BUILD",
        """
        genaquery(name = "q", expression = "//pkg:root", scope = ["//pkg:root"], output = "summary")
        genrule(name = "consumer", srcs = [":q"], outs = ["copied.txt"], cmd = "cat $(location :q) > $@")
        """);
    assertThat(result("//reports:consumer").toStringUtf8()).contains("ExampleAction: 1");
  }

  @Test
  public void testScopeConflictsDoNotAffectTheReport(@TestParameter boolean mergedAnalysisExecution)
      throws Exception {
    addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution);
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            output = ctx.actions.declare_file("shared.out")
            ctx.actions.write(output, ctx.label.name)
            return [DefaultInfo(files = depset([output]))]
        producer = rule(implementation = _impl)
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "producer")
        producer(name = "a")
        producer(name = "b")
        filegroup(name = "both", srcs = [":a", ":b"])
        genaquery(name = "q", expression = "set(//pkg:a //pkg:b)", scope = [":a", ":b"],
                 output = "proto", opts = ["--include_file_write_contents"])
        """);
    var graph = ActionGraphContainer.parseFrom(result("//pkg:q"));
    assertThat(graph.getActionsList().stream().map(a -> a.getFileContents()))
        .containsExactly("a", "b")
        .inOrder();
    buildTarget("//pkg:q", "//pkg:a");
    failure("//pkg:both", "conflicting actions");
    assertThat(ActionGraphContainer.parseFrom(result("//pkg:q"))).isEqualTo(graph);
  }

  @Test
  @TestParameters("{expression: \"inputs('.*a.h', inputs('.*b.h', //pkg:root))\", expected: 1}")
  @TestParameters(
      "{expression: \"outputs('.*[.]one', outputs('.*[.]two', //pkg:root))\", expected: 1}")
  @TestParameters(
      "{expression: \"mnemonic('ExampleAction', inputs('.*b.h', //pkg:root))\", expected: 1}")
  @TestParameters("{expression: \"mnemonic('Other', //pkg:root)\", expected: 0}")
  @TestParameters("{expression: \"inputs('.*missing', //pkg:root)\", expected: 0}")
  public void testActionFiltersCompose(String expression, int expected) throws Exception {
    writeActions();
    report("filters", expression, "output = 'proto'");
    assertThat(ActionGraphContainer.parseFrom(result("//reports/filters:q")).getActionsCount())
        .isEqualTo(expected);
  }

  @Test
  @TestParameters(
      "{expression: \"mnemonic('X')\", message: 'requires a regular expression and a target"
          + " expression'}")
  @TestParameters("{expression: \"mnemonic('[', //pkg:root)\", message: 'invalid action filter'}")
  @TestParameters(
      "{expression: \"deps(mnemonic('X', //pkg:root))\", message: 'action filters must wrap'}")
  @TestParameters(
      "{expression: \"mnemonic('X', //pkg:root) union //pkg:root\", message: 'action filters must"
          + " wrap'}")
  @TestParameters(
      "{expression: \"let x = mnemonic('X', //pkg:root) in $x\", message: 'action filters must"
          + " wrap'}")
  @TestParameters("{expression: 'deps(', message: 'premature end of input'}")
  public void testInvalidExpressionsFailClearly(String expression, String message)
      throws Exception {
    writeActions();
    report("invalid", expression, "");
    failure("//reports/invalid:q", message);
  }

  @Test
  public void testConfigurationAndTransitiveRuleChangesInvalidateOutput() throws Exception {
    write("pkg/value.bzl", "VALUE = 'first'");
    write(
        "pkg/rules.bzl",
        """
        load(":value.bzl", "VALUE")
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.write(out, VALUE + ctx.attr.value)
            return [DefaultInfo(files = depset([out]))]
        producer = rule(implementation = _impl, attrs = {"value": attr.string()})
        """);
    write(
        "pkg/BUILD",
        """
        load(":rules.bzl", "producer")
        config_setting(name = "a", define_values = {"choice": "a"})
        producer(name = "root", value = select({":a": "-a", "//conditions:default": "-b"}))
        """);
    report("config", "//pkg:root", "output = 'proto', opts = ['--include_file_write_contents']");
    assertThat(
            ActionGraphContainer.parseFrom(result("//reports/config:q"))
                .getActions(0)
                .getFileContents())
        .isEqualTo("first-b");
    addOptions("--define=choice=a");
    assertThat(
            ActionGraphContainer.parseFrom(result("//reports/config:q"))
                .getActions(0)
                .getFileContents())
        .isEqualTo("first-a");
    write("pkg/value.bzl", "VALUE = 'second'");
    assertThat(
            ActionGraphContainer.parseFrom(result("//reports/config:q"))
                .getActions(0)
                .getFileContents())
        .isEqualTo("second-a");
  }

  @Test
  public void testSourceTemplateContentsAreTracked(
      @TestParameter({"text", "proto", "jsonproto"}) String format,
      @TestParameter boolean discardAnalysisCache)
      throws Exception {
    if (discardAnalysisCache) {
      addOptions("--discard_analysis_cache");
    }
    write("pkg/template", "first");
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.expand_template(template = ctx.file.template, output = out, substitutions = {})
            return [DefaultInfo(files = depset([out]))]
        producer = rule(implementation = _impl, attrs = {"template": attr.label(allow_single_file = True)})
        """);
    write(
        "pkg/BUILD",
        "load(':rules.bzl', 'producer')",
        "producer(name = 'root', template = 'template')");
    report("template", "//pkg:root", "output = '" + format + "'");
    for (String content : new String[] {"first", "second"}) {
      write("pkg/template", content);
      ByteString output = result("//reports/template:q");
      if (format.equals("text")) {
        assertThat(output.toStringUtf8()).contains("Template: " + content);
      } else {
        assertThat(parse(format, output).getActions(0).getTemplateContent())
            .isEqualTo(content + "\n");
      }
    }
  }

  @Test
  public void testFormattingOptionsAndCompressedOutput(@TestParameter boolean compressed)
      throws Exception {
    writeActions();
    report(
        "options",
        "//pkg:root",
        "output = 'jsonproto', compressed_output = "
            + (compressed ? "True" : "False")
            + ", opts = ['--noinclude_artifacts', '--noinclude_commandline']");
    ByteString bytes = result("//reports/options:q");
    if (compressed) {
      try (var in = new GZIPInputStream(bytes.newInput())) {
        bytes = ByteString.readFrom(in);
      }
    }
    var graph = parse("jsonproto", bytes);
    assertThat(graph.getActions(0).getMnemonic()).isEqualTo("ExampleAction");
    assertThat(graph.getActions(0).getArgumentsList()).isEmpty();
    assertThat(graph.getArtifactsList()).isEmpty();
  }

  @Test
  public void testStableEncodingAcrossColdAndWarmBuilds(
      @TestParameter({"proto", "streamed_proto", "jsonproto"}) String format) throws Exception {
    writeActions();
    write(
        "pkg/BUILD",
        "load(':rules.bzl', 'producer')",
        "producer(name = 'root', srcs = ['a.h', 'b.h'])",
        "producer(name = 'other', srcs = ['b.h', 'a.h'])",
        "genaquery(name = 'q', expression = 'set(//pkg:root //pkg:other)', scope = [':other',"
            + " ':root'], output = '"
            + format
            + "')");
    ByteString first = result("//pkg:q");
    assertThat(result("//pkg:q")).isEqualTo(first);
    getSkyframeExecutor().resetEvaluator();
    assertThat(result("//pkg:q")).isEqualTo(first);
  }

  @Test
  public void testScopeExcludesOtherPreviouslyAnalyzedTargets(@TestParameter boolean strict)
      throws Exception {
    writeActions();
    write("other/BUILD", "filegroup(name = 'outside')");
    buildTarget("//other:outside");
    report(
        "scope",
        "//pkg:root union //other:outside",
        "strict = " + (strict ? "True" : "False") + ", output = 'proto'");
    if (strict) {
      failure("//reports/scope:q", "is not within the scope of the query");
    } else {
      var graph = ActionGraphContainer.parseFrom(result("//reports/scope:q"));
      assertThat(graph.getActionsCount()).isEqualTo(1);
      events.assertContainsWarning("is not within the scope of the query");
    }
  }

  @Test
  @TestParameters("{option: '--output=text', message: 'option --output is not allowed'}")
  @TestParameters(
      "{option: '--universe_scope=//pkg:root', message: 'option --universe_scope is not allowed'}")
  @TestParameters("{option: '--skyframe_state', message: 'option --skyframe_state is not allowed'}")
  @TestParameters(
      "{option: '--experimental_explicit_aspects', message: 'option --experimental_explicit_aspects"
          + " is not allowed'}")
  @TestParameters(
      "{option: '--noinclude_pruned_inputs', message: '--noinclude_pruned_inputs is not allowed'}")
  public void testDisallowedOptions(String option, String message) throws Exception {
    writeActions();
    report("options", "//pkg:root", "opts = ['" + option + "']");
    failure("//reports/options:q", message);
  }

  @Test
  public void testMixedQueryRulesRemainQueryable() throws Exception {
    writeActions();
    write(
        "reports/BUILD",
        """
        gencquery(name = "configured", expression = "//pkg:root", scope = ["//pkg:root"])
        genaquery(name = "actions", expression = "deps(//reports:configured)", scope = [":configured"], output = "proto")
        gencquery(name = "nested", expression = "labels(scope, //reports:actions)", scope = [":actions"], output = "starlark")
        """);
    var graph = ActionGraphContainer.parseFrom(result("//reports:actions"));
    assertThat(graph.getActionsList().stream().map(a -> a.getMnemonic())).contains("ExampleAction");
    assertThat(result("//reports:nested").toStringUtf8()).isEqualTo("@@//reports:configured\n");
  }

  @Test
  public void testEmptyScopeProducesEmptyGraph() throws Exception {
    write(
        "pkg/BUILD",
        "genaquery(name = 'q', expression = 'set()', scope = [], output = 'jsonproto')");
    assertThat(parse("jsonproto", result("//pkg:q")))
        .isEqualTo(ActionGraphContainer.getDefaultInstance());
  }

  @Test
  public void testNonIncrementalModeFailsClearly() throws Exception {
    addOptions(
        "--nokeep_state_after_build", "--discard_analysis_cache", "--notrack_incremental_state");
    write("pkg/BUILD", "genaquery(name = 'q', expression = 'set()', scope = [])");
    failure("//pkg:q", "genaquery requires --track_incremental_state");
  }

  @Test
  public void testAttachedAspectsAreScopedAndOptional(@TestParameter boolean includeAspects)
      throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _aspect(target, ctx):
            out = ctx.actions.declare_file(target.label.name + ".aspect")
            ctx.actions.write(out, "aspect")
            return []
        inspect = aspect(implementation = _aspect)
        def _leaf(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.write(out, "leaf")
            return [DefaultInfo(files = depset([out]))]
        leaf = rule(implementation = _leaf)
        def _root(ctx):
            return []
        root = rule(implementation = _root, attrs = {"dep": attr.label(aspects = [inspect])})
        """);
    write(
        "pkg/BUILD",
        "load(':rules.bzl', 'leaf', 'root')",
        "leaf(name = 'leaf')",
        "root(name = 'root', dep = ':leaf')");
    report(
        "aspects",
        "deps(//pkg:root)",
        "output = 'proto', opts = ['--include_file_write_contents', '--include_aspects="
            + includeAspects
            + "']");
    var graph = ActionGraphContainer.parseFrom(result("//reports/aspects:q"));
    assertThat(graph.getActionsList().stream().map(a -> a.getFileContents()))
        .containsExactlyElementsIn(
            includeAspects ? java.util.List.of("leaf", "aspect") : java.util.List.of("leaf"));
    write(
        "reports/leaf/BUILD",
        "genaquery(name = 'q', expression = '//pkg:leaf', scope = ['//pkg:leaf'], output ="
            + " 'proto')");
    assertThat(ActionGraphContainer.parseFrom(result("//reports/leaf:q")).getActionsCount())
        .isEqualTo(1);
  }

  @Test
  public void testRunfilesManifestContentsCannotDependOnGeneratedSymlinks(
      @TestParameter({"files", "symlinks", "root_symlinks"}) String location) throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            exe = ctx.actions.declare_file(ctx.label.name + ".sh")
            ctx.actions.write(exe, "exit 0", is_executable = True)
            link = ctx.actions.declare_symlink(ctx.label.name + ".link")
            ctx.actions.symlink(output = link, target_path = "destination")
            return [DefaultInfo(executable = exe, runfiles = ctx.runfiles(%s)),
                    OutputGroupInfo(links = depset([link]))]
        producer = rule(implementation = _impl, executable = True)
        """
            .formatted(
                location.equals("files") ? "files = [link]" : location + " = {\"alias\": link}"));
    write("pkg/BUILD", "load(':rules.bzl', 'producer')", "producer(name = 'root')");
    report(
        "manifest",
        "mnemonic('SourceSymlinkManifest', //pkg:root)",
        "output = 'proto', opts = ['--include_file_write_contents']");
    failure(
        "//reports/manifest:q",
        "cannot report file contents for a runfiles manifest with symlink artifacts");
    addOptions("--output_groups=links");
    buildTarget("//pkg:root");
    addOptions("--output_groups=default");
    failure(
        "//reports/manifest:q",
        "cannot report file contents for a runfiles manifest with symlink artifacts");
    report(
        "commands",
        "mnemonic('SourceSymlinkManifest', //pkg:root)",
        "output = 'commands', opts = ['--include_file_write_contents']");
    assertThat(result("//reports/commands:q").isEmpty()).isTrue();
  }

  @Test
  public void testGeneratedTemplatesAreDescribedWithoutExecution() throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            template = ctx.actions.declare_file("template")
            ctx.actions.write(template, "generated template")
            output = ctx.actions.declare_file("expanded")
            ctx.actions.expand_template(template = template, output = output, substitutions = {})
            return [DefaultInfo(files = depset([output]))]
        producer = rule(implementation = _impl)
        """);
    write("pkg/BUILD", "load(':rules.bzl', 'producer')", "producer(name = 'root')");
    report("generated", "mnemonic('TemplateExpand', //pkg:root)", "output = 'proto'");
    var graph = ActionGraphContainer.parseFrom(result("//reports/generated:q"));
    assertThat(graph.getActionsCount()).isEqualTo(1);
    assertThat(graph.getActions(0).getTemplateContent()).contains("template");
    assertThat(graph.getActions(0).getTemplateContent()).doesNotContain("generated template");
  }

  @Test
  public void testWildcardPatternsAreRejected(
      @TestParameter({"//pkg:*", "//pkg:all", "//pkg/..."}) String pattern) throws Exception {
    writeActions();
    report("wildcard", pattern, "");
    failure("//reports/wildcard:q", "target patterns are not allowed in genaquery");
  }

  @Test
  public void testConcreteAllTargetAndRepositoryRelativeLabels() throws Exception {
    write("BUILD", "genrule(name = 'all', outs = ['out'], cmd = 'exit 1')");
    write(
        "reports/BUILD",
        """
        genaquery(name = "q", expression = "//:all", scope = ["//:all"], output = "proto")
        genaquery(name = "relative", expression = ":all", scope = ["//:all"], output = "proto")
        """);
    assertThat(ActionGraphContainer.parseFrom(result("//reports:q")).getActions(0).getMnemonic())
        .isEqualTo("Genrule");
    // Relative :all is a wildcard in the query language, even when a concrete target exists.
    failure("//reports:relative", "target patterns are not allowed");
    write("BUILD", "genrule(name = 'root', outs = ['out'], cmd = 'exit 1')");
    write(
        "reports/BUILD",
        "genaquery(name = 'q', expression = ':root', scope = ['//:root'], output = 'proto')");
    assertThat(ActionGraphContainer.parseFrom(result("//reports:q")).getTargets(0).getLabel())
        .isEqualTo("//:root");
  }

  @Test
  public void testScopeAnalysisFailuresAreNotSuppressedByNonStrictMode() throws Exception {
    write(
        "pkg/BUILD",
        """
        filegroup(name = "broken", srcs = ["//missing:target"])
        genaquery(name = "q", expression = "set()", scope = [":broken"], strict = False)
        """);
    failure("//pkg:q", "no such package 'missing'");
    write(
        "pkg/BUILD", "genaquery(name = 'q', expression = 'set()', scope = [':q'], strict = False)");
    failure("//pkg:q", "cycle in dependency graph");
  }

  @Test
  public void testLargeJsonReportCanBeCompressedAndInvalidated() throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            outputs = []
            for i in range(128):
                out = ctx.actions.declare_file(str(i))
                ctx.actions.write(out, ctx.attr.value * 16384)
                outputs.append(out)
            return [DefaultInfo(files = depset(outputs))]
        producer = rule(implementation = _impl, attrs = {"value": attr.string()})
        """);
    report(
        "large",
        "//pkg:root",
        "output = 'jsonproto', compressed_output = True, opts = ['--include_file_write_contents']");
    for (String value : new String[] {"a", "b"}) {
      write(
          "pkg/BUILD",
          "load(':rules.bzl', 'producer')",
          "producer(name = 'root', value = '" + value + "')");
      ByteString compressed = result("//reports/large:q");
      try (var in = new GZIPInputStream(compressed.newInput())) {
        var graph = parse("jsonproto", ByteString.readFrom(in));
        assertThat(graph.getActionsCount()).isEqualTo(128);
        assertThat(graph.getActionsList().stream().map(a -> a.getFileContents()).distinct())
            .containsExactly(value.repeat(16384));
      }
    }
  }

  @Test
  public void testParameterFileContentsFollowArtifactOwnersAndIgnoreActionOrder(
      @TestParameter({"text", "proto"}) String format) throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _params(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".params")
            args = ctx.actions.args()
            args.add("--example-argument")
            ctx.actions.write(out, args)
            return [DefaultInfo(files = depset([out]))]
        params = rule(implementation = _params)
        def _consumer(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run_shell(inputs = ctx.files.params, outputs = [out], command = "exit 1", mnemonic = "ConsumeParams")
            return [DefaultInfo(files = depset([out]))]
        consumer = rule(implementation = _consumer, attrs = {"params": attr.label(allow_files = True)})
        """);
    write(
        "pkg/BUILD",
        "load(':rules.bzl', 'consumer', 'params')",
        "params(name = 'z_params')",
        "consumer(name = 'root', params = ':z_params')");
    // The producer is outside the selected target set and filtered out of the action set.
    report(
        "params",
        "mnemonic('ConsumeParams', //pkg:root)",
        "output = '" + format + "', opts = ['--noinclude_commandline', '--include_param_files']");
    ByteString output = result("//reports/params:q");
    if (format.equals("text")) {
      assertThat(output.toStringUtf8()).contains("Params File Content");
      assertThat(output.toStringUtf8()).contains("--example-argument");
    } else {
      var action = parse(format, output).getActions(0);
      assertThat(action.getArgumentsList()).contains("exit 1");
      assertThat(action.getParamFilesCount()).isEqualTo(1);
      assertThat(action.getParamFiles(0).getArgumentsList()).containsExactly("--example-argument");
    }
  }

  @Test
  public void testSplitConfigurationsAndAttachedAspectsKeepTheirIdentities() throws Exception {
    write(
        "pkg/rules.bzl",
        """
        def _split(settings, attr):
            return [{"//command_line_option:platform_suffix": suffix} for suffix in ["one", "two"]]
        split = transition(implementation = _split, inputs = [], outputs = ["//command_line_option:platform_suffix"])
        def _aspect(target, ctx):
            out = ctx.actions.declare_file(target.label.name + ".aspect")
            ctx.actions.write(out, "aspect")
            return []
        inspect = aspect(implementation = _aspect)
        def _leaf(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.write(out, "leaf")
            return [DefaultInfo(files = depset([out]))]
        leaf = rule(implementation = _leaf)
        def _root(ctx):
            return []
        root = rule(implementation = _root, attrs = {
            "dep": attr.label(cfg = split, aspects = [inspect]),
            "_allowlist_function_transition": attr.label(default = "@bazel_tools//tools/allowlists/function_transition_allowlist"),
        })
        """);
    write(
        "pkg/BUILD",
        "load(':rules.bzl', 'leaf', 'root')",
        "leaf(name = 'leaf')",
        "root(name = 'root', dep = ':leaf')");
    report("split", "//pkg:leaf", "output = 'proto', opts = ['--include_file_write_contents']");
    var graph = ActionGraphContainer.parseFrom(result("//reports/split:q"));
    assertThat(graph.getActionsList().stream().map(a -> a.getFileContents()))
        .containsExactly("leaf", "leaf", "aspect", "aspect");
    assertThat(graph.getActionsList().stream().map(a -> a.getConfigurationId()).distinct())
        .hasSize(2);
    assertThat(
            graph.getActionsList().stream().flatMap(a -> a.getOutputIdsList().stream()).distinct())
        .hasSize(4);
  }

  @Test
  public void testExternalRepositoryMappingAndCanonicalOutput() throws Exception {
    write(
        "MODULE.bazel",
        "bazel_dep(name = 'other', repo_name = 'renamed')",
        "local_path_override(module_name = 'other', path = 'other')");
    write("other/MODULE.bazel", "module(name = 'other')");
    write(
        "other/pkg/BUILD",
        """
        genrule(name = "root", outs = ["out"], cmd = "exit 1")
        genaquery(name = "q", expression = "//pkg:root", scope = [":root"], output = "proto")
        """);
    var graph = ActionGraphContainer.parseFrom(result("@renamed//pkg:q"));
    assertThat(graph.getTargets(0).getLabel()).isEqualTo("@@other+//pkg:root");
    write(
        "reports/BUILD",
        "genaquery(name = 'q', expression = '@renamed//pkg:root', scope = ['@renamed//pkg:root'],"
            + " output = 'proto')");
    assertThat(ActionGraphContainer.parseFrom(result("//reports:q")).getTargets(0).getLabel())
        .isEqualTo("@@other+//pkg:root");
  }

  @Test
  public void testCommandsUseAnalysisExecPathsWithPathStrippingEnabled() throws Exception {
    addOptions("--experimental_output_paths=strip");
    write(
        "pkg/rules.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            args = ctx.actions.args()
            args.add(out)
            ctx.actions.run_shell(outputs = [out], arguments = [args], command = "exit 1",
                                  execution_requirements = {"supports-path-mapping": "1"})
            return [DefaultInfo(files = depset([out]))]
        producer = rule(implementation = _impl)
        """);
    write("pkg/BUILD", "load(':rules.bzl', 'producer')", "producer(name = 'root')");
    report("paths", "//pkg:root", "output = 'proto'");
    var action = ActionGraphContainer.parseFrom(result("//reports/paths:q")).getActions(0);
    var artifact =
        Iterables.getOnlyElement(getFilesToBuild(getConfiguredTarget("//pkg:root")).toList());
    assertThat(action.getArgumentsList()).contains(artifact.getExecPathString());
  }

  private ByteString result(String target) throws Exception {
    buildTarget(target);
    return readContentAsByteArray(Iterables.getOnlyElement(getArtifacts(target)));
  }

  private void failure(String target, String message) throws Exception {
    Class<? extends Throwable> exception =
        keepGoing ? BuildFailedException.class : ViewCreationFailedException.class;
    assertThrows(exception, () -> buildTarget(target));
    events.assertContainsError(message);
  }

  private static ActionGraphContainer parse(String format, ByteString bytes) throws Exception {
    var builder = ActionGraphContainer.newBuilder();
    switch (format) {
      case "proto" -> builder.mergeFrom(bytes);
      case "streamed_proto" -> {
        try (var input = bytes.newInput()) {
          while (builder.mergeDelimitedFrom(input)) {}
        }
      }
      case "textproto" -> TextFormat.merge(bytes.toStringUtf8(), builder);
      case "jsonproto" -> JsonFormat.parser().merge(bytes.toStringUtf8(), builder);
      default -> throw new IllegalArgumentException(format);
    }
    return builder.build();
  }
}
