// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.injectVersionGetterForTesting;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.MemoryPressureEvent;
import com.google.devtools.build.lib.runtime.MemoryPressureOptions;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.HighWaterMarkLimiter;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.StringKey;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.FutureNestedDependencies;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.FutureNestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.NotifyingHelper;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameters;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TestParameterInjector.class)
public final class BazelSkycacheIntegrationTest extends SkycacheIntegrationTestBase {
  private boolean allowExternalRepositories = true;
  private final LongVersionGetter versionGetter = mock(LongVersionGetter.class);
  private final FailingFingerprintValueStore failingStore = new FailingFingerprintValueStore();

  @Before
  public void injectVersionGetter() {
    injectVersionGetterForTesting(versionGetter);
  }

  private static class FailingFingerprintValueStore implements FingerprintValueStore {
    private final FingerprintValueStore delegate = FingerprintValueStore.inMemoryStore();
    private final Map<ByteString, byte[]> entries = new ConcurrentHashMap<>();
    private ImmutableList<String> changedFiles = ImmutableList.of();
    private final AtomicBoolean shouldFail = new AtomicBoolean();
    private final AtomicInteger failCounter = new AtomicInteger();
    private final AtomicReference<KeyBytesProvider> lastFailedKey = new AtomicReference<>();

    private FailingFingerprintValueStore() {
      // The serializer omits filesystem roots because they are constant.
      delegate.put(new StringKey(":"), new byte[0]);
    }

    private void failNextPut() {
      shouldFail.set(true);
    }

    private int getFailCounter() {
      return failCounter.get();
    }

    private KeyBytesProvider getFailedKey() {
      return lastFailedKey.get();
    }

    @Override
    public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
      if (shouldFail.getAndSet(false)) {
        failCounter.getAndIncrement();
        lastFailedKey.set(fingerprint);
        return WriteStatuses.immediateFailedWriteStatus(
            new IOException("Simulated write failure for " + fingerprint));
      }
      entries.put(ByteString.copyFrom(fingerprint.toBytes()), serializedBytes);
      return delegate.put(fingerprint, serializedBytes);
    }

