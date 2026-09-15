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
package com.google.devtools.build.lib.runtime;

import static com.google.devtools.build.lib.runtime.AggregatingTestListener.asKey;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionExecutedEvent;
import com.google.devtools.build.lib.analysis.AnalysisFailureEvent;
import com.google.devtools.build.lib.analysis.AspectCompleteEvent;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.TargetCompleteEvent;
import com.google.devtools.build.lib.analysis.test.TestResult;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.buildtool.BuildResult;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.pkgcache.LoadingFailureEvent;
import com.google.devtools.build.lib.pkgcache.TargetParsingCompleteEvent;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn;
import com.google.devtools.build.lib.skyframe.CacheProbeCompletion.MissingOutputEvent;
import com.google.devtools.build.lib.skyframe.CacheProbeCompletion.TestMissEvent;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.vfs.Path;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-command cache-probe accounting; never stores an action graph or successful cache payloads.
 */
public final class CacheProbe implements AutoCloseable {
  private final CommandEnvironment env;
  private final boolean runTests;
  private final Path output;
  private final AggregatingTestListener tests;
  private final Set<ConfiguredTargetKey> missing = ConcurrentHashMap.newKeySet();
  private volatile ImmutableSet<Label> roots = ImmutableSet.of();
  private volatile ImmutableSet<Label> excluded = ImmutableSet.of();
  private final Set<Label> affected = ConcurrentHashMap.newKeySet();
  private volatile ImmutableSetMultimap<Label, Label> memberSuites = ImmutableSetMultimap.of();
  private volatile ImmutableMap<Label, ImmutableSet<Label>> suiteMembers = ImmutableMap.of();
  private final AtomicReference<DetailedExitCode> failure = new AtomicReference<>();
  private final AtomicReference<String> incomplete = new AtomicReference<>();

  public CacheProbe(CommandEnvironment env, BuildRequest request) throws IOException {
    this.env = env;
    runTests = request.shouldRunTests();
    output = env.getWorkingDirectory().getRelative(request.getExecutionOptions().cacheProbeOutput);
    if (!Set.of("build", "test").contains(request.getCommandName())) {
      throw new IOException("Cache probing supports only build and test commands");
    }
    if (!request.getKeepGoing() || !request.getExecutionOptions().testKeepGoing) {
      throw new IOException("Cache probing requires --keep_going and --test_keep_going");
    }
    if (request.getLoadingOptions().buildTestsOnly) {
      throw new IOException("Cache probing requires --build_tests_only=false");
    }
    ExecutionOptions execution = request.getExecutionOptions();
    if (execution.checkUpToDate
        || execution.testCheckUpToDate
        || execution.testOutput == ExecutionOptions.TestOutputFormat.STREAMED) {
      throw new IOException(
          "Cache probing does not support check_up_to_date or streamed test output");
    }
    output.getParentDirectory().createDirectoryAndParents();
    EventBus testEvents = new EventBus();
    tests = new AggregatingTestListener(TestSummaryOptions.DEFAULTS, execution, testEvents);
    testEvents.register(this);
    env.getEventBus().register(this);
    if (runTests) {
      env.getEventBus().register(tests);
    }
    env.getReporter()
        .handle(
            Event.info(
                "Cache probe: streamed misses are provisional until the manifest is complete"));
  }

  /** Removes stale or incomplete output before and after a probe. */
  static void deleteOutput(CommandEnvironment env, ExecutionOptions execution) throws IOException {
    env.getWorkingDirectory().getRelative(execution.cacheProbeOutput).delete();
  }

  private static boolean isMiss(DetailedExitCode code) {
    return code != null
        && code.getFailureDetail() != null
        && code.getFailureDetail().hasSpawn()
        && code.getFailureDetail().getSpawn().getCode() == Spawn.Code.CACHE_PROBE_MISS;
  }

