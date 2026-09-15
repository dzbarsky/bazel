// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSetMultimap.flatteningToImmutableSetMultimap;
import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.SignedTargetPattern;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestTargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.pkgcache.AbstractRecursivePackageProvider.MissingDepException;
import com.google.devtools.build.lib.pkgcache.CompileOneDependencyTransformer;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.pkgcache.FilteringPolicy;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseCompleteEvent;
import com.google.devtools.build.lib.pkgcache.ParsingFailedEvent;
import com.google.devtools.build.lib.pkgcache.TargetParsingCompleteEvent;
import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue.TargetPatternPhaseKey;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Takes a list of target patterns corresponding to a command line and turns it into a set of
 * resolved Targets.
 */
final class TargetPatternPhaseFunction implements SkyFunction {
  public TargetPatternPhaseFunction() {}

  @Override
  @Nullable
  public TargetPatternPhaseValue compute(SkyKey key, Environment env) throws InterruptedException {
    TargetPatternPhaseKey options = (TargetPatternPhaseKey) key.argument();
    RepositoryMappingValue repositoryMappingValue =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (repositoryMappingValue == null) {
      return null;
    }

    ProbeDependencyState probeState =
        options.isCacheProbe() && !options.getExcludedDependencyPackages().isEmpty()
            ? env.getState(ProbeDependencyState::new)
            : null;
    // Determine targets to build:
    List<String> failedPatterns = new ArrayList<>();
    List<ExpandedPattern> expandedPatterns;
    ResolvedTargets<Target> targets;
    if (probeState != null && probeState.selection != null) {
      expandedPatterns = probeState.selection.expandedPatterns();
      targets = probeState.selection.selectedTargets();
    } else {
      expandedPatterns =
          getTargetsToBuild(
              env, options, repositoryMappingValue.repositoryMapping(), failedPatterns);
      if (env.valuesMissing()) {
        return null;
      }
      targets = mergeAll(expandedPatterns, !failedPatterns.isEmpty(), env, options);
      if (targets == null) {
        return null;
      }
      // Local parsing errors must be replayed after a Skyframe restart.
      if (probeState != null
          && failedPatterns.isEmpty()
          && !targets.hasError()
          && !options.getCompileOneDependency()) {
        probeState.selection = new ProbeSelection(ImmutableList.copyOf(expandedPatterns), targets);
      }
    }
    ImmutableSet<Label> probeExcluded =
        probeState == null
            ? ImmutableSet.of()
            : excludedProbeDependencies(
                env, targets.getTargets(), expandedPatterns, options, probeState);
    if (probeExcluded == null) {
      return null;
    }
    targets = filterProbeTargets(targets, options, probeExcluded);

    // Record labels before they're expanded. For example, if the build requests a test_suite //foo,
    // record //foo here instead of the tests the suite expands to.
    ImmutableSet<Label> nonExpandedLabels =
        targets.getTargets().stream().map(Target::getLabel).collect(toImmutableSet());

    // If the --build_tests_only option was specified or we want to run tests, we need to determine
    // the list of targets to test. For that, we remove manual tests and apply the command-line
    // filters. Also, if --build_tests_only is specified, then the list of filtered targets will be
    // set as build list as well.
    ResolvedTargets<Target> testTargets = null;
    if (options.getDetermineTests() || options.getBuildTestsOnly()) {
      // Dropping an excluded negative suite would leave its eligible members selected; expanding
      // it instead would load the excluded packages. Fail rather than publish an incorrect probe.
      Target excludedNegativeSuite =
          options.isCacheProbe()
              ? expandedPatterns.stream()
                  .filter(pattern -> pattern.pattern().isNegative())
                  .flatMap(pattern -> pattern.resolvedTargets().getTargets().stream())
                  .filter(
                      target ->
                          TargetUtils.isTestSuiteRule(target)
                              && probeExcluded.contains(target.getLabel()))
                  .findFirst()
                  .orElse(null)
              : null;
      if (excludedNegativeSuite != null) {
        env.getListener()
            .handle(
                Event.error(
                    "Cannot subtract excluded test suite " + excludedNegativeSuite.getLabel()));
        testTargets = ResolvedTargets.failed();
      } else {
        testTargets =
            determineTests(
                env, repositoryMappingValue.repositoryMapping(), options, probeExcluded);
      }
      Preconditions.checkState(env.valuesMissing() || (testTargets != null));
    }

    Map<Label, SkyKey> testExpansionKeys = new LinkedHashMap<>();
    for (Target target : targets.getTargets()) {
      if (TargetUtils.isTestSuiteRule(target) && options.isExpandTestSuites()) {
        Label label = target.getLabel();
        SkyKey testExpansionKey = TestsForTargetPatternValue.key(ImmutableSet.of(label));
        testExpansionKeys.put(label, testExpansionKey);
      }
    }
    SkyframeLookupResult expandedTests = env.getValuesAndExceptions(testExpansionKeys.values());
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableSet<Target> filteredTargets = targets.getFilteredTargets();
    ImmutableSet<Target> testsToRun = null;
    ImmutableSet<Target> testFilteredTargets = ImmutableSet.of();

    if (testTargets != null) {
      // Parse the targets to get the tests.
      if (testTargets.getTargets().isEmpty() && !testTargets.getFilteredTargets().isEmpty()) {
        env.getListener().handle(Event.warn("All specified test targets were excluded by filters"));
      }

      if (options.getBuildTestsOnly()) {
        // Replace original targets to build with test targets, so that only targets that are
        // actually going to be built are loaded in the loading phase. Note that this has a side
        // effect that any test_suite target requested to be built is replaced by the set of *_test
        // targets it represents; for example, this affects the status and the summary reports.
        Set<Target> allFilteredTargets = new HashSet<>();
        allFilteredTargets.addAll(targets.getTargets());
        allFilteredTargets.addAll(targets.getFilteredTargets());
        allFilteredTargets.removeAll(testTargets.getTargets());
        allFilteredTargets.addAll(testTargets.getFilteredTargets());
        testFilteredTargets = ImmutableSet.copyOf(allFilteredTargets);
        filteredTargets = ImmutableSet.of();

        targets =
            ResolvedTargets.<Target>builder()
                .merge(testTargets)
                .mergeError(targets.hasError())
                .build();
        if (options.getDetermineTests()) {
          testsToRun = testTargets.getTargets();
        }
      } else /*if (determineTests)*/ {
        testsToRun = testTargets.getTargets();
        targets =
            ResolvedTargets.<Target>builder()
                .merge(targets)
                // Merging in all testsToRun guarantees that targets that will be built (because
                // they are tests) are not considered to be "filtered out", even if they were
                // initially filtered out. We can't merge in testTargets because its set of
                // filteredTargets could include targets that we're building but not testing.
                .merge(ResolvedTargets.<Target>builder().addAll(testsToRun).build())
                .mergeError(testTargets.hasError())
                .build();
        filteredTargets = targets.getFilteredTargets();
      }
      if (testsToRun != null) {
        // Note that testsToRun can still be null here, if buildTestsOnly && !shouldRunTests.
        if (!targets.getTargets().containsAll(testsToRun)) {
          throw new IllegalStateException(
              String.format(
                  "Internal consistency check failed; some targets are scheduled for test execution"
                      + " but not for building (%s)",
                  Sets.difference(testsToRun, targets.getTargets())));
        }
      }
    }

    if (targets.hasError()) {
      env.getListener().handle(Event.warn("Target pattern parsing failed."));
    }

    maybeReportDeprecation(env.getListener(), targets.getTargets());

    ResolvedTargets.Builder<Label> expandedLabelsBuilder = ResolvedTargets.builder();
    ImmutableMap.Builder<Label, ImmutableSet<Label>> testSuiteExpansions =
        ImmutableMap.builderWithExpectedSize(testExpansionKeys.size());
    for (Target target : targets.getTargets()) {
      Label label = target.getLabel();
      if (TargetUtils.isTestSuiteRule(target) && options.isExpandTestSuites()) {
        SkyKey expansionKey = Preconditions.checkNotNull(testExpansionKeys.get(label));
        var value = (TestsForTargetPatternValue) expandedTests.get(expansionKey);
        if (value == null) {
          return null;
        }
        ResolvedTargets<Label> testExpansion = value.getLabels();
        expandedLabelsBuilder.merge(testExpansion);
        testSuiteExpansions.put(label, testExpansion.getTargets());
      } else {
        expandedLabelsBuilder.add(label);
      }
    }
    ResolvedTargets<Label> targetLabels = expandedLabelsBuilder.build();
    ResolvedTargets<Target> expandedTargets =
        TestsForTargetPatternFunction.labelsToTargets(
            env, targetLabels.getTargets(), targetLabels.hasError());
    if (expandedTargets == null) {
      return null;
    }
    expandedTargets = filterProbeTargets(expandedTargets, options, probeExcluded);
    if (options.isCacheProbe()) {
      filteredTargets =
          ImmutableSet.<Target>builder()
              .addAll(filteredTargets)
              .addAll(expandedTargets.getFilteredTargets())
              .build();
      targetLabels =
          ResolvedTargets.<Label>builder()
              .addAll(expandedTargets.getTargets().stream().map(Target::getLabel).toList())
              .mergeError(expandedTargets.hasError())
              .build();
    }
    Set<Target> testSuiteTargets =
        Sets.difference(targets.getTargets(), expandedTargets.getTargets());
    ImmutableSet<Label> testsToRunLabels = null;
    if (testsToRun != null) {
      testsToRunLabels =
          testsToRun.stream().map(Target::getLabel).collect(ImmutableSet.toImmutableSet());
    }
    ImmutableSet<Label> removedTargetLabels =
        testSuiteTargets.stream().map(Target::getLabel).collect(ImmutableSet.toImmutableSet());
    TargetPatternPhaseValue result =
        new TargetPatternPhaseValue(
            targetLabels.getTargets(),
            testsToRunLabels,
            Objects.equals(nonExpandedLabels, targetLabels.getTargets())
                ? targetLabels.getTargets()
                : nonExpandedLabels,
            targets.hasError(),
            expandedTargets.hasError());

    env.getListener()
        .post(
            new TargetParsingCompleteEvent(
                targets.getTargets(),
                filteredTargets,
                testFilteredTargets,
                options.getTargetPatterns(),
                expandedTargets.getTargets(),
                ImmutableList.copyOf(failedPatterns),
                mapOriginalPatternsToLabels(expandedPatterns, targets.getTargets()),
                options.isCacheProbe()
                    ? filterSuiteExpansions(
                        testSuiteExpansions.buildOrThrow(), targetLabels.getTargets())
                    : testSuiteExpansions.buildOrThrow()));
    env.getListener()
        .post(
            new LoadingPhaseCompleteEvent(
                result.getTargetLabels(),
                removedTargetLabels,
                repositoryMappingValue.repositoryMapping()));
    return result;
  }

