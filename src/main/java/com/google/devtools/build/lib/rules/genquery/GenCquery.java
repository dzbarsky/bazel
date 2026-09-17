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

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.test.InstrumentedFilesInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.cquery.CqueryThreadsafeCallback;
import com.google.devtools.build.lib.query2.cquery.LabelAndConfigurationOutputFormatterCallback;
import com.google.devtools.build.lib.query2.cquery.StarlarkOutputFormatterCallback;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.syntax.ParserInput;

/**
 * Evaluates cquery over an explicitly scoped configured graph and registers a file write action.
 */
public final class GenCquery implements RuleConfiguredTargetFactory {
  private static final Comparator<CqueryNode> TARGET_ORDER =
      Comparator.comparing((CqueryNode target) -> target.getOriginalLabel().toString())
          .thenComparing(
              CqueryNode::getConfigurationChecksum,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(
              CqueryNode::getLookupKey,
              (left, right) -> {
                // Compare the two kinds of query node structurally. Stringifying an aspect key
                // can exponentially expand its shared base-aspect graph.
                if (left instanceof AspectKey leftAspect) {
                  return right instanceof AspectKey rightAspect
                      ? AspectKey.ORDERING.compare(leftAspect, rightAspect)
                      : 1;
                }
                return right instanceof AspectKey
                    ? -1
                    : ConfiguredTargetKey.ORDERING.compare(
                        (ConfiguredTargetKey) left, (ConfiguredTargetKey) right);
              });

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    String outputFormat = ruleContext.attributes().get("output", Type.STRING);
    String expr = ruleContext.attributes().get("starlark_expr", Type.STRING);
    boolean hasExpr = ruleContext.attributes().isAttributeValueExplicitlySpecified("starlark_expr");
    boolean hasFile = ruleContext.attributes().get("starlark_file", BuildType.LABEL) != null;
    if ((hasExpr || hasFile) && !outputFormat.equals("starlark")) {
      ruleContext.attributeError(
          hasExpr ? "starlark_expr" : "starlark_file", "requires output = \"starlark\"");
      return null;
    }
    if (hasExpr && hasFile) {
      ruleContext.ruleError("starlark_expr and starlark_file are mutually exclusive");
      return null;
    }
    Artifact source = ruleContext.getPrerequisiteArtifact("starlark_file");
    if (source != null && !source.isSourceArtifact()) {
      ruleContext.attributeError("starlark_file", "must be a source file, not a generated file");
      return null;
    }

    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(CqueryOptions.class)
            .allowResidue(false)
            .withConversionContext(
                Label.RepoContext.of(
                    ruleContext.getRepository(),
                    ruleContext.getRule().getPackageMetadata().repositoryMapping()))
            .build();
    try {
      parser.parse(ruleContext.attributes().get("opts", Types.STRING_LIST));
      for (String forbidden :
          ImmutableList.of(
              "output",
              "universe_scope",
              "infer_universe_scope",
              "query_file",
              "output_file",
              "transitions",
              "show_config_fragments",
              "starlark:expr",
              "starlark:file")) {
        if (parser.containsExplicitOption(forbidden)) {
          ruleContext.attributeError("opts", "option --" + forbidden + " is not allowed");
          return null;
        }
      }
      parser.parse("--output=" + outputFormat, "--starlark:expr=" + expr);
    } catch (OptionsParsingException e) {
      ruleContext.attributeError("opts", "error while parsing cquery options: " + e.getMessage());
      return null;
    }
    CqueryOptions options = parser.getOptions(CqueryOptions.class);

    SkyFunction.Environment env = ruleContext.getAnalysisEnvironment().getSkyframeEnv();
    GenCqueryScope scope;
    try {
      scope =
          (GenCqueryScope)
              env.getValueOrThrow(
                  GenCqueryScope.Key.create(
                      ruleContext.getPrerequisiteConfiguredTargets("scope").stream()
                          .map(
                              dep ->
                                  ConfiguredTargetKey.fromConfiguredTarget(
                                      dep.getConfiguredTarget()))
                          .distinct()
                          .collect(ImmutableList.toImmutableList())),
                  GenCqueryScope.ScopeException.class);
    } catch (GenCqueryScope.ScopeException e) {
      ruleContext.ruleError(e.getMessage());
      return null;
    }
    if (scope == null) {
      return null;
    }

    ParserInput starlarkFile = null;
    if (source != null) {
      // Reading a source during analysis must declare a Skyframe dependency on its contents.
      FileValue file =
          (FileValue)
              env.getValue(
                  FileValue.key(
                      RootedPath.toRootedPath(
                          source.getRoot().getRoot(), source.getRootRelativePath())));
      if (file == null) {
        return null;
      }
      if (!file.isFile()) {
        ruleContext.attributeError("starlark_file", "must be an existing regular file");
        return null;
      }
      try {
        // Use the same byte-preserving strings as BUILD files, labels, and provider values.
        starlarkFile =
            ParserInput.fromCharArray(
                FileSystemUtils.readContentAsLatin1(source.getPath()), source.getExecPathString());
      } catch (IOException e) {
        ruleContext.attributeError("starlark_file", "cannot read file: " + e.getMessage());
        return null;
      }
    }

    StoredEventHandler events = new StoredEventHandler();
    GenQueryResult result;
    try (ScopedEnvironment queryEnvironment =
            new ScopedEnvironment(ruleContext, scope, options, events);
        GenQueryOutputStream out =
            new GenQueryOutputStream(
                ruleContext.attributes().get("compressed_output", Type.BOOLEAN))) {
      Function<BuildConfigurationKey, BuildConfigurationValue> configurations =
          key -> (BuildConfigurationValue) scope.getValue(key);
      // Write Bazel's internal byte strings without applying another UTF-8 encoding.
      CqueryThreadsafeCallback formatter =
          options.getOutputFormat().equals("starlark")
              ? new StarlarkOutputFormatterCallback(
                  events,
                  options,
                  out,
                  configurations,
                  queryEnvironment.getAccessor(),
                  ruleContext.getAnalysisEnvironment().getStarlarkSemantics(),
                  starlarkFile,
                  ISO_8859_1)
              : new LabelAndConfigurationOutputFormatterCallback(
                  events,
                  options,
                  out,
                  configurations,
                  queryEnvironment.getAccessor(),
                  options.getOutputFormat().equals("label_kind"),
                  queryEnvironment.getLabelPrinter(),
                  ISO_8859_1);
      QueryExpression expression =
          QueryExpression.parse(
              ruleContext.attributes().get("expression", Type.STRING), queryEnvironment);
      var targets = QueryUtil.newOrderedAggregateAllOutputFormatterCallback(queryEnvironment);
      queryEnvironment.evaluateQuery(expression, targets);
      formatter.start();
      formatter.processOutputAndWrite(
          ImmutableList.sortedCopyOf(TARGET_ORDER, targets.getResult()));
      formatter.close(/* failFast= */ events.hasErrors());
      out.close();
      result = out.getResult();
    } catch (QueryException | QuerySyntaxException | IOException e) {
      ruleContext.ruleError("cquery failed: " + e.getMessage());
      return null;
    } finally {
      events.replayOn(ruleContext.getAnalysisEnvironment().getEventHandler());
    }
    if (events.hasErrors()) {
      ruleContext.ruleError("cquery output could not be evaluated");
      return null;
    }

    Artifact output = ruleContext.createOutputArtifact();
    ruleContext.registerAction(
        new GenQuery.QueryResultAction(ruleContext.getActionOwner(), output, result));
    var files = NestedSetBuilder.create(Order.STABLE_ORDER, output);
    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(files)
        // Scope dependencies contribute query metadata, not artifacts for consumers to build.
        .addNativeDeclaredProvider(InstrumentedFilesInfo.EMPTY)
        .setPropagateExtraActionArtifacts(false)
        .addProvider(
            RunfilesProvider.class,
            RunfilesProvider.simple(
                new Runfiles.Builder(ruleContext.getWorkspaceName())
                    .addTransitiveArtifacts(files)
                    .build()))
        .addOutputGroup(
            OutputGroupInfo.VALIDATION_TRANSITIVE, NestedSetBuilder.emptySet(Order.STABLE_ORDER))
        .build();
  }

