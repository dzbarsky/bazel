// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool;

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.concurrent.MoreFutures;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.query2.PostAnalysisQueryEnvironment.TopLevelConfigurations;
import com.google.devtools.build.lib.query2.common.CommonQueryOptions;
import com.google.devtools.build.lib.query2.common.CqueryNode;
import com.google.devtools.build.lib.query2.cquery.ConfiguredTargetQueryEnvironment;
import com.google.devtools.build.lib.query2.cquery.CqueryOptions;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver;
import com.google.devtools.build.lib.query2.query.output.StreamedJSONProtoOutputFormatter;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Query;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator;
import com.google.devtools.build.lib.skyframe.TargetPatternPhaseValue;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.WalkableGraph;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkSemantics;

/** Performs {@code cquery} processing. */
public final class CqueryProcessor extends PostAnalysisQueryProcessor<CqueryNode> {
  private static final int CATALOG_CHUNK_SIZE = 128;
  private static final int CATALOG_CHUNK_MEMORY_LIMIT = 1 << 20;

  public CqueryProcessor(
      QueryExpression queryExpression, TargetPattern.Parser mainRepoTargetParser) {
    super(queryExpression, mainRepoTargetParser);
  }

  @Override
  protected CommonQueryOptions getQueryOptions(CommandEnvironment env) {
    return env.getOptions().getOptions(CqueryOptions.class);
  }

  @Override
  public TargetPatternPhaseValue afterTargetPatternLoading(
      BuildRequest request, CommandEnvironment env, TargetPatternPhaseValue loadingResult)
      throws InterruptedException, ViewCreationFailedException {
    CqueryOptions options = request.getOptions(CqueryOptions.class);
    if (request.targetsAreLabels() && options.getHashStreamReceipt()) {
      var requested = ImmutableSet.copyOf(request.getTargets());
      var loaded = loadingResult.getTargetLabels();
      if (loadingResult.hasError()
          || loadingResult.hasPostExpansionError()
          || requested.size() != request.getTargets().size()
          || requested.size() != loaded.size()
          || loaded.stream()
              .anyMatch(label -> !requested.contains(label.getCanonicalForm()))) {
        String message = "A hash stream receipt requires successfully loaded, unique canonical labels";
        throw new ViewCreationFailedException(
            message,
            FailureDetail.newBuilder()
                .setMessage(message)
                .setQuery(Query.newBuilder().setCode(Query.Code.BUILD_FILE_ERROR))
                .build());
      }
    }
    if (options.getRuleCatalogOutput().isEmpty()) {
      return loadingResult;
    }
    boolean filterRoots = !options.getRootFilterFile().isEmpty();
    if (loadingResult.hasError() || loadingResult.hasPostExpansionError()) {
      if (filterRoots) {
        String message = "A cquery root filter requires successfully loaded targets";
        throw new ViewCreationFailedException(
            message,
            FailureDetail.newBuilder()
                .setMessage(message)
                .setQuery(Query.newBuilder().setCode(Query.Code.BUILD_FILE_ERROR))
                .build());
      }
      env.getReporter().handle(Event.warn("Skipping rule catalog because target loading failed."));
      return loadingResult;
    }
    Path output = env.getWorkingDirectory().getRelative(options.getRuleCatalogOutput());
    try (var unused = Profiler.instance().profile("cquery.writeRuleCatalog")) {
      Path filterFile =
          filterRoots ? env.getWorkingDirectory().getRelative(options.getRootFilterFile()) : null;
      return writeRuleCatalog(env, options, loadingResult, output, filterFile);
    } catch (IOException | NoSuchTargetException e) {
      String message = "Error writing cquery rule catalog: " + e.getMessage();
      throw new ViewCreationFailedException(
          message,
          FailureDetail.newBuilder()
              .setMessage(message)
              .setQuery(Query.newBuilder().setCode(Query.Code.OUTPUT_FORMATTER_IO_EXCEPTION))
              .build());
    }
  }

  private static Path omissionsPath(Path catalog) {
    return catalog.getParentDirectory().getChild(catalog.getBaseName() + ".omitted");
  }