  /**
   * Emit a warning when a deprecated target is mentioned on the command line.
   *
   * <p>Note that this does not stop us from emitting "target X depends on deprecated target Y"
   * style warnings for the same target and it is a good thing; <i>depending</i> on a target and
   * <i>wanting</i> to build it are different things.
   */
  private static void maybeReportDeprecation(
      ExtendedEventHandler eventHandler, Collection<Target> targets) {
    for (Rule rule : Iterables.filter(targets, Rule.class)) {
      if (rule.isAttributeValueExplicitlySpecified("deprecation")) {
        eventHandler.handle(
            Event.warn(
                rule.getLocation(),
                String.format(
                    "target '%s' is deprecated: %s",
                    rule.getLabel(),
                    NonconfigurableAttributeMapper.of(rule).get("deprecation", Type.STRING))));
      }
    }
  }

  /**
   * Interprets the command-line arguments by expanding each pattern to targets and populating the
   * list of {@code failedPatterns}.
   *
   * @param env the Starlark environment
   * @param options the command-line arguments in structured form
   * @param failedPatterns a list into which failed patterns are added
   */
  private static List<ExpandedPattern> getTargetsToBuild(
      Environment env,
      TargetPatternPhaseKey options,
      RepositoryMapping repoMapping,
      List<String> failedPatterns)
      throws InterruptedException {
    TargetPattern.Parser parser =
        new TargetPattern.Parser(options.getOffset(), RepositoryName.MAIN, repoMapping);
    // Keep manually tagged wildcard targets available for the probe's excluded-target manifest.
    FilteringPolicy policy =
        options.getBuildManualTests()
                || (options.isCacheProbe() && options.getBuildTargetFilter().contains("-manual"))
            ? FilteringPolicies.NO_FILTER
            : FilteringPolicies.FILTER_MANUAL;
    List<TargetPatternKey> patternSkyKeys = new ArrayList<>(options.getTargetPatterns().size());
    for (String pattern : options.getTargetPatterns()) {
      try {
        TargetPatternKey patternKey =
            probePatternKey(env, SignedTargetPattern.parse(pattern, parser), policy, options);
        if (patternKey != null) {
          patternSkyKeys.add(patternKey);
        }
      } catch (TargetParsingException e) {
        failedPatterns.add(pattern);
        // We post a PatternExpandingError here - the pattern could not be parsed, so we don't even
        // get to run TargetPatternFunction.
        env.getListener().post(PatternExpandingError.failed(pattern, e.getMessage()));
        // We generally skip patterns that don't parse. We report a parsing failed exception to the
        // event bus here, but not in determineTests below, which goes through the same list. Note
        // that the TargetPatternFunction otherwise reports these events (but only if the target
        // pattern could be parsed successfully).
        env.getListener().post(new ParsingFailedEvent(pattern, e.getMessage()));
        try {
          env.getValueOrThrow(TargetPatternErrorFunction.key(e), TargetParsingException.class);
        } catch (TargetParsingException ignore) {
          // We ignore this. Keep going is active.
        }
        env.getListener().handle(Event.error("Skipping '" + pattern + "': " + e.getMessage()));
      }
    }

    SkyframeLookupResult resolvedPatterns = env.getValuesAndExceptions(patternSkyKeys);
    List<ExpandedPattern> expandedPatterns = new ArrayList<>(patternSkyKeys.size());

    for (TargetPatternKey pattern : patternSkyKeys) {
      TargetPatternValue value;
      try {
        value =
            (TargetPatternValue) resolvedPatterns.getOrThrow(pattern, TargetParsingException.class);
      } catch (TargetParsingException e) {
        String rawPattern = pattern.getPattern();
        String errorMessage = e.getMessage();
        failedPatterns.add(rawPattern);
        env.getListener().post(PatternExpandingError.failed(rawPattern, errorMessage));
        env.getListener().handle(Event.error("Skipping '" + rawPattern + "': " + errorMessage));
        continue;
      }
      if (value == null) {
        continue;
      }
      // TODO(ulfjack): This is terribly inefficient.
      ResolvedTargets<Target> asTargets =
          TestsForTargetPatternFunction.labelsToTargets(
              env, value.getTargets().getTargets(), value.getTargets().hasError());
      if (asTargets == null) {
        continue;
      }
      expandedPatterns.add(new ExpandedPattern(pattern, asTargets));
    }

    return expandedPatterns;
  }