    @Override
    public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
      return delegate.get(fingerprint);
    }
  }

  private class ModuleWithOverrides extends SerializationModule {
    @Override
    public void workspaceInit(
        BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
      super.workspaceInit(runtime, directories, builder);
      builder.allowExternalRepositories(allowExternalRepositories);
    }

    @Override
    protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier() {
      return new TestServicesSupplier(failingStore);
    }
  }

  private static class TestServicesSupplier implements RemoteAnalysisCachingServicesSupplier {
    private final ListenableFuture<FingerprintValueService> wrappedService;
    private final RemoteAnalysisCacheClient client = mock(RemoteAnalysisCacheClient.class);

    private TestServicesSupplier(FailingFingerprintValueStore failingStore) {
      this.wrappedService =
          immediateFuture(
              new FingerprintValueService(
                  newSingleThreadExecutor(),
                  failingStore,
                  new FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
                  FingerprintValueService.NONPROD_FINGERPRINTER));
      when(client.getStats()).thenReturn(RemoteAnalysisCacheClient.EMPTY_STATS);
      when(client.lookup(any()))
          .thenAnswer(
              invocation -> {
                byte[] entry = failingStore.entries.get(invocation.getArgument(0));
                if (entry == null) {
                  return immediateFuture(
                      new LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_SKYVALUE_MISS));
                }
                // The service removes the invalidation header before returning the cached value.
                CodedInputStream input = CodedInputStream.newInstance(entry);
                switch (DataType.forNumber(input.readEnum())) {
                  case DATA_TYPE_EMPTY -> {}
                  case DATA_TYPE_FILE, DATA_TYPE_LISTING -> input.readString();
                  case DATA_TYPE_ANALYSIS_NODE -> {
                    PackedFingerprint key = PackedFingerprint.readFrom(input);
                    if (!failingStore.changedFiles.isEmpty()) {
                      var deserializer =
                          new FileDependencyDeserializer(
                              directExecutor(), FingerprintValueService.NONPROD_FINGERPRINTER);
                      var dependencies =
                          switch (deserializer.getNestedDependencies(key, failingStore)) {
                            case NestedDependencies value -> value;
                            case FutureNestedDependencies future -> future.get();
                          };
                      assertThat(dependencies.isMissingData()).isFalse();
                      var validator =
                          new VersionedChangesValidator(
                              directExecutor(), new VersionedChanges(failingStore.changedFiles));
                      var match =
                          switch (validator.matches(dependencies, 0)) {
                            case NestedMatchResult value -> value;
                            case FutureNestedMatchResult future -> future.get();
                          };
                      if (match instanceof MatchIndicator indicator && indicator.isMatch()) {
                        return immediateFuture(
                            new LookupResult(ByteString.EMPTY, MissReason.MISS_REASON_INVALIDATED));
                      }
                    }
                  }
                  case DATA_TYPE_EXECUTION_NODE -> PackedFingerprint.readFrom(input);
                  default -> throw new IOException("Unexpected cache entry header");
                }
                int offset = input.getTotalBytesRead();
                return immediateFuture(
                    new LookupResult(ByteString.copyFrom(entry, offset, entry.length - offset)));
              });
    }

    @Override
    public ListenableFuture<FingerprintValueService> getFingerprintValueService() {
      return wrappedService;
    }

    @Override
    public ListenableFuture<RemoteAnalysisCacheClient> getAnalysisCacheClient() {
      return immediateFuture(client);
    }

    @Override
    public void resetCommandState() {}
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder().addBlazeModule(new ModuleWithOverrides());
  }

  @Test
  public void genaqueryUsesCachedActionsAndTracksTemplateContents(
      @TestParameter boolean cacheReport) throws Exception {
    allowExternalRepositories = false;
    reinitializeAndPreserveOptions();
    write("scope/template", "first");
    write(
        "scope/defs.bzl",
        """
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.expand_template(template = ctx.file.template, output = out, substitutions = {})
            return [DefaultInfo(files = depset([out]))]
        root = rule(implementation = _impl, attrs = {"template": attr.label(allow_single_file = True)})
        """);
    write("scope/BUILD", "load(':defs.bzl', 'root')", "root(name = 'root', template = 'template')");
    write(
        "queries/BUILD",
        """
        genaquery(name = "report", expression = "//scope:root", scope = ["//scope:root"], output = "proto")
        """);
    String cached = cacheReport ? "//queries:report" : "//scope:root";
    addOptions("--experimental_active_directories=", "--nobuild");
    assertUploadSuccess(cached);
    getSkyframeExecutor().resetEvaluator();
    addOptions("--build", "--nouse_action_cache");
    failingStore.changedFiles = ImmutableList.of("unrelated/file");
    assertDownloadSuccess("//queries:report");
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .contains(parseCanonicalUnchecked(cached));
    var bytes = readContentAsByteArray(getArtifacts("//queries:report").iterator().next());
    assertThat(ActionGraphContainer.parseFrom(bytes).getActions(0).getTemplateContent())
        .isEqualTo("first\n");
    write("scope/template", "second");
    failingStore.changedFiles = ImmutableList.of("scope/template");
    getSkyframeExecutor().resetEvaluator();
    buildTarget("//queries:report");
    bytes = readContentAsByteArray(getArtifacts("//queries:report").iterator().next());
    assertThat(ActionGraphContainer.parseFrom(bytes).getActions(0).getTemplateContent())
        .isEqualTo("second\n");
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .doesNotContain(parseCanonicalUnchecked("//queries:report"));
  }

  @Test
  public void genaqueryRecomputesReportsWhenOutputRootsChange(
      @TestParameter boolean consumer, @TestParameter boolean cacheExecution) throws Exception {
    allowExternalRepositories = false;
    reinitializeAndPreserveOptions();
    String rules =
        """
        def _impl(ctx):
            exe = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.write(exe, "exit 0", is_executable = True)
            return [DefaultInfo(executable = exe)]
        root = rule(implementation = _impl, executable = True)
        """;
    String query =
        """
        genaquery(name = "report", expression = "mnemonic('SourceSymlinkManifest', //scope:root)",
                 scope = ["//scope:root"], output = "proto", opts = ["--include_file_write_contents"])
        genrule(name = "consumer", srcs = [":report"], outs = ["copied"],
                cmd = "cat $(location :report) > $@")
        """;
    write("scope/defs.bzl", rules);
    write("scope/BUILD", "load(':defs.bzl', 'root')", "root(name = 'root')");
    write("queries/BUILD", query);
    String target = consumer ? "//queries:consumer" : "//queries:report";
    addOptions(
        "--experimental_active_directories=",
        "--experimental_skycache_analysis_only=" + !cacheExecution);
    assertUploadSuccess(target);
    var original =
        ActionGraphContainer.parseFrom(
            readContentAsByteArray(getArtifacts(target).iterator().next()));
    getSkyframeExecutor().resetEvaluator();
    assertDownloadSuccess(target);
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .contains(parseCanonicalUnchecked(target));
    String oldRoot = directories.getOutputBase().getPathString();
    outputBaseName = "relocatedOutputBase";
    reinitializeAndPreserveOptions();
    write("scope/defs.bzl", rules);
    write("scope/BUILD", "load(':defs.bzl', 'root')", "root(name = 'root')");
    write("queries/BUILD", query);
    assertDownloadSuccess(target);
    var relocated =
        ActionGraphContainer.parseFrom(
            readContentAsByteArray(getArtifacts(target).iterator().next()));
    assertThat(relocated.getActions(0).getFileContents())
        .contains(directories.getOutputBase().getPathString());
    assertThat(relocated.getActions(0).getFileContents()).doesNotContain(oldRoot);
    assertThat(relocated.getActions(0).getActionKey())
        .isNotEqualTo(original.getActions(0).getActionKey());
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .doesNotContain(parseCanonicalUnchecked(target));
  }

  @Test
  public void genaqueryProtocolIsIndependentOfNestedSetSharing() throws Exception {
    allowExternalRepositories = false;
    reinitializeAndPreserveOptions();
    write("scope/a", "a");
    write("scope/b", "b");
    write(
        "scope/defs.bzl",
        """
        def _impl(ctx):
            for name in ["first", "second"]:
                out = ctx.actions.declare_file(name)
                ctx.actions.run_shell(inputs = depset(ctx.files.srcs), outputs = [out], command = "exit 1")
            return []
        root = rule(implementation = _impl, attrs = {"srcs": attr.label_list(allow_files = True)})
        """);
    write("scope/BUILD", "load(':defs.bzl', 'root')", "root(name = 'root', srcs = ['a', 'b'])");
    write(
        "queries/BUILD",
        "genaquery(name = 'report', expression = '//scope:root', scope = ['//scope:root'], output ="
            + " 'proto')");
    addOptions("--experimental_active_directories=", "--nobuild");
    assertUploadSuccess("//scope:root");
    addOptions(OFF_MODE_OPTION, "--build");
    buildTarget("//queries:report");
    var original = readContentAsByteArray(getArtifacts("//queries:report").iterator().next());
    getSkyframeExecutor().resetEvaluator();
    assertDownloadSuccess("//queries:report");
    assertThat(readContentAsByteArray(getArtifacts("//queries:report").iterator().next()))
        .isEqualTo(original);
  }

  @Test
  public void gencqueryAspectDepthIsIndependentOfCacheHits(@TestParameter boolean ownsLeaf)
      throws Exception {
    // Remote target values do not carry transitive packages. Exercise mixed aspect analysis in
    // the supported mode that does not collect them (as opposed to Bazel's external-repo mode).
    allowExternalRepositories = false;
    reinitializeAndPreserveOptions();
    write(
        "scope/defs.bzl",
        """
        def _aspect_impl(target, ctx):
            return []
        inspect = aspect(implementation = _aspect_impl, attr_aspects = ["srcs"], attrs = %s)
        def _impl(ctx):
            return []
        root = rule(implementation = _impl, attrs = {
            "dep": attr.label(aspects = [inspect]),
        })
        """
            .formatted(ownsLeaf ? "{\"_dep\": attr.label(default = \"//scope:leaf\")}" : "{}"));
    write(
        "scope/BUILD",
        """
        load(":defs.bzl", "root")
        package(default_visibility = ["//visibility:public"])
        filegroup(name = "leaf")
        filegroup(name = "target", srcs = [":leaf"])
        root(name = "root", dep = ":target")
        """);
    write(
        "queries/BUILD",
        """
        gencquery(name = "report", expression = "filter('^//scope:', deps(//scope:root, 1))",
                 scope = ["//scope:root"], output = "starlark")
        """);
    // Cache the base target without its aspect, which must later analyze over a remote hit.
    addOptions("--experimental_active_directories=");
    assertUploadSuccess("//scope:target");
    addOptions(OFF_MODE_OPTION);
    buildTarget("//queries:report");
    var expected = readContentAsByteArray(getArtifacts("//queries:report").iterator().next());
    assertThat(expected.toStringUtf8())
        .isEqualTo(
            ownsLeaf
                ? "@@//scope:leaf\n@@//scope:root\n@@//scope:target\n"
                : "@@//scope:root\n@@//scope:target\n");

    getSkyframeExecutor().resetEvaluator();
    var droppedState = new AtomicBoolean();
    var pressure = new EventBus();
    pressure.register(
        new HighWaterMarkLimiter(
            getSkyframeExecutor(),
            SyscallCache.NO_CACHE,
            Options.getDefaults(MemoryPressureOptions.class)));
    getSkyframeExecutor()
        .getEvaluator()
        .injectGraphTransformerForTesting(
            NotifyingHelper.makeNotifyingTransformer(
                (key, type, order, context) -> {
                  if (key instanceof ConfiguredTargetKey target
                      && target.getLabel().equals(parseCanonicalUnchecked("//scope:leaf"))
                      && type == NotifyingHelper.EventType.SET_VALUE
                      && order == NotifyingHelper.Order.AFTER
                      && droppedState.compareAndSet(false, true)) {
                    // Evict the suspended aspect's state after its cold prerequisite finishes. On
                    // restart that prerequisite is warm, but the previous physical edge remains.
                    pressure.post(
                        MemoryPressureEvent.newBuilder()
                            .setWasFullGc(true)
                            .setTenuredSpaceMaxBytes(100)
                            .setTenuredSpaceUsedBytes(100)
                            .setDuration(Duration.ZERO)
                            .build());
                  }
                }));
    assertDownloadSuccess("//queries:report");
    assertThat(droppedState.get()).isTrue();
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .contains(parseCanonicalUnchecked("//scope:target"));
    assertThat(readContentAsByteArray(getArtifacts("//queries:report").iterator().next()))
        .isEqualTo(expected);
  }

  @Test
  public void gencqueryBaseToolchainsAreNotAspectDependencies(@TestParameter boolean ownsToolchain)
      throws Exception {
    allowExternalRepositories = false;
    reinitializeAndPreserveOptions();
    write(
        "scope/defs.bzl",
        """
        def _toolchain(ctx):
            return [platform_common.ToolchainInfo()]
        implementation = rule(implementation = _toolchain)
        def _impl(ctx):
            return []
        base = rule(implementation = _impl, toolchains = ["//scope:type"])
        def _aspect(target, ctx):
            return []
        inspect = aspect(implementation = _aspect, toolchains = %s)
        root = rule(implementation = _impl, attrs = {"dep": attr.label(aspects = [inspect])})
        """
            .formatted(ownsToolchain ? "[\"//scope:type\"]" : "[]"));
    write(
        "scope/BUILD",
        """
        load(":defs.bzl", "base", "implementation", "root")
        package(default_visibility = ["//visibility:public"])
        toolchain_type(name = "type")
        implementation(name = "implementation")
        toolchain(name = "toolchain", toolchain_type = ":type", toolchain = ":implementation")
        base(name = "target")
        root(name = "root", dep = ":target")
        """);
    write(
        "queries/BUILD",
        """
        gencquery(name = "report", expression = "filter('^//scope:', deps(//scope:root, 1))",
                 scope = ["//scope:root"], output = "starlark")
        """);
    addOptions("--experimental_active_directories=", "--extra_toolchains=//scope:toolchain");
    assertUploadSuccess("//scope:target");
    addOptions(OFF_MODE_OPTION);
    buildTarget("//queries:report");
    var expected = readContentAsByteArray(getArtifacts("//queries:report").iterator().next());
    if (ownsToolchain) {
      assertThat(expected.toStringUtf8()).contains("@@//scope:implementation\n");
    } else {
      assertThat(expected.toStringUtf8()).doesNotContain("@@//scope:implementation\n");
    }
    getSkyframeExecutor().resetEvaluator();
    assertDownloadSuccess("//queries:report");
    assertThat(readContentAsByteArray(getArtifacts("//queries:report").iterator().next()))
        .isEqualTo(expected);
  }

  @Test
  @TestParameters(
      "{changedFile: 'format/format.cquery', newContents: 'def format(target): return \"second\"'}")
  @TestParameters("{changedFile: 'scope/value.bzl', newContents: 'VALUE = \"second\"'}")
  public void cachedQueryTracksSerializedAnalysisInputs(String changedFile, String newContents)
      throws Exception {
    allowExternalRepositories = false;
    reinitializeAndPreserveOptions();
    write("scope/value.bzl", "VALUE = 'first'");
    write(
        "scope/defs.bzl",
        """
        load(":value.bzl", "VALUE")
        Info = provider(fields = ["value"])
        def _impl(ctx):
            return [Info(value = VALUE)]
        leaf = rule(implementation = _impl)
        """);
    write("scope/BUILD", "load(':defs.bzl', 'leaf')", "leaf(name = 'leaf')");
    write("format/BUILD", "exports_files(['format.cquery'])");
    write(
        "format/format.cquery",
        "def format(target): return providers(target)['//scope:defs.bzl%Info'].value");
    write(
        "queries/BUILD",
        """
        gencquery(name = "report", expression = "//scope:leaf", scope = ["//scope:leaf"],
                 output = "starlark", starlark_file = "//format:format.cquery")
        """);
    addOptions("--experimental_active_directories=", "--nobuild");
    assertUploadSuccess("//queries:report");
    getSkyframeExecutor().resetEvaluator();
    addOptions("--build", "--nouse_action_cache");
    // An unrelated edit must allow the serialized analysis hit. No output or action result exists.
    failingStore.changedFiles = ImmutableList.of("unrelated/never_loaded.txt");
    assertDownloadSuccess("//queries:report");
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .contains(parseCanonicalUnchecked("//queries:report"));
    assertThat(
            readContentAsByteArray(getArtifacts("//queries:report").iterator().next())
                .toStringUtf8())
        .isEqualTo("first\n");

    write(changedFile, newContents);
    failingStore.changedFiles = ImmutableList.of(changedFile);
    getSkyframeExecutor().resetEvaluator();
    buildTarget("//queries:report");
    assertThat(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
                .filter(key -> key instanceof ActionLookupKey)
                .map(key -> ((ActionLookupKey) key).getLabel()))
        .doesNotContain(parseCanonicalUnchecked("//queries:report"));
    assertThat(
            readContentAsByteArray(getArtifacts("//queries:report").iterator().next())
                .toStringUtf8())
        .isEqualTo("second\n");
  }

  private enum InvalidAnalysisEntry {
    BARE_VALUE,
    MISSING_EDGES,
    WRONG_KIND
  }

  @Test
  public void incompleteAnalysisEntryFallsBackOnce(@TestParameter InvalidAnalysisEntry damage)
      throws Exception {
    write("scope/BUILD", "filegroup(name = 'leaf')", "filegroup(name = 'root', srcs = [':leaf'])");
    addOptions("--experimental_active_directories=", "--nobuild");
    assertUploadSuccess("//scope:root");
    var key = ConfiguredTargetKey.fromConfiguredTarget(getConfiguredTarget("//scope:root"));
    var value = getSkyframeExecutor().getEvaluator().getExistingValue(key);
    var deps = getSkyframeExecutor().getRemoteAnalysisCacheReaderDepsProvider();
    var codecs = deps.getObjectCodecs();
    var service = deps.getFingerprintValueService();
    var malformed =
        switch (damage) {
          case BARE_VALUE -> value;
          case MISSING_EDGES -> new AnalysisCacheEntry(value, null);
          case WRONG_KIND ->
              new AnalysisCacheEntry(
                  FileStateValue.NONEXISTENT_FILE_STATE_NODE, ImmutableList.of());
        };
    var serialized = codecs.serializeMemoizedAndBlocking(service, malformed);
    if (serialized.getFutureToBlockWritesOn() != null) {
      serialized.getFutureToBlockWritesOn().get();
    }
    var fingerprint =
        FingerprintValueService.computeFingerprint(service, codecs, key, deps.getSkyValueVersion());
    failingStore.entries.clear();
    failingStore.entries.put(
        ByteString.copyFrom(fingerprint.toBytes()),
        ByteString.copyFrom(new byte[] {(byte) DataType.DATA_TYPE_EMPTY.getNumber()})
            .concat(serialized.getObject())
            .toByteArray());
    getSkyframeExecutor().resetEvaluator();
    addOptions(DOWNLOAD_MODE_OPTION);
    buildTarget("//scope:root");
    // Fallback has cold prerequisites and restarts; the rejected payload must not be retried.
    assertThat(
            getCommandEnvironment()
                .getRemoteAnalysisCachingEventListener()
                .getSerializationExceptionCounts())
        .isEqualTo(1);
    assertThat(getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits())
        .isEmpty();
  }

  @Test
  public void buildCommand_uploadsFrontierBytesWithUploadMode() throws Exception {
    setupScenarioWithAspects();
    assertUploadSuccess("//bar:one");

    var listener = getCommandEnvironment().getRemoteAnalysisCachingEventListener();
    assertThat(listener.getSerializedKeysCount()).isAtLeast(9); // for Bazel
    assertThat(listener.getSkyfunctionCounts().count(SkyFunctions.CONFIGURED_TARGET))
        .isAtLeast(9); // for Bazel
  }

  @Test
  public void buildCommand_withWriteFailure_reportsErrorAndCompletes() throws Exception {
    setupScenarioWithAspects();

    failingStore.failNextPut();

    addOptions(UPLOAD_MODE_OPTION);
    var thrown = assertThrows(AbruptExitException.class, () -> buildTarget("//bar:one"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Simulated write failure for " + failingStore.getFailedKey());

    assertThat(failingStore.getFailCounter()).isEqualTo(1);
    assertContainsEvent("Simulated write failure for " + failingStore.getFailedKey());
  }
}
