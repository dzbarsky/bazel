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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
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
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
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
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.ParserInput;

/**
 * Evaluates cquery over an explicitly scoped configured graph and registers a file write action.
 */
public final class GenCquery
    implements RuleConfiguredTargetFactory, GenCqueryFunction.QueryEvaluator {
  private static final ImmutableMap<String, QueryFunction> QUERY_FUNCTIONS =
      Maps.uniqueIndex(ConfiguredTargetQueryEnvironment.FUNCTIONS, QueryFunction::getName);

  private static final Comparator<CqueryNode> CONFIGURATION_ORDER =
      Comparator.comparing(
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

    String expression = ruleContext.attributes().get("expression", Type.STRING);
    var targetPatternPackages = ImmutableSet.<PackageIdentifier>builder();
    try {
      var patterns = new LinkedHashSet<String>();
      QueryParser.parse(expression, QUERY_FUNCTIONS).collectTargetPatterns(patterns);
      var targetParser =
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT,
              ruleContext.getRepository(),
              ruleContext.getRule().getPackageMetadata().repositoryMapping());
      for (String pattern : patterns) {
        TargetPattern parsed = targetParser.parse(pattern);
        if (parsed.getType() == TargetPattern.Type.TARGETS_IN_PACKAGE
            && (pattern.startsWith("//") || pattern.startsWith("@"))) {
          // Resolve concrete names such as //pkg:all before applying strict scope filtering.
          targetPatternPackages.add(parsed.getDirectory());
        }
      }
    } catch (QuerySyntaxException | TargetParsingException e) {
      ruleContext.ruleError("cquery failed: " + e.getMessage());
      return null;
    }

    SkyFunction.Environment env = ruleContext.getAnalysisEnvironment().getSkyframeEnv();
    ImmutableList<ConfiguredTargetKey> rootKeys =
        ruleContext.attributes().get("scope", BuildType.GENQUERY_SCOPE_TYPE_LIST).stream()
            .distinct()
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfigurationKey(ruleContext.getConfiguration().getKey())
                        .build())
            .collect(ImmutableList.toImmutableList());
    GenCqueryValue value;
    try {
      value =
          (GenCqueryValue)
              env.getValueOrThrow(
                  GenCqueryKey.create(
                      new GenCqueryKey.Request(
                          ruleContext.getLabel(),
                          ruleContext.getConfiguration().getKey(),
                          rootKeys,
                          expression,
                          ImmutableList.copyOf(targetPatternPackages.build().iterator()),
                          ImmutableList.copyOf(parser.canonicalize()),
                          source,
                          ruleContext.attributes().get("strict", Type.BOOLEAN),
                          ruleContext.attributes().get("compressed_output", Type.BOOLEAN))),
                  GenCqueryFunction.QueryException.class);
    } catch (GenCqueryFunction.QueryException e) {
      if (e.attribute != null) {
        ruleContext.attributeError(e.attribute, e.getMessage());
      } else {
        ruleContext.ruleError(e.getMessage());
      }
      return null;
    }
    if (value == null) {
      return null;
    }

    Artifact output = ruleContext.createOutputArtifact();
    ruleContext.registerAction(
        new GenQuery.QueryResultAction(ruleContext.getActionOwner(), output, value.getResult()));
    var files = NestedSetBuilder.create(Order.STABLE_ORDER, output);
    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(files)
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

  @Override
  public GenQueryResult evaluate(
      GenCqueryKey.Request request,
      RepositoryMapping repositoryMapping,
      StarlarkSemantics semantics,
      GenCqueryScope scope,
      @Nullable ParserInput starlarkFile,
      ExtendedEventHandler eventHandler)
      throws InterruptedException, GenCqueryFunction.QueryException {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(CqueryOptions.class)
            .allowResidue(false)
            .withConversionContext(
                Label.RepoContext.of(request.owner().getRepository(), repositoryMapping))
            .build();
    try {
      parser.parse(request.options());
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("validated query options could not be parsed", e);
    }
    CqueryOptions options = parser.getOptions(CqueryOptions.class);
    StoredEventHandler events = new StoredEventHandler();
    GenQueryResult result;
    try (ScopedEnvironment queryEnvironment =
            new ScopedEnvironment(request, repositoryMapping, semantics, scope, options, events);
        GenQueryOutputStream out = new GenQueryOutputStream(request.compressedOutput())) {
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
                  semantics,
                  starlarkFile,
                  starlarkFile == null ? "starlark_expr" : "starlark_file",
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
      QueryExpression expression = QueryExpression.parse(request.expression(), queryEnvironment);
      Iterable<CqueryNode> orderedTargets;
      if (expression.isTopLevelSomePathFunction()) {
        var targets = QueryUtil.newOrderedAggregateAllOutputFormatterCallback(queryEnvironment);
        queryEnvironment.evaluateQuery(expression, targets);
        orderedTargets = targets.getResult();
      } else {
        var targets = queryEnvironment.createThreadSafeMutableSet();
        queryEnvironment.evaluateQuery(
            expression,
            new ThreadSafeOutputFormatterCallback<>() {
              @Override
              public void processOutput(Iterable<CqueryNode> partialResult) {
                Iterables.addAll(targets, partialResult);
              }
            });
        // Build each canonical label string once, and keep this sort-key cache out of formatting.
        var labels = new HashMap<Label, String>();
        for (CqueryNode target : targets) {
          labels.computeIfAbsent(target.getOriginalLabel(), Label::toString);
        }
        orderedTargets =
            ImmutableList.sortedCopyOf(
                Comparator.comparing((CqueryNode target) -> labels.get(target.getOriginalLabel()))
                    .thenComparing(CONFIGURATION_ORDER),
                targets);
      }
      formatter.start();
      formatter.processOutputAndWrite(orderedTargets);
      formatter.close(/* failFast= */ events.hasErrors());
      out.close();
      result = out.getResult();
    } catch (QueryException | QuerySyntaxException | IOException e) {
      throw new GenCqueryFunction.QueryException("cquery failed: " + e.getMessage());
    } finally {
      events.replayOn(eventHandler);
    }
    if (events.hasErrors()) {
      throw new GenCqueryFunction.QueryException("cquery output could not be evaluated");
    }

    return result;
  }

  /** Uses cquery's evaluator while resolving target literals exclusively within the snapshot. */
  private static final class ScopedEnvironment extends ConfiguredTargetQueryEnvironment {
    private final GenCqueryScope scope;
    private final boolean strict;

    ScopedEnvironment(
        GenCqueryKey.Request request,
        RepositoryMapping repositoryMapping,
        StarlarkSemantics semantics,
        GenCqueryScope scope,
        CqueryOptions options,
        StoredEventHandler events) {
      super(
          /* keepGoing= */ false,
          events,
          CQUERY_FUNCTIONS,
          topLevelConfigurations(scope),
          scope.getConfigurations(),
          /* topLevelAspects= */ ImmutableMap.of(),
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT, request.owner().getRepository(), repositoryMapping),
          new PathPackageLocator(/* outputBase= */ null, ImmutableList.of(), ImmutableList.of()),
          () -> scope,
          options,
          /* topLevelArtifactContext= */ null,
          options.getLabelPrinterLegacy(semantics));
      this.strict = request.strict();
      this.scope = scope;
    }

    private static TopLevelConfigurations topLevelConfigurations(GenCqueryScope scope) {
      ImmutableList.Builder<TargetAndConfiguration> roots = ImmutableList.builder();
      for (ConfiguredTargetKey key : scope.getRootKeys()) {
        ConfiguredTarget target =
            ((ConfiguredTargetValue) scope.getValue(key)).getConfiguredTarget();
        Label label = target.getOriginalLabel();
        try {
          roots.add(
              new TargetAndConfiguration(
                  ((PackageValue) scope.getValue(label.getPackageIdentifier()))
                      .getPackage()
                      .getTarget(label.getName()),
                  target.getConfigurationKey() == null
                      ? null
                      : (BuildConfigurationValue) scope.getValue(target.getConfigurationKey())));
        } catch (NoSuchTargetException e) {
          throw new IllegalStateException("analyzed scope target is missing: " + label, e);
        }
      }
      return new TopLevelConfigurations(roots.build());
    }

    @Override
    protected ImmutableList<CqueryNode> getConfiguredTargets(
        Label label, ConfigurationSelection selection) {
      // A configuration can contain several execution-platform instances of the same label.
      // Scan its candidates once, preserving full identities in the preferred configuration.
      int bestPriority = Integer.MAX_VALUE;
      ImmutableList.Builder<CqueryNode> result = ImmutableList.builder();
      for (CqueryNode target : scope.getTargets(label)) {
        int priority = selection.priority(target.getConfigurationKey());
        if (priority == Integer.MAX_VALUE || priority > bestPriority) {
          continue;
        }
        if (priority < bestPriority) {
          bestPriority = priority;
          result = ImmutableList.builder();
        }
        result.add(target);
      }
      return result.build();
    }

    @Override
    public QueryTaskFuture<Void> getTargetsMatchingPattern(
        QueryExpression owner, String pattern, Callback<CqueryNode> callback) {
      try {
        TargetPattern parsed = getPattern(pattern);
        Label label = null;
        if (parsed.getType() == TargetPattern.Type.SINGLE_TARGET) {
          label = parsed.getSingleTargetLabel();
        } else if (parsed.getType() == TargetPattern.Type.TARGETS_IN_PACKAGE
            && (pattern.startsWith("//") || pattern.startsWith("@"))) {
          // Absolute wildcard spellings can name concrete targets, such as //pkg:all. Resolve
          // that ambiguity from tracked package data before enforcing the configured scope.
          PackageValue pkg = (PackageValue) scope.getValue(parsed.getDirectory());
          String name =
              Label.create(parsed.getDirectory(), pattern.substring(pattern.lastIndexOf(':') + 1))
                  .getName();
          var target = pkg == null ? null : pkg.getPackage().getTargets().get(name);
          if (target != null) {
            label = target.getLabel();
            eventHandler.handle(
                Event.warn(
                    String.format(
                        "The target pattern '%s' is ambiguous: ':%s' is both a wildcard, and the"
                            + " name of an existing %s; using the latter interpretation",
                        pattern, name, target.getTargetKind())));
          }
        }
        if (label == null) {
          throw new QueryException(
              owner,
              "target patterns are not allowed in gencquery: " + pattern,
              Query.Code.SYNTAX_ERROR);
        }
        List<CqueryNode> targets = scope.getTargets(label);
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
      } catch (LabelSyntaxException e) {
        return immediateFailedFuture(
            new QueryException(owner, e.getMessage(), Query.Code.SYNTAX_ERROR));
      } catch (QueryException e) {
        return immediateFailedFuture(e);
      } catch (InterruptedException e) {
        return immediateCancelledFuture();
      }
    }
  }
}