  /** Merges expansions from all patterns into a single {@link ResolvedTargets} instance. */
  @Nullable
  private static ResolvedTargets<Target> mergeAll(
      List<ExpandedPattern> expandedPatterns,
      boolean hasError,
      Environment env,
      TargetPatternPhaseKey options)
      throws InterruptedException {
    ResolvedTargets.Builder<Target> builder = ResolvedTargets.builder();
    builder.mergeError(hasError);

    for (ExpandedPattern expansion : expandedPatterns) {
      if (expansion.pattern().isNegative()) {
        builder.filter(Predicates.not(Predicates.in(expansion.resolvedTargets().getTargets())));
      } else {
        builder.merge(expansion.resolvedTargets());
      }
    }

    ResolvedTargets<Target> result = builder.filter(buildTagFilter(options)).build();
    if (options.getCompileOneDependency()) {
      EnvironmentBackedRecursivePackageProvider environmentBackedRecursivePackageProvider =
          new EnvironmentBackedRecursivePackageProvider(env);
      try {
        result =
            new CompileOneDependencyTransformer(environmentBackedRecursivePackageProvider)
                .transformCompileOneDependency(env.getListener(), result);
      } catch (MissingDepException e) {
        return null;
      } catch (TargetParsingException e) {
        try {
          env.getValueOrThrow(TargetPatternErrorFunction.key(e), TargetParsingException.class);
        } catch (TargetParsingException ignore) {
          // We ignore this. Keep going is active.
        }
        env.getListener().handle(Event.error(e.getMessage()));
        return ResolvedTargets.failed();
      }
      if (environmentBackedRecursivePackageProvider.encounteredPackageErrors()) {
        result = ResolvedTargets.<Target>builder().merge(result).setError().build();
      }
    }
    return result;
  }

