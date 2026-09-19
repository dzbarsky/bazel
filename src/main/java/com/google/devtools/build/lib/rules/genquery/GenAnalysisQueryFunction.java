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
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.ConfiguredObjectValue;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.ParserInput;

/** Acquires a tracked query graph, evaluates it, and retains only the formatted result. */
public final class GenAnalysisQueryFunction implements SkyFunction {
  /** Keeps query implementation dependencies out of the Skyframe analysis library. */
  public interface QueryEvaluator {
    @Nullable
    GenQueryResult evaluate(
        GenAnalysisQueryKey.Request request,
        RepositoryMapping repositoryMapping,
        StarlarkSemantics semantics,
        GenAnalysisQueryScope scope,
        @Nullable ParserInput formatter,
        Environment env)
        throws InterruptedException, QueryException, IOException;
  }

  private final ImmutableMap<GenAnalysisQueryKey.Kind, QueryEvaluator> evaluators;
  private final Supplier<WalkableGraph> graphSupplier;
  private final BooleanSupplier tracksIncrementalState;
  private final AtomicReference<Semaphore> cpuBoundSemaphore;

  public GenAnalysisQueryFunction(
      ImmutableMap<GenAnalysisQueryKey.Kind, QueryEvaluator> evaluators,
      Supplier<WalkableGraph> graphSupplier,
      BooleanSupplier tracksIncrementalState,
      AtomicReference<Semaphore> cpuBoundSemaphore) {
    this.evaluators = evaluators;
    this.graphSupplier = graphSupplier;
    this.tracksIncrementalState = tracksIncrementalState;
    this.cpuBoundSemaphore = cpuBoundSemaphore;
  }

  private static final class State implements SkyKeyComputeState {
    final Map<SkyKey, SkyValue> values = new LinkedHashMap<>();
    final Map<SkyKey, Iterable<SkyKey>> directDeps = new LinkedHashMap<>();
    final Set<SkyKey> metadataKeys = new LinkedHashSet<>();
    final Set<SkyKey> pending;

    State(GenAnalysisQueryKey key) {
      pending = new LinkedHashSet<>(key.roots());
      // Literal-resolution metadata does not add configured targets to the query scope.
      metadataKeys.addAll(key.argument().targetPatternPackages());
    }
    // Default close is intentional: eviction must not mutate an active invocation's maps.
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, QueryFunctionException {
    GenAnalysisQueryKey queryKey = (GenAnalysisQueryKey) skyKey;
    GenAnalysisQueryKey.Request request = queryKey.argument();
    try {
      if (!tracksIncrementalState.getAsBoolean()) {
        throw new QueryException(request.kind().ruleName() + " requires --track_incremental_state");
      }
      // These control inputs invalidate even empty requests, but are not query-scope members.
      PackageValue owner = (PackageValue) env.getValue(request.owner().getPackageIdentifier());
      env.getValue(request.configuration());
      StarlarkSemantics semantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
      if (env.valuesMissing()) {
        return null;
      }

      WalkableGraph graph = graphSupplier.get();
      State state = env.getState(() -> new State(queryKey));
      while (!state.pending.isEmpty()) {
        SkyframeLookupResult lookup = env.getValuesAndExceptions(state.pending);
        Set<SkyKey> next = new LinkedHashSet<>();
        var iterator = state.pending.iterator();
        while (iterator.hasNext()) {
          SkyKey key = iterator.next();
          SkyValue value = lookup.get(key);
          if (value == null) {
            continue;
          }
          iterator.remove();
          state.values.put(key, value);
          if (value instanceof ConfiguredTargetValue configuredTargetValue) {
            var target = configuredTargetValue.getConfiguredTarget();
            state.metadataKeys.add(target.getOriginalLabel().getPackageIdentifier());
            if (target.getConfigurationKey() != null) {
              state.metadataKeys.add(target.getConfigurationKey());
            }
          }
          // Never walk reverse edges in the live graph: they may include unrelated builds or the
          // query rule itself. Reading edges is safe only after requesting the parent through env.
          // Track every visited value so changes to both nodes and edges invalidate this snapshot.
          // Only query results need sorting. Stringifying aspect keys here can exponentially
          // expand their shared base-aspect graphs.
          ImmutableList<SkyKey> deps =
              value instanceof ConfiguredObjectValue configuredValue
                  ? configuredValue.getQueryDependencies(key)
                  : null;
          if (deps == null) {
            Iterable<SkyKey> directDeps = graph.getDirectDeps(key);
            if (value instanceof ConfiguredObjectValue configuredValue) {
              var excluded = configuredValue.getQueryDependencyExclusions(key);
              if (!excluded.isEmpty()) {
                directDeps =
                    Iterables.filter(directDeps, dependency -> !excluded.contains(dependency));
              }
            }
            deps = GenAnalysisQueryKey.queryDependencies(directDeps);
          }
          state.directDeps.put(key, deps);
          next.addAll(deps);
        }
        next.removeAll(state.values.keySet());
        state.pending.addAll(next);
        if (env.valuesMissing()) {
          return null;
        }
      }
      SkyframeLookupResult metadata = env.getValuesAndExceptions(state.metadataKeys);
      var iterator = state.metadataKeys.iterator();
      while (iterator.hasNext()) {
        SkyKey key = iterator.next();
        SkyValue value = metadata.get(key);
        if (value == null) {
          continue;
        }
        iterator.remove();
        state.values.put(key, value);
      }
      if (env.valuesMissing()) {
        return null;
      }

      if (request.formatter() != null) {
        var source = request.formatter();
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
          throw new QueryException("must be an existing regular file", "starlark_file");
        }
      }

      // All analysis dependency requests precede admission: nested reports and cold scope analysis
      // must be able to acquire the same permit. Release precisely the captured semaphore.
      Semaphore semaphore = cpuBoundSemaphore.get();
      if (semaphore != null) {
        semaphore.acquire();
      }
      try {
        ParserInput formatter = null;
        if (request.formatter() != null) {
          var source = request.formatter();
          try {
            formatter =
                ParserInput.fromCharArray(
                    FileSystemUtils.readContentAsLatin1(source.getPath()),
                    source.getExecPathString());
          } catch (IOException e) {
            throw new QueryFunctionException(
                new QueryException("cannot read file: " + e.getMessage(), "starlark_file"),
                SkyFunctionException.Transience.TRANSIENT);
          }
        }
        GenQueryResult result =
            evaluators
                .get(request.kind())
                .evaluate(
                    request,
                    owner.getPackage().getMetadata().repositoryMapping(),
                    semantics,
                    new GenAnalysisQueryScope(queryKey.roots(), state.values, state.directDeps),
                    formatter,
                    env);
        return result == null ? null : new GenAnalysisQueryValue(result);
      } finally {
        if (semaphore != null) {
          semaphore.release();
        }
      }
    } catch (IOException e) {
      throw new QueryFunctionException(
          new QueryException(request.kind().ruleName() + " failed: " + e.getMessage()),
          SkyFunctionException.Transience.TRANSIENT);
    } catch (QueryException e) {
      throw new QueryFunctionException(e, SkyFunctionException.Transience.PERSISTENT);
    }
  }

  /** A rule- or attribute-oriented failure, reported by the native rule. */
  public static final class QueryException extends Exception {
    @Nullable final String attribute;

    QueryException(String message) {
      this(message, null);
    }

    private QueryException(String message, @Nullable String attribute) {
      super(message);
      this.attribute = attribute;
    }
  }

  private static final class QueryFunctionException extends SkyFunctionException {
    QueryFunctionException(QueryException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
