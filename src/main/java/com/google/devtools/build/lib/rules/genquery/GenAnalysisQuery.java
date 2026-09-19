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
import com.google.common.collect.ImmutableSet;
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
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment.TopLevelConfigurations;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryParser;
import com.google.devtools.build.lib.query2.engine.QuerySyntaxException;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.common.options.OptionsParser;
import java.util.LinkedHashSet;
import javax.annotation.Nullable;

/** Shared rule setup and label resolution for analysis queries. */
final class GenAnalysisQuery {
  private GenAnalysisQuery() {}

  @Nullable
  static ConfiguredTarget create(
      RuleContext ruleContext,
      GenAnalysisQueryKey.Kind kind,
      ImmutableMap<String, QueryFunction> queryFunctions,
      OptionsParser parser,
      @Nullable Artifact source)
      throws InterruptedException,
          RuleConfiguredTargetFactory.RuleErrorException,
          ActionConflictException {
    String expression = ruleContext.attributes().get("expression", Type.STRING);
    var targetPatternPackages = ImmutableSet.<PackageIdentifier>builder();
    try {
      var patterns = new LinkedHashSet<String>();
      QueryParser.parse(expression, queryFunctions).collectTargetPatterns(patterns);
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
      String queryName =
          switch (kind) {
            case CQUERY -> "cquery";
            case AQUERY -> "aquery";
          };
      ruleContext.ruleError(queryName + " failed: " + e.getMessage());
      return null;
    }

    SkyFunction.Environment env = ruleContext.getAnalysisEnvironment().getSkyframeEnv();
    if (kind == GenAnalysisQueryKey.Kind.AQUERY
        && env.getValue(GenAqueryDirectoryInfo.Key.INSTANCE) == null) {
      return null;
    }
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
    GenAnalysisQueryValue value;
    try {
      value =
          (GenAnalysisQueryValue)
              env.getValueOrThrow(
                  GenAnalysisQueryKey.create(
                      new GenAnalysisQueryKey.Request(
                          kind,
                          ruleContext.getLabel(),
                          ruleContext.getConfiguration().getKey(),
                          rootKeys,
                          expression,
                          ImmutableList.copyOf(targetPatternPackages.build().iterator()),
                          ImmutableList.copyOf(parser.canonicalize()),
                          source,
                          ruleContext.attributes().get("strict", Type.BOOLEAN),
                          ruleContext.attributes().get("compressed_output", Type.BOOLEAN))),
                  GenAnalysisQueryFunction.QueryException.class);
    } catch (GenAnalysisQueryFunction.QueryException e) {
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

  static TopLevelConfigurations topLevelConfigurations(GenAnalysisQueryScope scope) {
    ImmutableList.Builder<TargetAndConfiguration> roots = ImmutableList.builder();
    for (ConfiguredTargetKey key : scope.getRootKeys()) {
      ConfiguredTarget target = ((ConfiguredTargetValue) scope.getValue(key)).getConfiguredTarget();
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

  static Label resolveLabel(
      GenAnalysisQueryScope scope,
      TargetPattern parsed,
      String pattern,
      String ruleName,
      QueryExpression owner,
      ExtendedEventHandler events)
      throws LabelSyntaxException, QueryException {
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
        events.handle(
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
          "target patterns are not allowed in " + ruleName + ": " + pattern,
          Query.Code.SYNTAX_ERROR);
    }
    return label;
  }
}