  /**
   * Interpret test target labels from the command-line arguments and return the corresponding set
   * of targets, handling the filter flags, and expanding test suites.
   *
   * @param repoMapping the repository mapping to apply to repos in the patterns
   */
  @Nullable
  private static ResolvedTargets<Target> determineTests(
      Environment env,
      RepositoryMapping repoMapping,
      TargetPatternPhaseKey options,
      ImmutableSet<Label> probeExcluded)
      throws InterruptedException {
    TargetPattern.Parser parser =
        new TargetPattern.Parser(options.getOffset(), RepositoryName.MAIN, repoMapping);
    List<TargetPatternKey> patternSkyKeys = new ArrayList<>();
    for (String pattern : options.getTargetPatterns()) {
      try {
        TargetPatternKey patternKey =
            probePatternKey(
                env,
                SignedTargetPattern.parse(pattern, parser),
                FilteringPolicies.FILTER_TESTS,
                options);
        if (patternKey != null) {
          patternSkyKeys.add(patternKey);
        }
      } catch (TargetParsingException e) {
        // Skip.
      }
    }
    SkyframeLookupResult resolvedPatterns = env.getValuesAndExceptions(patternSkyKeys);
    if (env.valuesMissing()) {
      return null;
    }

    List<SkyKey> expandedSuiteKeys = new ArrayList<>();
    for (TargetPatternKey key : patternSkyKeys) {
      TargetPatternValue value;
      try {
        value = (TargetPatternValue) resolvedPatterns.getOrThrow(key, TargetParsingException.class);
        if (value == null) {
          BugReport.sendBugReport(
              new IllegalStateException(
                  "TargetPatternValue " + key + " was missing, this should never happen"));
          return null;
        }
      } catch (TargetParsingException e) {
        // Skip.
        continue;
      }
      ImmutableSet<Label> labels = value.getTargets().getTargets();
      if (options.isCacheProbe() && !key.isNegative()) {
        ResolvedTargets<Target> positiveTargets =
            TestsForTargetPatternFunction.labelsToTargets(
                env, labels, value.getTargets().hasError());
        if (positiveTargets == null) {
          return null;
        }
        labels =
            filterProbeTargets(positiveTargets, options, probeExcluded).getTargets().stream()
                .map(Target::getLabel)
                .collect(toImmutableSet());
      } else {
        labels =
            labels.stream()
                .filter(label -> !probeExcluded.contains(label))
                .collect(toImmutableSet());
      }
      expandedSuiteKeys.add(TestsForTargetPatternValue.key(labels));
    }
    SkyframeLookupResult expandedSuites = env.getValuesAndExceptions(expandedSuiteKeys);
    if (env.valuesMissing()) {
      return null;
    }

    ResolvedTargets.Builder<Target> testTargetsBuilder = ResolvedTargets.builder();
    int suiteKeyIndex = 0;
    for (TargetPatternKey pattern : patternSkyKeys) {
      TargetPatternValue value;
      try {
        value =
            (TargetPatternValue) resolvedPatterns.getOrThrow(pattern, TargetParsingException.class);
        if (value == null) {
          BugReport.sendBugReport(
              new IllegalStateException(
                  "TargetPatternValue " + pattern + " was missing, this should never happen"));
          return null;
        }
      } catch (TargetParsingException e) {
        // This was already reported in getTargetsToBuild (maybe merge the two code paths?).
        continue;
      }

      TestsForTargetPatternValue expandedSuitesValue =
          (TestsForTargetPatternValue) expandedSuites.get(expandedSuiteKeys.get(suiteKeyIndex++));
      if (expandedSuitesValue == null) {
        BugReport.logUnexpected("Value for: '%s' was missing, this should never happen", pattern);
        return null;
      }
      if (pattern.isNegative()) {
        ResolvedTargets<Target> negativeTargets =
            TestsForTargetPatternFunction.labelsToTargets(
                env,
                expandedSuitesValue.getLabels().getTargets(),
                expandedSuitesValue.getLabels().hasError());
        testTargetsBuilder.filter(Predicates.not(Predicates.in(negativeTargets.getTargets())));
        testTargetsBuilder.mergeError(negativeTargets.hasError());
      } else {
        ResolvedTargets<Target> positiveTargets =
            TestsForTargetPatternFunction.labelsToTargets(
                env,
                expandedSuitesValue.getLabels().getTargets(),
                expandedSuitesValue.getLabels().hasError());
        testTargetsBuilder.addAll(positiveTargets.getTargets());
        testTargetsBuilder.mergeError(positiveTargets.hasError());
      }
    }

    testTargetsBuilder.filter(options.getTestFilter());
    return filterProbeTargets(testTargetsBuilder.build(), options, probeExcluded);
  }

