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
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.BuildType;
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
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.query2.engine.QueryUtil;
import com.google.devtools.build.lib.query2.engine.ThreadSafeOutputFormatterCallback;
import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.ParserInput;

/**
 * Evaluates cquery over an explicitly scoped configured graph and registers a file write action.
 */
public final class GenCquery
    implements RuleConfiguredTargetFactory, GenAnalysisQueryFunction.QueryEvaluator {
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

    return GenAnalysisQuery.create(
        ruleContext, GenAnalysisQueryKey.Kind.CQUERY, QUERY_FUNCTIONS, parser, source);
  }

  @Override
  public GenQueryResult evaluate(
      GenAnalysisQueryKey.Request request,
      RepositoryMapping repositoryMapping,
      StarlarkSemantics semantics,
      GenAnalysisQueryScope scope,
      @Nullable ParserInput starlarkFile,
      SkyFunction.Environment env)
      throws InterruptedException, GenAnalysisQueryFunction.QueryException {
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
      throw new GenAnalysisQueryFunction.QueryException("cquery failed: " + e.getMessage());
    } finally {
      events.replayOn(env.getListener());
    }
    if (events.hasErrors()) {
      throw new GenAnalysisQueryFunction.QueryException("cquery output could not be evaluated");
    }

    return result;
  }

  /** Uses cquery's evaluator while resolving target literals exclusively within the snapshot. */
  private static final class ScopedEnvironment extends ConfiguredTargetQueryEnvironment {
    private final GenAnalysisQueryScope scope;
    private final boolean strict;

    ScopedEnvironment(
        GenAnalysisQueryKey.Request request,
        RepositoryMapping repositoryMapping,
        StarlarkSemantics semantics,
        GenAnalysisQueryScope scope,
        CqueryOptions options,
        StoredEventHandler events) {
      super(
          /* keepGoing= */ false,
          events,
          CQUERY_FUNCTIONS,
          GenAnalysisQuery.topLevelConfigurations(scope),
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
        Label label =
            GenAnalysisQuery.resolveLabel(scope, parsed, pattern, "gencquery", owner, eventHandler);
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
