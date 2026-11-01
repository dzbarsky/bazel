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
package com.google.devtools.build.lib.query2.cquery;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.query2.SkyQueryEnvironment;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.TargetAccessor;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.query.hash.DirectTargetHashFunction;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.AspectKey;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.skyframe.ErrorInfo;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * cquery output formatter for {@code --generate_hashes}. Prints each configured target in the
 * result followed by a deterministic hash of it and its transitive configured dependencies.
 *
 * <p>Unlike {@code query --generate_hashes}, the graph that is hashed is the configured target
 * graph: {@code select()}s are resolved, toolchains and execution platforms are the ones that were
 * actually chosen, and each node is hashed together with its configuration. A target's own hash is
 * the {@link DirectTargetHashFunction} hash of its unconfigured target (attribute values, rule
 * definition, or source file contents), combined with the checksum of its build options.
 */
class HashesOutputFormatterCallback extends CqueryThreadsafeCallback {

  @FunctionalInterface
  interface DepsRetriever {
    @Nullable Iterable<CqueryNode> getDirectDeps(CqueryNode target) throws InterruptedException;
  }

  static final String NAME = "generate_hashes";

  private final SkyframeExecutor skyframeExecutor;
  private final DepsRetriever depsRetriever;
  private final LabelPrinter labelPrinter;
  private final boolean keepGoing;

  HashesOutputFormatterCallback(
      ExtendedEventHandler eventHandler,
      CqueryOptions options,
      OutputStream out,
      SkyframeExecutor skyframeExecutor,
      TargetAccessor<CqueryNode> accessor,
      DepsRetriever depsRetriever,
      LabelPrinter labelPrinter,
      boolean keepGoing) {
    super(eventHandler, options, out, skyframeExecutor, accessor, /* uniquifyResults= */ false);
    this.skyframeExecutor = skyframeExecutor;
    this.depsRetriever = depsRetriever;
    this.labelPrinter = labelPrinter;
    this.keepGoing = keepGoing;
  }

  @Override
  public String getName() {
    return NAME;
  }

  private int failures = 0;

  @Override
  public void process(Iterable<CqueryNode> partialResult)
      throws QueryException, InterruptedException {
    super.process(partialResult);
    if (failures > 0) {
      throw new QueryException(
          String.format("failed to compute hashes for %d target(s)", failures),
          Query.Code.BUILD_FILE_ERROR);
    }
  }

  @Override
  public void processOutput(Iterable<CqueryNode> partialResult)
      throws IOException, InterruptedException {
    List<HashTarget> roots = new ArrayList<>();
    Set<HashTarget> failed = new HashSet<>();
    List<HashTarget> nodes = collectGraph(partialResult, roots, failed);
    // EvaluationResult retains a map of all requested values. Bound it independently of the
    // configured closure size. Repeated labels across batches reuse memoized Skyframe values.
    for (List<HashTarget> batch : Lists.partition(nodes, 16384)) {
      computeDirectHashes(batch, failed);
    }
    failures += failed.size();
    if (!keepGoing && failures > 0) {
      return;
    }
    eventHandler.handle(
        Event.progress(String.format("Hashing: combining hashes of %d targets", nodes.size())));
    CompactConfiguredHashes.combine(nodes, failed);
    for (HashTarget target : roots) {
      CqueryNode root = target.node;
      byte[] hash = target.hash;
      if (hash == null) {
        continue;
      }
      String description =
          String.format(
              "%s (%s)",
              root.getDescription(labelPrinter),
              shortId(getConfiguration(root.getConfigurationKey())));
      printStream
          .append(description)
          .append(' ')
          .append(BaseEncoding.base16().lowerCase().encode(hash))
          .append(options.getLineTerminator());
    }
    printStream.flush();
  }

  // Intern by ActionLookupKey while walking. Later operations can use the identity of a single
  // shared object per configured target and access its stable name without another map lookup.
  private static final class HashTarget extends CompactConfiguredHashes.Node<HashTarget> {
    final CqueryNode node;