  private static ImmutableMap<Label, ImmutableSet<Label>> filterSuiteExpansions(
      ImmutableMap<Label, ImmutableSet<Label>> expansions, ImmutableSet<Label> included) {
    ImmutableMap.Builder<Label, ImmutableSet<Label>> result = ImmutableMap.builder();
    expansions.forEach(
        (suite, members) ->
            result.put(suite, members.stream().filter(included::contains).collect(toImmutableSet())));
    return result.buildOrThrow();
  }

  private static Predicate<Target> buildTagFilter(TargetPatternPhaseKey options) {
    Predicate<Target> filter = TargetUtils.tagFilter(options.getBuildTargetFilter());
    if (options.isCacheProbe() && options.getBuildTargetFilter().contains("-manual")) {
      // Native configuration rules' non-taggable "manual" defaults bypass the regular tag filter.
      return Predicates.and(target -> !TargetUtils.hasManualTag(target), filter);
    }
    return filter;
  }

  private static ResolvedTargets<Target> filterProbeTargets(
      ResolvedTargets<Target> targets,
      TargetPatternPhaseKey options,
      ImmutableSet<Label> excluded) {
    if (!options.isCacheProbe()
        || (excluded.isEmpty() && options.getBuildTargetFilter().isEmpty())) {
      return targets;
    }
    Predicate<Target> tagFilter = buildTagFilter(options);
    return ResolvedTargets.<Target>builder()
        .merge(targets)
        .filter(target -> !excluded.contains(target.getLabel()) && tagFilter.apply(target))
        .build();
  }

