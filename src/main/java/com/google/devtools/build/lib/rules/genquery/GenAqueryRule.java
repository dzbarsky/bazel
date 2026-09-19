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

/** Definition of the genaquery rule. */
public final class GenAqueryRule implements RuleDefinition {
  @Override
  public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
    return builder
        // Scope may include tests, so preserve their incoming options as test_suite does.
        .requiresConfigurationFragments(TestConfiguration.class)
        .setMissingFragmentPolicy(TestConfiguration.class, MissingFragmentPolicy.IGNORE)
        /* <!-- #BLAZE_RULE(genaquery).ATTRIBUTE(scope) -->
        The roots of the action query graph. These targets are analyzed in this rule's
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
        /* <!-- #BLAZE_RULE(genaquery).ATTRIBUTE(expression) -->
        The <a href="${link aquery}">aquery expression</a> to evaluate. Labels are relative to the
        root of this rule's repository, not its package. For example, <code>:b</code> in
        <code>a/BUILD</code> refers to <code>//:b</code>. Wildcard and recursive target patterns
        such as <code>//pkg:*</code>, <code>//pkg:all</code>, and <code>//pkg/...</code> are not allowed.
        As with genquery, an absolute spelling such as <code>//pkg:all</code> can instead name an
        individual existing target.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("expression", STRING).mandatory())
        /* <!-- #BLAZE_RULE(genaquery).ATTRIBUTE(strict) -->
        If true, referencing a target outside the configured scope is an error. If false, such
        references produce a warning and are skipped, while the rest of the query completes.
        This does not suppress errors analyzing the scope or evaluating the query and formatting.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("strict", BOOLEAN).value(true))
        /* <!-- #BLAZE_RULE(genaquery).ATTRIBUTE(output) -->
        The <a href="${link aquery#output}">aquery output format</a>:
        <code>text</code> (the default), <code>commands</code>, <code>summary</code>,
        <code>proto</code>, <code>streamed_proto</code>, <code>textproto</code>, or
        <code>jsonproto</code>. Protocol outputs use Bazel's <code>analysis_v2.proto</code>
        schema. Labels use canonical repository names, as with genquery.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(
            attr("output", STRING)
                .value("text")
                .allowedValues(
                    new AllowedValueSet(
                        "text",
                        "commands",
                        "summary",
                        "proto",
                        "streamed_proto",
                        "textproto",
                        "jsonproto")))
        /* <!-- #BLAZE_RULE(genaquery).ATTRIBUTE(opts) -->
        Additional <a href="${link aquery#options}">aquery options</a>, using their
        command-line defaults. Dependency filters such as <code>--noimplicit_deps</code>
        and <code>--notool_deps</code> apply when selecting targets. Formatting options
        include <code>--noinclude_artifacts</code>, <code>--noinclude_commandline</code>,
        <code>--include_param_files</code>, and <code>--include_file_write_contents</code>.
        Parameter-file contents are obtained from their generating actions without executing them.
        <code>--include_param_files</code> implies <code>--include_commandline</code>.
        Options cannot change the build configuration, scope, expression, or output path.
        <code>--keep_going</code>, <code>--universe_scope</code>, <code>--infer_universe_scope</code>,
        <code>--query_file</code>, <code>--output_file</code>, and <code>--skyframe_state</code>
        are not allowed. Use the <code>output</code> attribute instead of <code>--output</code>.
        <code>--experimental_explicit_aspects</code> is not supported. Attached aspect actions are
        included by default; use <code>--noinclude_aspects</code> to omit them.
        <code>--noinclude_pruned_inputs</code> is not allowed: reports describe analysis inputs,
        including inputs an earlier execution might have pruned.
        <!-- #END_BLAZE_RULE.ATTRIBUTE --> */
        .add(attr("opts", STRING_LIST))
        /* <!-- #BLAZE_RULE(genaquery).ATTRIBUTE(compressed_output) -->
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
        .name("genaquery")
        .ancestors(BaseRuleClasses.NativeActionCreatingRule.class)
        .factoryClass(GenAquery.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = genaquery, FAMILY = General)[GENERIC_RULE] -->
<p>
  <code>genaquery()</code> evaluates an <a href="${link aquery}">action query</a> during analysis
  and writes the result to a file named after the rule. Other rules can consume this file as a
  normal build input. Use it for compiler-command inventories, action-input and output manifests,
  or reports about how a configuration builds its targets.
</p>
<p>
  The query selects configured targets, then reports the actions they register. Use
  <code>deps(...)</code> to include actions from dependencies. The <code>inputs</code>,
  <code>outputs</code>, and <code>mnemonic</code> filters can be nested around the target
  expression. They cannot appear inside target query functions or set operations: those operate
  on targets, not actions. Functions unsupported by aquery, such as <code>labels</code> and
  <code>visible</code>, are also unsupported here. This rule does not provide a Starlark output mode.
</p>
<p>
  Only the configured transitive closure of <code>scope</code> is visible, regardless of previous
  builds. Labels in <code>expression</code> are relative to the root of this rule's repository.
  Scope roots use the build's configuration, including selected <code>select()</code> branches
  and configuration transitions. They must analyze successfully, but their actions do not run.
  Wildcard target patterns are not allowed. This rule requires
  <code>--track_incremental_state</code> (the default) to retain configured dependency edges.
</p>
<p>
  Targets are ordered by canonical label, configuration checksum, and execution-platform identity;
  actions retain their analysis registration order, and attached aspects are ordered structurally.
  Protocol identifiers are assigned in this order. A top-level <code>somepath</code> target
  expression retains dependency path order. Functions such as <code>some</code> and
  <code>somepath</code> retain aquery's freedom to choose any matching target or path.
</p>
<p>
  Reports describe analysis, using unstripped exec paths rather than execution path mapping.
  They do not describe execution results: tree-artifact contents are not expanded, and
  generated templates are represented by their artifact description. Source-template contents
  are tracked inputs: edits invalidate the report. Parameter files and file-write contents are
  expanded from their action definitions when requested. File contents for runfiles manifests
  with symlink artifacts are unavailable during analysis and requesting them is an error.
  Execution-only environment values are
  not available. Large reports can use <code>compressed_output</code>, or omit command lines
  and artifact listings using <code>opts</code>.
</p>
<p>
  Reports can contain absolute paths and action hashes tied to local directories. Remote
  analysis and execution cache entries that depend on a report are reused only within the
  same workspace and output roots. Scoped targets and unrelated targets can still reuse
  cached analysis across roots.
</p>
<h4 id="genaquery_examples">Examples</h4>
<p>To report compilation actions in a target's configured dependency graph:</p>
<pre class="code">
genaquery(
    name = "compile-actions.txt",
    expression = 'mnemonic("CppCompile", deps(//app:app))',
    scope = ["//app:app"],
)
</pre>
<p>To produce a machine-readable action graph for downstream tools:</p>
<pre class="code">
genaquery(
    name = "actions.json",
    expression = "deps(//app:app)",
    scope = ["//app:app"],
    output = "jsonproto",
    opts = ["--include_param_files"],
)
</pre>
<p>To list commands for actions producing object files:</p>
<pre class="code">
genaquery(
    name = "compile-commands.txt",
    expression = 'outputs(".*[.]o", deps(//app:app))',
    scope = ["//app:app"],
    output = "commands",
)
</pre>
<!-- #END_BLAZE_RULE -->*/