  /** Invocation-local filter constructed from the caller's candidate list and tag policy. */
  private record RootFilter(
      Set<String> candidates,
      Pattern excludedTags,
      String companionSuffix,
      String inputDigest,
      Set<String> protectedPeers,
      Set<Label> omitted) {
    private static RootFilter read(Path input) throws IOException {
      try (var stream = new HashingInputStream(Hashing.sha256(), input.getInputStream());
          var reader =
              new BufferedReader(
                  new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()))) {
        String header = reader.readLine();
        String[] fields = header == null ? new String[0] : header.split("\t", -1);
        if (fields.length != 3
            || !fields[0].equals("# bazel-cquery-root-filter-v1")
            || fields[1].isEmpty()) {
          throw new IOException("Invalid cquery root filter header: " + input);
        }
        Pattern tags;
        try {
          tags = Pattern.compile(fields[1]);
        } catch (PatternSyntaxException e) {
          throw new IOException("Invalid cquery root filter tag regex", e);
        }
        var candidates = new HashSet<String>();
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("//") || !line.contains(":") || line.indexOf('\t') >= 0) {
            throw new IOException("Expected a canonical main-repository label in " + input);
          }
          candidates.add(line);
        }
        return new RootFilter(
            candidates,
            tags,
            fields[2],
            stream.hash().toString(),
            new HashSet<>(),
            new LinkedHashSet<>());
      }
    }

    private void consider(Label label, Rule rule) {
      String canonical = internalToUnicode(label.getCanonicalForm());
      String name = internalToUnicode(label.getName());
      if (!companionSuffix.isEmpty() && name.endsWith(companionSuffix)) {
        protectedPeers.add(canonical.substring(0, canonical.length() - companionSuffix.length()));
      } else if (candidates.contains(canonical)) {
        for (String tag : rule.getOnlyTagsAttribute()) {
          if (excludedTags.matcher(internalToUnicode(tag)).find()) {
            omitted.add(label);
            break;
          }
        }
      }
    }

    private TargetPatternPhaseValue finish(
        TargetPatternPhaseValue loaded, Path proof, String invocation) throws IOException {
      omitted.retainAll(loaded.getTargetLabels());
      if (!protectedPeers.isEmpty()) {
        omitted.removeIf(
            label -> protectedPeers.contains(internalToUnicode(label.getCanonicalForm())));
      }
      try (var writer =
          new BufferedWriter(
              new OutputStreamWriter(proof.getOutputStream(), StandardCharsets.UTF_8))) {
        writer.write("# bazel-cquery-root-omissions-v1\t" + invocation + "\t" + inputDigest + "\n");
        for (Label label : omitted) {
          writer.write(internalToUnicode(label.getCanonicalForm()));
          writer.write('\n');
        }
        writer.write("# complete\n");
      }
      return omitted.isEmpty()
          ? loaded
          : loaded.withTargetLabels(
              ImmutableSet.copyOf(Sets.difference(loaded.getTargetLabels(), omitted)));
    }
  }

  private TargetPatternPhaseValue writeRuleCatalog(
      CommandEnvironment env,
      CqueryOptions options,
      TargetPatternPhaseValue loadingResult,
      Path output,
      @Nullable Path filterFile)
      throws IOException, NoSuchTargetException, InterruptedException {
    Path parent = output.getParentDirectory();
    if (parent == null) {
      throw new IOException("Catalog output must be a file: " + output);
    }
    RootFilter filter = null;
    if (filterFile != null) {
      if (filterFile.equals(output) || filterFile.equals(omissionsPath(output))) {
        throw new IOException("Root filter input must be different from the catalog outputs");
      }
      filter = RootFilter.read(filterFile);
    }
    var executor = env.getSkyframeExecutor();
    var starlarkSemantics =
        executor.getEffectiveStarlarkSemantics(
            env.getOptions().getOptions(BuildLanguageOptions.class));
    var labelPrinter =
        options.getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping());
    var formatter = new StreamedJSONProtoOutputFormatter();
    formatter.setOptions(
        options,
        options.getAspectDeps().createResolver(env.getPackageManager(), env.getReporter()),
        env.getRuntime().getFileSystem().getDigestFunction().getHashFunction());

    Path staging = parent.createTempDirectory(".rule-catalog-");
    try {
      Path temporary = staging.getChild("catalog");
      int available = Runtime.getRuntime().availableProcessors();
      int requested = options.getHashWorkers();
      int workers = requested == 0 ? available : Math.min(available, requested);
      // Rule class metadata is emitted only for the first occurrence of each rule class.
      // Precise aspect resolution can also load packages while formatting.
      boolean parallel =
          workers > 1
              && !options.getProtoRuleClasses()
              && options.getAspectDeps() != AspectResolver.Mode.PRECISE;
      try (var out = new BufferedOutputStream(temporary.getOutputStream(), 16384);
          var pool =
              parallel
                  ? Executors.newFixedThreadPool(
                      workers, Thread.ofPlatform().name("cquery-rule-catalog-", 0).factory())
                  : null) {
        var packages = new HashMap<PackageIdentifier, Package>();
        var pending = new ArrayDeque<Future<CatalogChunk>>();
        int nextChunk = 0;
        var chunk = new ArrayList<Rule>(CATALOG_CHUNK_SIZE);
        try {
          for (var label : loadingResult.getNonExpandedLabels()) {
            var id = label.getPackageIdentifier();
            Package pkg = packages.get(id);
            if (pkg == null) {
              pkg = executor.getExistingPackage(id);
              if (pkg == null) {
                throw new IOException("Loaded package is missing from the graph: " + id);
              }
              packages.put(id, pkg);
            }
            if (!(pkg.getTarget(label.getName()) instanceof Rule rule)) {
              continue;
            }
            if (filter != null) {
              filter.consider(label, rule);
            }
            if (pool == null) {
              formatter.writeTarget(rule, labelPrinter, out);
              continue;
            }
            chunk.add(rule);
            if (chunk.size() == CATALOG_CHUNK_SIZE) {
              // Bound queued chunks and drain in target-pattern order.
              if (pending.size() == 2 * workers) {
                writeCatalogChunk(pending.removeFirst(), out);
              }
              List<Rule> rules = chunk;
              Path spill = staging.getChild("chunk-" + nextChunk++);
              pending.addLast(
                  pool.submit(
                      () -> {
                        try (var bytes = new CatalogChunk(spill)) {
                          for (var target : rules) {
                            formatter.writeTarget(target, labelPrinter, bytes);
                          }
                          return bytes;
                        }
                      }));
              chunk = new ArrayList<>(CATALOG_CHUNK_SIZE);
            }
          }
          while (!pending.isEmpty()) {
            writeCatalogChunk(pending.removeFirst(), out);
          }
          for (var rule : chunk) {
            formatter.writeTarget(rule, labelPrinter, out);
          }
        } finally {
          if (pool != null) {
            pool.shutdownNow();
          }
        }
      }
      if (filter != null) {
        Path omissions = staging.getChild("omitted");
        loadingResult = filter.finish(loadingResult, omissions, env.getCommandId().toString());
        // Consumers can observe the catalog only after its omission proof is available.
        omissions.renameTo(omissionsPath(output));
      }
      temporary.renameTo(output);
      return loadingResult;
    } finally {
      staging.deleteTree();
    }
  }

  private static void writeCatalogChunk(Future<CatalogChunk> chunk, OutputStream out)
      throws IOException, InterruptedException {
    MoreFutures.waitForFutureAndGetWithCheckedException(
            chunk, /* cancelOnInterrupt= */ true, IOException.class)
        .writeTo(out);
  }

  /** Keeps ordinary chunks in memory and spills larger ones beside the staged catalog. */
  private static final class CatalogChunk extends OutputStream {
    private final Path spill;
    private OutputStream out = new ByteArrayOutputStream();

    private CatalogChunk(Path spill) {
      this.spill = spill;
    }

    @Override
    public void write(int value) throws IOException {
      spillIfNeeded(1);
      out.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      spillIfNeeded(length);
      out.write(bytes, offset, length);
    }

    private void spillIfNeeded(int length) throws IOException {
      if (out instanceof ByteArrayOutputStream memory
          && length > CATALOG_CHUNK_MEMORY_LIMIT - memory.size()) {
        out = new BufferedOutputStream(spill.getOutputStream());
        memory.writeTo(out);
      }
    }

    @Override
    public void close() throws IOException {
      out.close();
    }

    private void writeTo(OutputStream destination) throws IOException {
      if (out instanceof ByteArrayOutputStream memory) {
        memory.writeTo(destination);
        return;
      }
      try (var input = spill.getInputStream()) {
        input.transferTo(destination);
      }
      spill.delete();
    }
  }

  @Override
  protected ConfiguredTargetQueryEnvironment getQueryEnvironment(
      BuildRequest request,
      CommandEnvironment env,
      TopLevelConfigurations configurations,
      ImmutableMap<String, BuildConfigurationValue> transitiveConfigurations,
      ImmutableMap<AspectKeyCreator.AspectKey, ConfiguredAspect> topLevelAspects,
      WalkableGraph walkableGraph) {
    ImmutableList<QueryFunction> extraFunctions =
        new ImmutableList.Builder<QueryFunction>()
            .addAll(ConfiguredTargetQueryEnvironment.CQUERY_FUNCTIONS)
            .addAll(env.getRuntime().getQueryFunctions())
            .build();
    CqueryOptions cqueryOptions = request.getOptions(CqueryOptions.class);
    StarlarkSemantics starlarkSemantics =
        env.getSkyframeExecutor()
            .getEffectiveStarlarkSemantics(env.getOptions().getOptions(BuildLanguageOptions.class));
    return new ConfiguredTargetQueryEnvironment(
        request.getKeepGoing(),
        env.getReporter(),
        extraFunctions,
        configurations,
        transitiveConfigurations,
        topLevelAspects,
        mainRepoTargetParser,
        env.getPackageManager().getPackagePath(),
        () -> walkableGraph,
        cqueryOptions,
        request.getTopLevelArtifactContext(),
        request
            .getOptions(CqueryOptions.class)
            .getLabelPrinter(starlarkSemantics, mainRepoTargetParser.getRepoMapping()));
  }
}