  private static boolean excludedPackage(
      PackageIdentifier pkg, ImmutableList<PackageIdentifier> prefixes) {
    for (PackageIdentifier prefix : prefixes) {
      if (prefix.getRepository().equals(pkg.getRepository())
          && pkg.getPackageFragment().startsWith(prefix.getPackageFragment())) {
        return true;
      }
    }
    return false;
  }

  @Nullable
  private static TargetPatternKey probePatternKey(
      Environment env,
      SignedTargetPattern pattern,
      FilteringPolicy policy,
      TargetPatternPhaseKey options)
      throws InterruptedException, TargetParsingException {
    var prefixes = options.getExcludedDependencyPackages();
    if (prefixes.isEmpty()) {
      return TargetPatternValue.key(pattern, policy);
    }
    TargetPattern parsed = pattern.pattern();
    PackageIdentifier directory =
        parsed.getType() == TargetPattern.Type.PATH_AS_TARGET
            ? PackageIdentifier.createInMainRepo(parsed.getPathForPathAsTarget())
            : parsed.getDirectory();
    if (parsed.getType() == TargetPattern.Type.PATH_AS_TARGET
        && excludedPackage(directory, prefixes)) {
      // A file path below an excluded directory may belong to an included ancestor package.
      ContainingPackageLookupValue containingPackage =
          (ContainingPackageLookupValue) env.getValue(ContainingPackageLookupValue.key(directory));
      if (containingPackage == null) {
        return null;
      }
      if (!containingPackage.hasContainingPackage()) {
        // Preserve the ordinary invalid-path diagnostic.
        return TargetPatternValue.key(pattern, policy);
      }
      directory = containingPackage.getContainingPackageName();
    }
    if (excludedPackage(directory, prefixes)) {
      // Keep subtree exclusions such as -//personal/... without loading that tree. A named
      // negative may be a suite that subtracts tests outside it, so rejecting it is necessary.
      if (pattern.sign() == SignedTargetPattern.Sign.NEGATIVE
          && (options.getDetermineTests() || options.getBuildTestsOnly())
          && parsed.getType() != TargetPattern.Type.TARGETS_BELOW_DIRECTORY) {
        throw new TargetParsingException(
            "Cannot subtract excluded target pattern -" + parsed.getOriginalPattern(),
            TargetPatterns.Code.NEGATIVE_TARGET_PATTERN_NOT_ALLOWED);
      }
      return null;
    }
    ImmutableSet<PathFragment> excludedDirectories =
        prefixes.stream()
            .filter(prefix -> prefix.getRepository().equals(parsed.getRepository()))
            .map(PackageIdentifier::getPackageFragment)
            .collect(toImmutableSet());
    return TargetPatternValue.key(pattern, policy, excludedDirectories);
  }