  private static ImmutableMap<String, BuildConfigurationValue> configurations(
      GenCqueryScope scope) {
    ImmutableMap.Builder<String, BuildConfigurationValue> result = ImmutableMap.builder();
    for (SkyValue value : scope.getValues().values()) {
      if (value instanceof BuildConfigurationValue configuration) {
        result.put(configuration.checksum(), configuration);
      }
    }
    return result.buildOrThrow();
  }

  /** Uses cquery's evaluator while resolving target literals exclusively within the snapshot. */
  private static final class ScopedEnvironment extends ConfiguredTargetQueryEnvironment {
    private final ImmutableListMultimap<Label, CqueryNode> targetsByLabel;
    private final boolean strict;

    ScopedEnvironment(
        RuleContext ruleContext,
        GenCqueryScope scope,
        CqueryOptions options,
        StoredEventHandler events) {
      super(
          /* keepGoing= */ false,
          events,
          CQUERY_FUNCTIONS,
          topLevelConfigurations(ruleContext, scope),
          configurations(scope),
          /* topLevelAspects= */ ImmutableMap.of(),
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT,
              ruleContext.getRepository(),
              ruleContext.getRule().getPackageMetadata().repositoryMapping()),
          new PathPackageLocator(/* outputBase= */ null, ImmutableList.of(), ImmutableList.of()),
          () -> scope,
          options,
          /* topLevelArtifactContext= */ null,
          options.getLabelPrinter(
              ruleContext.getAnalysisEnvironment().getStarlarkSemantics(),
              ruleContext.getRule().getPackageMetadata().repositoryMapping()));
      this.strict = ruleContext.attributes().get("strict", Type.BOOLEAN);
      Map<ActionLookupKey, CqueryNode> targets = new LinkedHashMap<>();
      for (SkyValue value : scope.getValues().values()) {
        if (value instanceof ConfiguredTargetValue configured) {
          CqueryNode target = configured.getConfiguredTarget();
          targets.put(target.getLookupKey(), target);
        }
      }
      ImmutableListMultimap.Builder<Label, CqueryNode> builder = ImmutableListMultimap.builder();
      targets.values().forEach(target -> builder.put(target.getOriginalLabel(), target));
      targetsByLabel = builder.orderValuesBy(TARGET_ORDER).build();
    }

