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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.SourceManifestAction;
import com.google.devtools.build.lib.analysis.actions.ParameterFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionException;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.packages.Types;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.query2.aquery.ActionGraphQueryEnvironment;
import com.google.devtools.build.lib.query2.aquery.ActionGraphTextOutput;
import com.google.devtools.build.lib.query2.aquery.AqueryActionFilter;
import com.google.devtools.build.lib.query2.aquery.AqueryOptions;
import com.google.devtools.build.lib.query2.aquery.AqueryUtils;
import com.google.devtools.build.lib.query2.engine.ActionFilterFunction;
import com.google.devtools.build.lib.query2.engine.BinaryOperatorExpression;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.FunctionExpression;
import com.google.devtools.build.lib.query2.engine.LetExpression;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
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
import com.google.devtools.build.lib.skyframe.actiongraph.v2.ActionGraphDump;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler.OutputType;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.InvalidAqueryOutputFormatException;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.ParserInput;

/** Evaluates aquery during analysis and makes the report available as a build artifact. */
public final class GenAquery
    implements RuleConfiguredTargetFactory, GenAnalysisQueryFunction.QueryEvaluator {
  private static final ImmutableMap<String, QueryFunction> QUERY_FUNCTIONS =
      Maps.uniqueIndex(
          Iterables.concat(
              ActionGraphQueryEnvironment.FUNCTIONS, ActionGraphQueryEnvironment.AQUERY_FUNCTIONS),
          QueryFunction::getName);

  @Override
  @Nullable
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(AqueryOptions.class)
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
              "skyframe_state",
              "experimental_explicit_aspects")) {
        if (parser.containsExplicitOption(forbidden)) {
          ruleContext.attributeError("opts", "option --" + forbidden + " is not allowed");
          return null;
        }
      }
      if (!parser.getOptions(AqueryOptions.class).getIncludePrunedInputs()) {
        ruleContext.attributeError(
            "opts",
            "--noinclude_pruned_inputs is not allowed: action inputs must not depend on previous"
                + " execution");
        return null;
      }
      parser.parse("--output=" + ruleContext.attributes().get("output", Type.STRING));
    } catch (OptionsParsingException e) {
      ruleContext.attributeError("opts", "error while parsing aquery options: " + e.getMessage());
      return null;
    }
    try {
      parse(ruleContext.attributes().get("expression", Type.STRING));
    } catch (QuerySyntaxException | GenAnalysisQueryFunction.QueryException e) {
      ruleContext.attributeError("expression", e.getMessage());
      return null;
    }
    return GenAnalysisQuery.create(
        ruleContext, GenAnalysisQueryKey.Kind.AQUERY, QUERY_FUNCTIONS, parser, null);
  }

  private record ActionQuery(QueryExpression targets, ImmutableList<AqueryActionFilter> filters) {
    boolean matches(ActionAnalysisMetadata action) throws InterruptedException {
      for (AqueryActionFilter filter : filters) {
        if (!AqueryUtils.matchesAqueryFilters(action, filter, true)) {
          return false;
        }
      }
      return true;
    }
  }

  private static ActionQuery parse(String expression)
      throws QuerySyntaxException, GenAnalysisQueryFunction.QueryException {
    QueryExpression targets = QueryParser.parse(expression, QUERY_FUNCTIONS);
    var filters = ImmutableList.<AqueryActionFilter>builder();
    while (targets instanceof FunctionExpression function
        && function.getFunction() instanceof ActionFilterFunction) {
      if (function.getArgs().size() != 2) {
        throw new GenAnalysisQueryFunction.QueryException(
            function.getFunction().getName()
                + " requires a regular expression and a target expression");
      }
      try {
        filters.add(
            AqueryActionFilter.builder()
                .put(
                    function.getFunction().getName(),
                    Pattern.compile(function.getArgs().get(0).getWord()))
                .build());
      } catch (PatternSyntaxException e) {
        throw new GenAnalysisQueryFunction.QueryException(
            "invalid action filter: " + e.getMessage());
      }
      targets = function.getArgs().get(1).getExpression();
    }
    // Action filters select actions, while the underlying engine operates on sets of targets.
    // Reject mixed expressions instead of silently applying one branch's filter to every branch.
    var pending = new ArrayDeque<QueryExpression>();
    pending.add(targets);
    while (!pending.isEmpty()) {
      QueryExpression node = pending.removeLast();
      if (node instanceof FunctionExpression function) {
        if (function.getFunction() instanceof ActionFilterFunction) {
          throw new GenAnalysisQueryFunction.QueryException(
              "action filters must wrap the target expression; they cannot occur inside target"
                  + " query functions or set operations");
        }
        for (var arg : function.getArgs()) {
          if (arg.getType() == ArgumentType.EXPRESSION) {
            pending.add(arg.getExpression());
          }
        }
      } else if (node instanceof BinaryOperatorExpression binary) {
        pending.addAll(binary.getOperands());
      } else if (node instanceof LetExpression let) {
        pending.add(let.getVarExpr());
        pending.add(let.getBodyExpr());
      }
    }
    return new ActionQuery(targets, filters.build());
  }

  private record Actions(ConfiguredTarget target, ImmutableList<ActionAnalysisMetadata> actions) {}

  @Override
  @Nullable
  public GenQueryResult evaluate(
      GenAnalysisQueryKey.Request request,
      RepositoryMapping repositoryMapping,
      StarlarkSemantics semantics,
      GenAnalysisQueryScope scope,
      @Nullable ParserInput unusedFormatter,
      SkyFunction.Environment env)
      throws InterruptedException, GenAnalysisQueryFunction.QueryException, IOException {
    OptionsParser parser =
        OptionsParser.builder()
            .optionsClasses(AqueryOptions.class)
            .allowResidue(false)
            .withConversionContext(
                Label.RepoContext.of(request.owner().getRepository(), repositoryMapping))
            .build();
    try {
      parser.parse(request.options());
    } catch (OptionsParsingException e) {
      throw new IllegalStateException("validated query options could not be parsed", e);
    }
    AqueryOptions options = parser.getOptions(AqueryOptions.class);
    options.setIncludeCommandline(
        options.getIncludeCommandline() || options.getIncludeParamFiles());
    StoredEventHandler events = new StoredEventHandler();
    try (ScopedEnvironment query =
        new ScopedEnvironment(request, repositoryMapping, semantics, scope, options, events)) {
      ActionQuery expression = parse(request.expression());
      Iterable<ConfiguredTargetValue> targets;
      if (expression.targets().isTopLevelSomePathFunction()) {
        var selected = QueryUtil.newOrderedAggregateAllOutputFormatterCallback(query);
        query.evaluateQuery(expression.targets(), selected);
        targets = selected.getResult();
      } else {
        var selected = query.createThreadSafeMutableSet();
        query.evaluateQuery(
            expression.targets(),
            new ThreadSafeOutputFormatterCallback<>() {
              @Override
              public void processOutput(Iterable<ConfiguredTargetValue> values) {
                Iterables.addAll(selected, values);
              }
            });
        var labels = new HashMap<Label, String>();
        for (ConfiguredTargetValue value : selected) {
          labels.computeIfAbsent(value.getConfiguredTarget().getOriginalLabel(), Label::toString);
        }
        targets =
            ImmutableList.sortedCopyOf(
                Comparator.comparing(
                        (ConfiguredTargetValue value) ->
                            labels.get(value.getConfiguredTarget().getOriginalLabel()))
                    .thenComparing(
                        value -> value.getConfiguredTarget().getConfigurationChecksum(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        value ->
                            ConfiguredTargetKey.fromConfiguredTarget(value.getConfiguredTarget()),
                        ConfiguredTargetKey.ORDERING),
                selected);
      }
      // Retain action-list references, not another entry for every action in the result.
      List<Actions> groups = new ArrayList<>();
      Set<AspectKey> seenAspects = new HashSet<>();
      for (ConfiguredTargetValue value : targets) {
        ConfiguredTarget target = value.getConfiguredTarget();
        if (!(target instanceof RuleConfiguredTarget rule)) {
          continue;
        }
        groups.add(new Actions(target, rule.getActions()));
        if (options.getUseAspects()) {
          ConfiguredTargetKey key = ConfiguredTargetKey.fromConfiguredTarget(target);
          List<AspectKey> aspects = new ArrayList<>();
          for (AspectKey aspect : scope.getAspectKeys(key)) {
            if (seenAspects.add(aspect)) {
              aspects.add(aspect);
            }
          }
          aspects.sort(AspectKey.ORDERING);
          for (AspectKey aspect : aspects) {
            groups.add(
                new Actions(target, ((ActionLookupValue) scope.getValue(aspect)).getActions()));
          }
        }
      }
      boolean textOutput =
          switch (options.getOutputFormat()) {
            case "text", "commands", "summary" -> true;
            default -> false;
          };
      if (!options.getOutputFormat().equals("commands")
          && !options.getOutputFormat().equals("summary")) {
        Set<SkyKey> templates = new LinkedHashSet<>();
        for (Actions group : groups) {
          for (ActionAnalysisMetadata action : group.actions()) {
            if (options.getIncludeFileWriteContents()
                && action instanceof SourceManifestAction manifest
                && expression.matches(action)
                && manifest.hasSymlinkArtifacts()) {
              throw new GenAnalysisQueryFunction.QueryException(
                  "cannot report file contents for a runfiles manifest with symlink artifacts"
                      + " during analysis");
            }
            if (action instanceof TemplateExpansionAction template && expression.matches(action)) {
              Artifact source = template.getTemplate().getTemplateArtifact();
              if (source != null && source.isSourceArtifact()) {
                templates.add(
                    FileValue.key(
                        RootedPath.toRootedPath(
                            source.getRoot().getRoot(), source.getRootRelativePath())));
              }
            }
          }
        }
        // File metadata cannot depend on analysis or require its semaphore. Batch all requests so
        // all selected source files can be acquired together before formatting.
        var files = env.getValuesAndExceptions(templates);
        for (SkyKey key : templates) {
          FileValue file = (FileValue) files.get(key);
          if (file != null && !file.isFile()) {
            throw new GenAnalysisQueryFunction.QueryException(
                "action template must be an existing regular file: " + key.argument());
          }
        }
        if (env.valuesMissing()) {
          return null;
        }
      }
      AqueryUtils.ParamFileContents paramFiles =
          input -> {
            if (!(input instanceof Artifact.DerivedArtifact derived)) {
              return null;
            }
            var generating = derived.getGeneratingActionKey();
            if (scope.getValue(generating.getActionLookupKey()) instanceof ActionLookupValue owner
                && owner.getActions().get(generating.getActionIndex())
                    instanceof ParameterFileWriteAction parameter) {
              return parameter.getArguments();
            }
            return null;
          };
      GenQueryResult result;
      try (GenQueryOutputStream out = new GenQueryOutputStream(request.compressedOutput())) {
        if (textOutput) {
          try (ActionGraphTextOutput formatter =
              new ActionGraphTextOutput(
                  options, out, events, query.getLabelPrinter(), paramFiles)) {
            for (Actions group : groups) {
              for (ActionAnalysisMetadata action : group.actions()) {
                if (expression.matches(action)) {
                  formatter.writeAction(action);
                }
              }
            }
          }
        } else {
          try (GenAqueryOutputHandler handler =
              new GenAqueryOutputHandler(OutputType.fromString(options.getOutputFormat()), out)) {
            ActionGraphDump dump =
                new ActionGraphDump(
                    options.getIncludeCommandline(),
                    options.getIncludeArtifacts(),
                    true,
                    AqueryActionFilter.emptyInstance(),
                    options.getIncludeParamFiles(),
                    options.getIncludeFileWriteContents(),
                    handler,
                    events);
            for (Actions group : groups) {
              for (ActionAnalysisMetadata action : group.actions()) {
                if (expression.matches(action)) {
                  dump.dumpAction(group.target(), action, paramFiles);
                }
              }
            }
          }
        }
        out.close();
        result = out.getResult();
      }
      if (events.hasErrors()) {
        throw new GenAnalysisQueryFunction.QueryException("aquery output could not be evaluated");
      }
      return result;
    } catch (QueryException
        | QuerySyntaxException
        | CommandLineExpansionException
        | EvalException
        | TemplateExpansionException
        | InvalidAqueryOutputFormatException e) {
      throw new GenAnalysisQueryFunction.QueryException("aquery failed: " + e.getMessage());
    } finally {
      events.replayOn(env.getListener());
    }
  }

  private static final class ScopedEnvironment extends ActionGraphQueryEnvironment {
    private final GenAnalysisQueryScope scope;
    private final boolean strict;

    ScopedEnvironment(
        GenAnalysisQueryKey.Request request,
        RepositoryMapping repositoryMapping,
        StarlarkSemantics semantics,
        GenAnalysisQueryScope scope,
        AqueryOptions options,
        StoredEventHandler events) {
      super(
          false,
          events,
          AQUERY_FUNCTIONS,
          GenAnalysisQuery.topLevelConfigurations(scope),
          scope.getConfigurations(),
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT, request.owner().getRepository(), repositoryMapping),
          new PathPackageLocator(null, ImmutableList.of(), ImmutableList.of()),
          () -> scope,
          options,
          options.getLabelPrinterLegacy(semantics));
      this.scope = scope;
      this.strict = request.strict();
    }

    @Override
    public QueryTaskFuture<Void> getTargetsMatchingPattern(
        QueryExpression owner, String pattern, Callback<ConfiguredTargetValue> callback) {
      try {
        Label label =
            GenAnalysisQuery.resolveLabel(
                scope, getPattern(pattern), pattern, "genaquery", owner, eventHandler);
        List<ConfiguredTargetValue> targets = scope.getTargetValues(label);
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