  @Nullable
  private static ImmutableSet<Label> excludedProbeDependencies(
      Environment env,
      Collection<Target> targets,
      List<ExpandedPattern> patterns,
      TargetPatternPhaseKey options,
      ProbeDependencyState state)
      throws InterruptedException {
    var prefixes = options.getExcludedDependencyPackages();
    state.initialize(targets, patterns, options);
    if (state.result != null) {
      return state.result;
    }
    var tagFilter = buildTagFilter(options);
    while (!state.pendingLabels.isEmpty()) {
      Map<PackageIdentifier, List<Label>> labelsByPackage = new HashMap<>();
      for (Label label : state.pendingLabels) {
        labelsByPackage
            .computeIfAbsent(label.getPackageIdentifier(), unused -> new ArrayList<>())
            .add(label);
      }
      SkyframeLookupResult packages = env.getValuesAndExceptions(labelsByPackage.keySet());
      if (env.valuesMissing()) {
        return null;
      }
      Map<PackageIdentifier, PackageValue> availablePackages = new HashMap<>();
      for (PackageIdentifier packageIdentifier : labelsByPackage.keySet()) {
        PackageValue value;
        try {
          value =
              (PackageValue) packages.getOrThrow(packageIdentifier, NoSuchPackageException.class);
        } catch (NoSuchPackageException e) {
          // Analysis reports a missing non-excluded dependency if its root survives filtering.
          continue;
        }
        if (value == null) {
          return null;
        }
        availablePackages.put(packageIdentifier, value);
      }
      Set<Label> nextLabels = new HashSet<>();
      for (var entry : labelsByPackage.entrySet()) {
        PackageValue value = availablePackages.get(entry.getKey());
        if (value == null) {
          continue;
        }
        for (Label label : entry.getValue()) {
          Target target = value.getPackage().getTargetOrNull(label.getName());
          boolean dependency = state.requiredLabels.remove(label);
          boolean expandedSuite = state.expandedSuiteLabels.remove(label);
          boolean required = dependency || expandedSuite;
          List<Rule> suites = state.suiteMemberships.remove(label);
          if (suites != null && target != null) {
            for (Rule suite : suites) {
              if (TargetUtils.isTestRule(target)) {
                if (!tagFilter.apply(target)) {
                  continue;
                }
                Set<Target> member = new HashSet<>();
                member.add(target);
                TestTargetUtils.filterTests(suite, member);
                if (member.isEmpty()) {
                  continue;
                }
              }
              state.reverse.computeIfAbsent(label, unused -> new ArrayList<>()).add(suite.getLabel());
              required = true;
            }
          }
          // A suite reached later through a non-suite dependency must be traversed in full.
          boolean requiredSuite = dependency && state.filteredSuites.remove(label);
          if (!required || (!state.visitedLabels.add(label) && !requiredSuite)) {
            continue;
          }
          if (target instanceof OutputFile output) {
            Label generator = output.getGeneratingRuleLabel();
            state.reverse.computeIfAbsent(generator, unused -> new ArrayList<>()).add(label);
            if (!state.visitedLabels.contains(generator)) {
              state.requiredLabels.add(generator);
              nextLabels.add(generator);
            }
            continue;
          }
          if (!(target instanceof Rule rule)) {
            continue;
          }
          boolean filterSuiteMembers = TargetUtils.isTestSuiteRule(rule) && !dependency;
          if (filterSuiteMembers) {
            state.filteredSuites.add(label);
          }
          // Visit selectors directly, not their Cartesian product of possible attribute values.
          AggregatingAttributeMapper.of(rule)
              .visitAllLabels(
                  (attribute, prerequisite) -> {
                    PackageIdentifier dependencyPackage = prerequisite.getPackageIdentifier();
                    // Suite membership selects roots, unlike an ordinary required dependency.
                    // Filter members before traversing them or propagating their exclusions.
                    if (filterSuiteMembers
                        && (attribute.getName().equals("tests")
                            || attribute.getName().equals("$implicit_tests"))
                        && !excludedPackage(dependencyPackage, prefixes)) {
                      state.suiteMemberships
                          .computeIfAbsent(prerequisite, unused -> new ArrayList<>())
                          .add(rule);
                      nextLabels.add(prerequisite);
                      return;
                    }
                    state
                        .reverse
                        .computeIfAbsent(prerequisite, unused -> new ArrayList<>())
                        .add(rule.getLabel());
                    if (excludedPackage(dependencyPackage, prefixes)) {
                      state.excluded.add(prerequisite);
                    } else if (!state.visitedLabels.contains(prerequisite)
                        || state.filteredSuites.contains(prerequisite)) {
                      state.requiredLabels.add(prerequisite);
                      nextLabels.add(prerequisite);
                    }
                  });
        }
      }
      state.pendingLabels = nextLabels;
    }
    // Exclusion is conservative per label: a suite required in full by another root can also
    // exclude that suite's independently requested filtered expansion.
    ArrayDeque<Label> frontier = new ArrayDeque<>(state.excluded);
    while (!frontier.isEmpty()) {
      for (Label dependent : state.reverse.getOrDefault(frontier.removeFirst(), List.of())) {
        if (state.excluded.add(dependent)) {
          frontier.addLast(dependent);
        }
      }
    }
    state.result = ImmutableSet.copyOf(state.excluded);
    state.visitedLabels.clear();
    state.requiredLabels.clear();
    state.expandedSuiteLabels.clear();
    state.filteredSuites.clear();
    state.suiteMemberships.clear();
    state.reverse.clear();
    state.excluded.clear();
    return state.result;
  }