  public static DetailedExitCode error(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setSpawn(Spawn.newBuilder().setCode(Spawn.Code.EXEC_IO_EXCEPTION))
            .build());
  }

  private void recordFailure(DetailedExitCode code) {
    if (code != null && !code.isSuccess() && !isMiss(code)) {
      failure.accumulateAndGet(code, DetailedExitCodeComparator::chooseMoreImportantWithFirstIfTie);
    }
  }

  @Subscribe
  public void targets(TargetParsingCompleteEvent event) {
    if (!event.getFailedTargetPatterns().isEmpty()) {
      incomplete.compareAndSet(
          null, "Cache probe could not load target patterns: " + event.getFailedTargetPatterns());
    }
    excluded =
        ImmutableSet.<Label>builder()
            .addAll(event.getFilteredLabels())
            .addAll(event.getTestFilteredLabels())
            .build();
    roots = ImmutableSet.copyOf(event.getOriginalPatternsToLabels().values());
    suiteMembers = event.getTestSuiteExpansions();
    ImmutableSetMultimap.Builder<Label, Label> suites = ImmutableSetMultimap.builder();
    suiteMembers.forEach((suite, members) -> members.forEach(member -> suites.put(member, suite)));
    memberSuites = suites.build();
  }

  @Subscribe
  @AllowConcurrentEvents
  public void analysisFailure(AnalysisFailureEvent event) {
    recordRootCauses(event.getFailedTarget(), event.getRootCauses().toList());
    if (event.getRootCauses().isEmpty()) {
      incomplete.compareAndSet(
          null, "Cache probe could not analyze target: " + event.getFailedTarget().getLabel());
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void loadingFailure(LoadingFailureEvent event) {
    // The paired analysis event normally supplies the detailed failure code.
    incomplete.compareAndSet(
        null,
        "Cache probe could not load "
            + event.getFailedTarget()
            + " because of "
            + event.getFailureReason());
  }

  private void markMissing(ConfiguredTargetKey key) {
    missing.add(key);
    markLabel(key.getLabel());
  }

  private void markLabel(Label label) {
    if (roots.contains(label) && affected.add(label)) {
      env.getReporter().getOutErr().printOutLn("CACHE_PROBE_MISS " + label.getCanonicalForm());
    }
    for (Label suite : memberSuites.get(label)) {
      if (roots.contains(suite) && affected.add(suite)) {
        env.getReporter().getOutErr().printOutLn("CACHE_PROBE_MISS " + suite.getCanonicalForm());
      }
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void missingOutput(MissingOutputEvent event) {
    markLabel(event.actionLookupKey().getLabel());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void testMiss(TestMissEvent event) {
    markMissing(event.configuredTargetKey());
  }

  @Subscribe
  @AllowConcurrentEvents
  public void targetComplete(TargetCompleteEvent event) {
    if (event.failed()) {
      recordRootCauses(event.getConfiguredTargetKey(), event.getRootCauses().toList());
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void aspectComplete(AspectCompleteEvent event) {
    if (event.failed()) {
      recordRootCauses(
          event.getAspectKey().getBaseConfiguredTargetKey(), event.getRootCauses().toList());
    }
  }

  private void recordRootCauses(ConfiguredTargetKey target, Iterable<Cause> causes) {
    boolean hasMiss = false;
    for (Cause cause : causes) {
      hasMiss |= isMiss(cause.getDetailedExitCode());
      recordFailure(cause.getDetailedExitCode());
    }
    if (hasMiss) {
      markMissing(target);
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionExecuted(ActionExecutedEvent event) {
    if (event.getException() != null) {
      recordFailure(event.getException().getDetailedExitCode());
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void testResult(TestResult result) {
    ConfiguredTargetKey key =
        ConfiguredTargetKey.builder()
            .setLabel(result.getTestAction().getOwner().getLabel())
            .setConfiguration(result.getTestAction().getConfiguration())
            .build();
    recordFailure(result.getSystemFailure());
    if (isMiss(result.getSystemFailure())) {
      markMissing(key);
    }
    if (tests.getAggregator(key) == null) {
      recordFailure(error("Cache probe received a result for an unregistered test: " + key));
    }
  }

  @Subscribe
  @AllowConcurrentEvents
  public void testSummary(TestSummary summary) {
    ConfiguredTargetKey key = asKey(summary.getTarget());
    TestResultAggregator aggregator = tests.getAggregator(key);
    // Synthetic skipped or incomplete summaries are not cached test results.
    if (!summary.isSkipped()
        && aggregator != null
        && aggregator.remainingRuns() == 0
        && !TestResult.isBlazeTestStatusPassed(summary.getStatus())) {
      markMissing(key);
    }
  }

  /** Called before BuildCompleteEvent, after execution has quiesced. */
  public DetailedExitCode finish(BuildResult result, DetailedExitCode buildCode) {
    recordFailure(buildCode);
    try {
      if (failure.get() != null) {
        return failure.get();
      }
      if (incomplete.get() != null) {
        return error(incomplete.get());
      }
      if (result.getActualTargets() == null || result.getSuccessfulTargets() == null) {
        return error("Cache probe did not finish target analysis/execution");
      }
      Set<ConfiguredTarget> successful = ImmutableSet.copyOf(result.getSuccessfulTargets());
      Set<ConfiguredTarget> skipped =
          result.getSkippedTargets() == null
              ? Set.of()
              : ImmutableSet.copyOf(result.getSkippedTargets());
      Set<Label> accounted = new HashSet<>();
      missing.forEach(key -> accounted.add(key.getLabel()));
      for (ConfiguredTarget target : result.getActualTargets()) {
        ConfiguredTargetKey key = ConfiguredTargetKey.fromConfiguredTarget(target);
        if (!successful.contains(target) && !skipped.contains(target) && !missing.contains(key)) {
          return error("Cache probe has an unexplained incomplete target: " + key);
        }
        accounted.add(target.getOriginalLabel());
      }
      // Failed analysis can omit a requested target from actualTargets altogether. A suite is
      // accounted for only when every selected member is accounted for, including on warm builds.
      for (Label root : roots) {
        for (Label member : suiteMembers.getOrDefault(root, ImmutableSet.of(root))) {
          if (!accounted.contains(member)) {
            return error(
                "Cache probe has an unaccounted target: " + member + " (root " + root + ")");
          }
        }
      }
      if (runTests && result.getTestTargets() != null) {
        for (ConfiguredTarget target : result.getTestTargets()) {
          if (skipped.contains(target)) {
            continue;
          }
          ConfiguredTargetKey key = asKey(target);
          TestResultAggregator aggregator = tests.getAggregator(key);
          if (missing.contains(ConfiguredTargetKey.fromConfiguredTarget(target))
              || missing.contains(key)) {
            markLabel(target.getOriginalLabel());
            continue;
          }
          if (aggregator == null || aggregator.remainingRuns() != 0) {
            return error("Cache probe has incomplete test results: " + key);
          }
          TestSummary summary = aggregator.aggregateAndReportSummary(false);
          if (!TestResult.isBlazeTestStatusPassed(summary.getStatus())) {
            markLabel(target.getOriginalLabel());
          }
        }
      }
      writeManifest();
      env.getReporter()
          .handle(
              Event.info(
                  "Cache probe complete: "
                      + affected.size()
                      + " of "
                      + roots.size()
                      + " roots may need work"));
      return DetailedExitCode.success();
    } catch (IOException e) {
      env.getReporter().handle(Event.error("Cannot write cache probe manifest: " + e.getMessage()));
      return error(e.getMessage());
    }
  }

  private void writeManifest() throws IOException {
    List<Map<String, String>> entries =
        affected.stream()
            .map(Label::getCanonicalForm)
            .sorted()
            .map(label -> Map.of("label", label))
            .toList();
    Map<String, Object> manifest = new TreeMap<>();
    manifest.put("schema_version", 1);
    manifest.put("complete", true);
    manifest.put("invocation_id", env.getCommandId().toString());
    manifest.put("mode", "cached-success");
    manifest.put("target_kind", "all");
    manifest.put("total_targets", roots.size());
    manifest.put("affected", entries);
    manifest.put(
        "excluded_targets", excluded.stream().map(Label::getCanonicalForm).sorted().toList());
    Path temporary =
        output
            .getParentDirectory()
            .getChild(output.getBaseName() + "." + env.getCommandId() + ".tmp");
    try {
      try (Writer writer =
          new OutputStreamWriter(temporary.getOutputStream(), StandardCharsets.UTF_8)) {
        new Gson().toJson(manifest, writer);
        writer.write('\n');
      }
      temporary.renameTo(output);
    } finally {
      temporary.delete();
    }
  }

  @Override
  public void close() {
    env.getEventBus().unregister(this);
    if (runTests) {
      env.getEventBus().unregister(tests);
    }
  }
}