    HashTarget(int ordinal, CqueryNode node) {
      super(ordinal, stableName(node));
      this.node = node;
    }

    private static String stableName(CqueryNode node) {
      String checksum = node.getConfigurationChecksum();
      String identity =
          node.getOriginalLabel().getUnambiguousCanonicalForm()
              + " " + (checksum == null ? "null" : checksum);
      return node.getLookupKey() instanceof AspectKey aspectKey
          ? identity + " " + aspectKey.getDescription()
          : identity;
    }
  }

  private static final Comparator<HashTarget> BY_NAME = Comparator.comparing(target -> target.name);

  private List<HashTarget> collectGraph(
      Iterable<CqueryNode> partialResult, List<HashTarget> roots, Set<HashTarget> failed)
      throws InterruptedException {
    Map<ActionLookupKey, HashTarget> seen = new HashMap<>();
    for (CqueryNode node : partialResult) {
      if (!seen.containsKey(node.getLookupKey())) {
        HashTarget target = new HashTarget(roots.size(), node);
        seen.put(node.getLookupKey(), target);
        roots.add(target);
      }
    }
    List<HashTarget> nodes = new ArrayList<>(roots);
    for (int i = 0; i < nodes.size(); ) {
      int end = nodes.size();
      while (i < end) {
        HashTarget target = nodes.get(i++);
        var deps = ImmutableSet.<HashTarget>builder();
        Iterable<CqueryNode> directDeps = depsRetriever.getDirectDeps(target.node);
        if (directDeps == null) {
          failed.add(target);
          continue;
        }
        for (CqueryNode dep : directDeps) {
          ActionLookupKey key = dep.getLookupKey();
          if (key.equals(target.node.getLookupKey())) {
            continue;
          }
          HashTarget candidate = seen.get(key);
          if (candidate == null) {
            candidate = new HashTarget(nodes.size(), dep);
            seen.put(key, candidate);
            nodes.add(candidate);
          }
          deps.add(candidate);
        }
        target.deps = ImmutableList.sortedCopyOf(BY_NAME, deps.build());
      }
      eventHandler.handle(
          Event.progress(String.format(
              "Hashing: visited %d configured targets (%d pending)", i, nodes.size() - i)));
    }
    return nodes;
  }

  private void computeDirectHashes(
      List<HashTarget> batch,
      Set<HashTarget> failed) throws InterruptedException {
    ImmutableSet<SkyKey> keys =
        batch.stream()
            .map(target -> DirectTargetHashFunction.key(target.node.getOriginalLabel()))
            .collect(toImmutableSet());
    EvaluationResult<SkyValue> result =
        skyframeExecutor.prepareAndGet(
            keys,
            EvaluationContext.newBuilder()
                .setParallelism(SkyQueryEnvironment.DEFAULT_THREAD_COUNT)
                .setEventHandler(eventHandler)
                .build());
    for (HashTarget target : batch) {
      CqueryNode node = target.node;
      var key = DirectTargetHashFunction.key(node.getOriginalLabel());
      var value = (DirectTargetHashFunction.Value) result.get(key);
      if (value == null) {
        failed.add(target);
        ErrorInfo error = result.getError(key);
        eventHandler.handle(
            Event.error(
                String.format(
                    "failed to compute hash for %s: %s",
                    node.getDescription(labelPrinter),
                    error != null && error.getException() != null
                        ? error.getException().getMessage()
                        : "unknown error")));
        continue;
      }
      Fingerprint fp = new Fingerprint().addBytes(value.getDirectHash());
      String checksum = node.getConfigurationChecksum();
      fp.addString(checksum == null ? "<no configuration>" : checksum);
      if (node.getLookupKey() instanceof AspectKey aspectKey) {
        fp.addString(aspectKey.getDescription());
      }
      target.hash = fp.digestAndReset();
    }
  }
}
