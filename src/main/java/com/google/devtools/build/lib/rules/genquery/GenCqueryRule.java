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

package com.google.devtools.build.lib.rules.genquery;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.GENQUERY_SCOPE_TYPE_LIST;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.STRING;
import static com.google.devtools.build.lib.packages.Types.STRING_LIST;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.packages.Attribute.AllowedValueSet;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy.MissingFragmentPolicy;
import com.google.devtools.build.lib.packages.RuleClass;

/** Definition of the gencquery rule. */
public final class GenCqueryRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
    return builder
        // Scope may include tests, so preserve their incoming options as test_suite does.
        .requiresConfigurationFragments(TestConfiguration.class)
        .setMissingFragmentPolicy(TestConfiguration.class, MissingFragmentPolicy.IGNORE)
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(scope) -->
        The roots of the configured query graph. These targets are analyzed in this rule's
        configuration, including their configuration transitions, but their actions are not run.
        Actions belonging only to the scope are also excluded from build action-conflict checks.
        The query can only visit these targets and their configured transitive dependencies.
        Scope targets and their selected dependencies must analyze successfully, even if
        <code>strict = False</code> or the query does not reference them.
        Incompatible targets can be inspected; their dependencies are limited to those Bazel
        analyzes when determining incompatibility. Scope targets do not impose their visibility,
        compatibility, coverage, or extra-action requirements on consumers of the query output.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        // The implementation analyzes scope explicitly, without propagating its prerequisites.
        .add(attr("scope", GENQUERY_SCOPE_TYPE_LIST).mandatory().legacyAllowAnyFileType())
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(expression) -->
        The <a href="${link cquery}">cquery expression</a> to evaluate. Labels are relative to the
        root of this rule's repository, not its package. For example, <code>:b</code> in
        <code>a/BUILD</code> refers to <code>//:b</code>. Wildcard and recursive target patterns
        such as <code>//pkg:*</code>, <code>//pkg:all</code>, and <code>//pkg/...</code> are not allowed.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("expression", STRING).mandatory())
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(strict) -->
        If true, referencing a target outside the configured scope is an error. If false, such
        references produce a warning and are skipped, while the rest of the query completes.
        This does not suppress errors analyzing the scope or evaluating the query and formatter.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("strict", BOOLEAN).value(true))
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(output) -->
        The output format: <code>label</code> (the default), <code>label_kind</code>, or
        <code>starlark</code>. The <code>label</code> format includes each target's label and
        configuration identifier; <code>label_kind</code> also includes its kind.
        The <code>starlark_expr</code> and <code>starlark_file</code> attributes can only be
        specified when this is <code>starlark</code>.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("output", STRING)
                .value("label")
                .allowedValues(new AllowedValueSet("label", "label_kind", "starlark")))
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(opts) -->
        Additional <a href="${link cquery#options}">cquery options</a>. Options not specified here
        have their command-line defaults. Dependency filters such as
        <code>--noimplicit_deps</code> and <code>--notool_deps</code> have their cquery meanings.
        Options cannot change the build configuration, scope, input expression, or output path.
        <code>--keep_going</code>, <code>--universe_scope</code>, <code>--infer_universe_scope</code>,
        <code>--query_file</code>, and <code>--output_file</code> are not allowed.
        <code>--transitions</code> and <code>--show_config_fragments</code> are not supported.
        Use the <code>output</code>, <code>starlark_expr</code>, and <code>starlark_file</code>
        attributes instead of <code>--output</code>, <code>--starlark:expr</code>, and
        <code>--starlark:file</code>, which are not allowed here.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("opts", STRING_LIST))
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(starlark_expr) -->
        A Starlark expression evaluated for each configured target, equivalent to cquery's
        <code>--starlark:expr</code>. Requires <code>output = "starlark"</code>.
        The target is bound to <code>target</code>; the
        <a href="${link cquery#cquery-starlark}">cquery Starlark built-ins</a>, including
        <code>providers(target)</code> and <code>build_options(target)</code>, are available.
        Mutually exclusive with <code>starlark_file</code>. If neither is set, Starlark output uses
        <code>str(target.label)</code>.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("starlark_expr", STRING))
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(starlark_file) -->
        The label of a source file defining a one-argument <code>format(target)</code> function,
        for example <code>:format.cquery</code> or <code>//tools:format.cquery</code>. This is the
        label-valued equivalent of cquery's <code>--starlark:file</code> flag. Requires
        <code>output = "starlark"</code>. Mutually exclusive with <code>starlark_expr</code>.
        The file has the same built-ins as <code>starlark_expr</code>. Changes to the file
        invalidate the query. Generated files are not supported because formatting happens
        during analysis, before actions execute. As with cquery, <code>load()</code> is not supported.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("starlark_file", LABEL).legacyAllowAnyFileType().singleArtifact())
        /* <!-- #BLAZE_RULE(gencquery).ATTRIBUTE(compressed_output) -->
        If <code>True</code>, query output is written in GZIP file format. As with
        <a href="${link genquery.compressed_output}">genquery</a>, this can avoid spikes in
        memory use for large results. Bazel already internally compresses query outputs greater
        than 2<sup>20</sup> bytes regardless of this setting, so it may not reduce retained heap.
        However, it skips decompression when writing the output file, which can be memory-intensive.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("compressed_output", BOOLEAN).value(false))
        .build();
  }

  @Override
  public Metadata getMetadata() {
    return Metadata.builder()
        .name("gencquery")
        .ancestors(BaseRuleClasses.NativeActionCreatingRule.class)
        .factoryClass(GenCquery.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = gencquery, FAMILY = General)[GENERIC_RULE] -->
<p>
  <code>gencquery()</code> evaluates a <a href="${link cquery}">configured query</a> during
  analysis and writes its result to a file named after the rule. Other rules can consume this
  file as an ordinary build input. Use it for configuration-specific dependency manifests,
  output-file inventories, or reports based on providers and build options.
</p>
<p>
  Unlike <a href="${link genquery}">genquery</a>, it follows the selected branches of
  <code>select()</code> and distinguishes instances of a target in different configurations.
  The build's configuration
  applies to the scope roots; query options cannot start a separate build or change that
  configuration. Scope targets must analyze successfully, but do not need to execute successfully.
</p>
<p>
  Only the configured transitive closure of <code>scope</code> is visible, even if other targets
  were analyzed by an earlier build. Mentioning the expression's labels in <code>scope</code>
  is the simplest way to keep a query within that closure. Wildcard target patterns are not
  allowed: both the query and its declared dependencies must stay within this explicit scope.
</p>
<p>
  Results are ordered lexicographically by label and configuration checksum before formatting.
  Multiple configurations or execution-platform instances of a target produce separate results,
  even if their formatted text is identical. <code>config()</code> preserves all instances in
  its selected configuration. Functions such as <code>some</code> and <code>somepath</code> retain cquery's
  freedom to choose any matching target or path. Starlark formatting errors fail the build.
  This rule requires <code>--track_incremental_state</code> (the default) to retain configured
  dependency edges.
</p>
<h4 id="gencquery_examples">Examples</h4>
<p>This writes the selected dependency labels and their configuration identifiers:</p>
<pre class="code">
gencquery(
    name = "app-deps.txt",
    expression = "deps(//app:app)",
    scope = ["//app:app"],
)
</pre>
<p>To format only the labels with a Starlark expression:</p>
<pre class="code">
gencquery(
    name = "app-labels.txt",
    expression = "deps(//app:app)",
    scope = ["//app:app"],
    output = "starlark",
    starlark_expr = "str(target.label)",
)
</pre>
<p>To list a target's output paths using a source file as the formatter:</p>
<pre class="code">
gencquery(
    name = "app-files.txt",
    expression = "//app:app",
    scope = ["//app:app"],
    output = "starlark",
    starlark_file = ":files.cquery",
)
</pre>
<p>The source file <code>files.cquery</code> can contain:</p>
<pre class="code">
def format(target):
    return "\n".join(sorted([f.path for f in target.files.to_list()]))
</pre>
<!-- #END_BLAZE_RULE -->*/