    private static TopLevelConfigurations topLevelConfigurations(
        RuleContext ruleContext, GenCqueryScope scope) {
      ImmutableList.Builder<TargetAndConfiguration> roots = ImmutableList.builder();
      for (var dep : ruleContext.getPrerequisiteConfiguredTargets("scope")) {
        Label label = dep.getConfiguredTarget().getOriginalLabel();
        try {
          roots.add(
              new TargetAndConfiguration(
                  ((PackageValue) scope.getValue(label.getPackageIdentifier()))
                      .getPackage()
                      .getTarget(label.getName()),
                  dep.getConfiguration()));
        } catch (NoSuchTargetException e) {
          throw new IllegalStateException("analyzed scope target is missing: " + label, e);
        }
      }
      return new TopLevelConfigurations(roots.build());
    }

    @Override
    @Nullable
    protected CqueryNode getConfiguredTarget(
        Label label, @Nullable BuildConfigurationValue configuration) {
      // Resolve the already configured instance, including non-idempotent rule transitions and
      // execution platform overrides, without synthesizing a fresh pre-transition SkyKey.
      BuildConfigurationKey key = configuration == null ? null : configuration.getKey();
      for (CqueryNode target : targetsByLabel.get(label)) {
        if (Objects.equals(target.getConfigurationKey(), key)) {
          return target;
        }
      }
      return null;
    }

    @Override
    public QueryTaskFuture<Void> getTargetsMatchingPattern(
        QueryExpression owner, String pattern, Callback<CqueryNode> callback) {
      try {
        TargetPattern parsed = getPattern(pattern);
        if (parsed.getType() != TargetPattern.Type.SINGLE_TARGET) {
          throw new QueryException(
              owner,
              "target patterns are not allowed in gencquery: " + pattern,
              Query.Code.SYNTAX_ERROR);
        }
        Label label = parsed.getSingleTargetLabel();
        ImmutableList<CqueryNode> targets = targetsByLabel.get(label);
        if (targets.isEmpty()) {
          String message = "target '" + label + "' is not within the scope of the query";
          if (strict) {
            throw new QueryException(owner, message, Query.Code.TARGET_NOT_IN_UNIVERSE_SCOPE);
          }
          eventHandler.handle(Event.warn(message));
        }
        callback.process(targets);
        return immediateSuccessfulFuture(null);
      } catch (TargetParsingException e) {
        return immediateFailedFuture(
            new QueryException(owner, e.getMessage(), e.getDetailedExitCode().getFailureDetail()));
      } catch (QueryException e) {
        return immediateFailedFuture(e);
      } catch (InterruptedException e) {
        return immediateCancelledFuture();
      }
    }
  }
}
