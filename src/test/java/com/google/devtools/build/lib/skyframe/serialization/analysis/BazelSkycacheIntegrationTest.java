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
import static com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.injectVersionGetterForTesting;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BazelSkycacheIntegrationTest extends SkycacheIntegrationTestBase {
  private final LongVersionGetter versionGetter = mock(LongVersionGetter.class);
  private final FailingFingerprintValueStore failingStore = new FailingFingerprintValueStore();

  @Before
  public void injectVersionGetter() {
    injectVersionGetterForTesting(versionGetter);
  }

  private static class FailingFingerprintValueStore implements FingerprintValueStore {
    private final FingerprintValueStore delegate = FingerprintValueStore.inMemoryStore();
    private final Map<ByteString, byte[]> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean shouldFail = new AtomicBoolean();
    private final AtomicInteger failCounter = new AtomicInteger();
    private final AtomicReference<KeyBytesProvider> lastFailedKey = new AtomicReference<>();

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
                  case DATA_TYPE_ANALYSIS_NODE, DATA_TYPE_EXECUTION_NODE ->
                      PackedFingerprint.readFrom(input);
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