  private record ProbeSelection(
      ImmutableList<ExpandedPattern> expandedPatterns, ResolvedTargets<Target> selectedTargets) {}

  private static final class ProbeDependencyState implements Environment.SkyKeyComputeState {
    @Nullable ProbeSelection selection;
    Set<Label> pendingLabels = new HashSet<>();
    final Set<Label> requiredLabels = new HashSet<>();
    final Set<Label> expandedSuiteLabels = new HashSet<>();
    final Set<Label> filteredSuites = new HashSet<>();
    final Map<Label, List<Rule>> suiteMemberships = new HashMap<>();
    final Set<Label> visitedLabels = new HashSet<>();
    final Map<Label, List<Label>> reverse = new HashMap<>();
    final Set<Label> excluded = new HashSet<>();
    @Nullable ImmutableSet<Label> result;
    boolean initialized;

    void initialize(
        Collection<Target> targets, List<ExpandedPattern> patterns, TargetPatternPhaseKey options) {
      if (initialized) {
        return;
      }
      for (Target target : targets) {
        pendingLabels.add(target.getLabel());
        if (TargetUtils.isTestSuiteRule(target) && options.isExpandTestSuites()) {
          expandedSuiteLabels.add(target.getLabel());
        } else {
          requiredLabels.add(target.getLabel());
        }
      }
      if (options.getDetermineTests() || options.getBuildTestsOnly()) {
        // Negative suites still expand even when their tags exclude them as build roots: their
        // members must be subtracted. Their excluded dependencies must stay unloaded.
        for (ExpandedPattern pattern : patterns) {
          if (!pattern.pattern().isNegative()) {
            continue;
          }
          for (Target target : pattern.resolvedTargets().getTargets()) {
            if (TargetUtils.isTestSuiteRule(target)) {
              pendingLabels.add(target.getLabel());
              expandedSuiteLabels.add(target.getLabel());
            }
          }
        }
      }
      initialized = true;
    }
  }

  private static ImmutableSetMultimap<String, Label> mapOriginalPatternsToLabels(
      List<ExpandedPattern> expandedPatterns, Set<Target> includedTargets) {
    return expandedPatterns.stream()
        .filter(expansion -> !expansion.pattern().isNegative())
        .collect(
            flatteningToImmutableSetMultimap(
                expansion -> expansion.pattern().getPattern(),
                expansion ->
                    expansion.resolvedTargets().getTargets().stream()
                        .filter(includedTargets::contains)
                        .map(Target::getLabel)));
  }

  /** Represents the expansion of a single target pattern. */
  record ExpandedPattern(TargetPatternKey pattern, ResolvedTargets<Target> resolvedTargets) {
    ExpandedPattern {
      requireNonNull(pattern, "pattern");
      requireNonNull(resolvedTargets, "resolvedTargets");
    }
  }
}
