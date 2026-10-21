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
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.devtools.build.lib.remote.BatchOutputServiceProto.*;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BatchOutputServiceClientTest {
  private Server server;
  private ManagedChannel channel;
  private BatchOutputServiceClient client;
  private Path root;
  private final List<BatchMkdirRequest> mkdirRequests = new ArrayList<>();
  private BiConsumer<BatchMkdirRequest, StreamObserver<BatchMkdirResponse>> mkdirHandler;
  private final List<BatchStatRequest> statRequests = new ArrayList<>();
  private final List<BatchSymlinkRequest> symlinkRequests = new ArrayList<>();
  private BiConsumer<BatchStatRequest, StreamObserver<BatchStatResponse>> statHandler;
  private BiConsumer<BatchSymlinkRequest, StreamObserver<BatchSymlinkResponse>> symlinkHandler;

  @Before
  public void setUp() throws Exception {
    root = new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/execroot/main");
    root.createDirectoryAndParents();
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                new BatchOutputServiceGrpc.BatchOutputServiceImplBase() {
                  @Override
                  public void batchMkdir(
                      BatchMkdirRequest request, StreamObserver<BatchMkdirResponse> response) {
                    mkdirRequests.add(request);
                    mkdirHandler.accept(request, response);
                  }

                  @Override
                  public void batchStat(
                      BatchStatRequest request, StreamObserver<BatchStatResponse> response) {
                    statRequests.add(request);
                    statHandler.accept(request, response);
                  }

                  @Override
                  public void batchSymlink(
                      BatchSymlinkRequest request, StreamObserver<BatchSymlinkResponse> response) {
                    symlinkRequests.add(request);
                    symlinkHandler.accept(request, response);
                  }
                })
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new BatchOutputServiceClient(channel, () -> root, 0, 16, 0);
  }

  @Test
  public void directoryBatchesPreservePrefixAcrossChunkBoundary() throws Exception {
    List<PathFragment> paths = new ArrayList<>();
    for (int i = 0; i < 8193; i++) {
      paths.add(PathFragment.create("directory-" + i));
    }
    mkdirHandler =
        (request, response) -> {
          int created = mkdirRequests.size() == 1 ? request.getPathsCount() : 1;
          response.onNext(BatchMkdirResponse.newBuilder().setCreatedCount(created).build());
          response.onCompleted();
        };
    assertThat(client.createDirectories(root, paths)).isEqualTo(4097);
    assertThat(mkdirRequests).hasSize(2);
    assertThat(mkdirRequests.get(0).getPathsCount()).isEqualTo(4096);
    assertThat(mkdirRequests.get(1).getPaths(0).toStringUtf8()).isEqualTo("directory-4096");
  }

  @Test
  public void directoryCutoffAndUnsupportedPerformNoWrites() throws Exception {
    client = new BatchOutputServiceClient(channel, () -> root, 0, 16, 2);
    var paths = List.of(PathFragment.create("a"), PathFragment.create("a/b"));
    assertThat(client.createDirectories(root, paths.subList(0, 1))).isEqualTo(-1);
    assertThat(mkdirRequests).isEmpty();
    mkdirHandler =
        (request, response) -> response.onError(Status.UNIMPLEMENTED.asRuntimeException());
    assertThat(client.createDirectories(root, paths)).isEqualTo(-1);
    assertThat(client.createDirectories(root, paths)).isEqualTo(-1);
    assertThat(mkdirRequests).hasSize(1);
    assertThat(root.getDirectoryEntries()).isEmpty();
  }

  @Test
  public void directoryTransportFailureAndInvalidPrefixAreFatal() throws Exception {
    var paths = List.of(PathFragment.create("a"));
    mkdirHandler = (request, response) -> response.onError(Status.UNAVAILABLE.asRuntimeException());
    IOException error =
        assertThrows(IOException.class, () -> client.createDirectories(root, paths));
    assertThat(error).hasMessageThat().contains("completion is unknown");
    for (int count : List.of(-1, 2)) {
      mkdirHandler =
          (request, response) -> {
            response.onNext(BatchMkdirResponse.newBuilder().setCreatedCount(count).build());
            response.onCompleted();
          };
      assertThrows(IOException.class, () -> client.createDirectories(root, paths));
    }
    assertThat(root.getDirectoryEntries()).isEmpty();
  }

  @After
  public void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  public void statPreservesMetadataOrderMissingEntriesAndDuplicates() throws Exception {
    var file =
        FileStat.newBuilder()
            .setMode(0100640)
            .setInode(123)
            .setSize(456)
            .setMtimeSeconds(-1)
            .setMtimeNanos(999_000_000)
            .setCtimeSeconds(7)
            .build();
    statHandler =
        (request, observer) -> {
          observer.onNext(
              BatchStatResponse.newBuilder()
                  .addStats(file)
                  .addStats(FileStat.newBuilder().setError(2))
                  .addStats(file)
                  .addStats(FileStat.newBuilder().setError(20))
                  .build());
          observer.onCompleted();
        };
    var statuses =
        client.batchStat(
            List.of(
                PathFragment.create("a"),
                PathFragment.create("missing"),
                PathFragment.create("a"),
                PathFragment.create("a/child")));
    assertThat(statuses).hasSize(4);
    assertThat(statuses.get(1)).isNull();
    assertThat(statuses.get(3)).isNull();
    assertThat(statuses.get(0)).isEqualTo(statuses.get(2));
    assertThat(statuses.get(0).isFile()).isTrue();
    assertThat(statuses.get(0).getNodeId()).isEqualTo(123);
    assertThat(statuses.get(0).getSize()).isEqualTo(456);
    assertThat(statuses.get(0).getPermissions()).isEqualTo(0640);
    assertThat(statuses.get(0).getLastModifiedTime()).isEqualTo(-1);
    assertThat(statuses.get(0).getLastChangeTime()).isEqualTo(7000);
    assertThat(statuses.get(0).getDigest()).isNull();
  }

  @Test
  public void malformedOrUnsupportedStatsRequireLocalFallback() {
    for (FileStat stat :
        List.of(
            FileStat.getDefaultInstance(),
            FileStat.newBuilder().setError(13).build(),
            FileStat.newBuilder().setError(95).build(),
            FileStat.newBuilder().setMode(0100644).setMtimeNanos(-1).build())) {
      statHandler =
          (request, observer) -> {
            observer.onNext(BatchStatResponse.newBuilder().addStats(stat).build());
            observer.onCompleted();
          };
      assertThrows(IOException.class, () -> client.batchStat(List.of(PathFragment.create("a"))));
    }
    statHandler =
        (request, observer) -> {
          observer.onNext(BatchStatResponse.getDefaultInstance());
          observer.onCompleted();
        };
    assertThrows(IOException.class, () -> client.batchStat(List.of(PathFragment.create("a"))));
  }

  @Test
  public void statChunksAreBoundedAndPreserveEveryPath() throws Exception {
    statHandler =
        (request, observer) -> {
          var response = BatchStatResponse.newBuilder();
          for (var path : request.getPathsList()) {
            response.addStats(FileStat.newBuilder().setError(2));
          }
          observer.onNext(response.build());
          observer.onCompleted();
        };
    List<PathFragment> paths = new ArrayList<>();
    for (int i = 0; i < 10000; i++) {
      paths.add(PathFragment.create("p/f" + i));
    }
    assertThat(client.batchStat(paths)).hasSize(paths.size());
    assertThat(statRequests.stream().map(BatchStatRequest::getPathsCount).toList())
        .containsExactly(4096, 4096, 1808)
        .inOrder();
    assertThat(statRequests.get(2).getPaths(1807).toStringUtf8()).isEqualTo("p/f9999");
  }

  @Test
  public void unsupportedStatIsOnlyProbedOnce() {
    statHandler =
        (request, observer) -> observer.onError(Status.UNIMPLEMENTED.asRuntimeException());
    for (int i = 0; i < 2; i++) {
      assertThrows(IOException.class, () -> client.batchStat(List.of(PathFragment.create("a"))));
    }
    assertThat(statRequests).hasSize(1);
  }

  @Test
  public void symlinksShareAnRpcAcrossDirectoriesAndKeepTargetBytes() throws Exception {
    symlinkHandler =
        (request, observer) -> {
          observer.onNext(BatchSymlinkResponse.newBuilder().addErrors(0).addErrors(0).build());
          observer.onCompleted();
        };
    client.createSymlinks(
        root,
        Map.of(
            PathFragment.create("a/link"),
            PathFragment.create("."),
            PathFragment.create("b/link"),
            PathFragment.create("\u00ff")));
    assertThat(symlinkRequests).hasSize(1);
    assertThat(
            symlinkRequests.getFirst().getSymlinksList().stream().map(Symlink::getTarget).toList())
        .containsExactly(
            ByteString.copyFromUtf8("."), ByteString.copyFrom(new byte[] {(byte) 0xff}));
  }

  @Test
  public void symlinkCutoffUsesLocalCreationBelowThresholdAndRpcAtThreshold() throws Exception {
    client = new BatchOutputServiceClient(channel, () -> root, 3, 16, 0);
    root.getChild("a").createDirectory();
    client.createSymlinks(
        root,
        Map.of(
            PathFragment.create("a/link"), PathFragment.create("../target"),
            PathFragment.create("other"), PathFragment.create("\u00ff")));
    assertThat(symlinkRequests).isEmpty();
    assertThat(root.getRelative("a/link").readSymbolicLink())
        .isEqualTo(PathFragment.create("../target"));
    assertThat(root.getChild("other").readSymbolicLink()).isEqualTo(PathFragment.create("\u00ff"));

    symlinkHandler =
        (request, observer) -> {
          observer.onNext(successfulSymlinks(request.getSymlinksCount()));
          observer.onCompleted();
        };
    client.createSymlinks(root, symlinks(3));
    assertThat(symlinkRequests).hasSize(1);
    assertThat(symlinkRequests.getFirst().getSymlinksCount()).isEqualTo(3);
  }

  @Test
  public void unsupportedSymlinksFallBackLocally() throws Exception {
    symlinkHandler =
        (request, observer) -> observer.onError(Status.UNIMPLEMENTED.asRuntimeException());
    client.createSymlinks(root, Map.of(PathFragment.create("link"), PathFragment.create("target")));
    client.createSymlinks(root, Map.of(PathFragment.create("second"), PathFragment.create(".")));
    assertThat(root.getChild("link").readSymbolicLink()).isEqualTo(PathFragment.create("target"));
    assertThat(root.getChild("second").readSymbolicLink()).isEqualTo(PathFragment.create("."));
    assertThat(symlinkRequests).hasSize(1);
  }

  @Test
  public void ambiguousSymlinkFailureDoesNotRetryOrFallBack() {
    symlinkHandler =
        (request, observer) -> observer.onError(Status.UNAVAILABLE.asRuntimeException());
    assertThrows(
        IOException.class,
        () ->
            client.createSymlinks(
                root, Map.of(PathFragment.create("link"), PathFragment.create("target"))));
    assertThat(symlinkRequests).hasSize(1);
    assertThat(root.getChild("link").isSymbolicLink()).isFalse();
  }

  @Test
  public void malformedSymlinkResponseDoesNotFallBack() {
    symlinkHandler =
        (request, observer) -> {
          observer.onNext(BatchSymlinkResponse.getDefaultInstance());
          observer.onCompleted();
        };
    assertThrows(
        IOException.class,
        () ->
            client.createSymlinks(
                root, Map.of(PathFragment.create("link"), PathFragment.create("target"))));
    assertThat(root.getChild("link").isSymbolicLink()).isFalse();
  }

  @Test(timeout = 10000)
  public void symlinkBatchesAreBoundedAndAcceptOutOfOrderReplies() throws Exception {
    client = new BatchOutputServiceClient(channel, () -> root, 4096, 16, 0);
    List<StreamObserver<BatchSymlinkResponse>> observers = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    symlinkHandler =
        (request, observer) -> {
          observers.add(observer);
          sizes.add(request.getSymlinksCount());
          if (observers.size() == 4 || request.getSymlinksCount() == 1) {
            for (int i = observers.size() - 1; i >= 0; i--) {
              observers.get(i).onNext(successfulSymlinks(sizes.get(i)));
              observers.get(i).onCompleted();
            }
            observers.clear();
            sizes.clear();
          }
        };
    client.createSymlinks(root, symlinks(4 * 4096 + 1));
    assertThat(symlinkRequests.stream().map(BatchSymlinkRequest::getSymlinksCount).toList())
        .containsExactly(4096, 4096, 4096, 4096, 1)
        .inOrder();
    assertThat(observers).isEmpty();
  }

  @Test(timeout = 10000)
  public void completedSymlinkBatchRefillsSlotBeforeEarlierRequestsFinish() throws Exception {
    var received = new LinkedBlockingQueue<StreamObserver<BatchSymlinkResponse>>();
    symlinkHandler = (request, observer) -> received.add(observer);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var result =
          executor.submit(
              () -> {
                client.createSymlinks(root, symlinks(4 * 4096 + 1));
                return null;
              });
      List<StreamObserver<BatchSymlinkResponse>> observers = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        var observer = received.poll(5, TimeUnit.SECONDS);
        assertThat(observer).isNotNull();
        observers.add(observer);
      }
      assertThat(received.poll(100, TimeUnit.MILLISECONDS)).isNull();
      observers.get(2).onNext(successfulSymlinks(4096));
      observers.get(2).onCompleted();
      var fifth = received.poll(5, TimeUnit.SECONDS);
      assertThat(fifth).isNotNull();
      fifth.onNext(successfulSymlinks(1));
      fifth.onCompleted();
      for (int i : new int[] {0, 1, 3}) {
        observers.get(i).onNext(successfulSymlinks(4096));
        observers.get(i).onCompleted();
      }
      result.get(5, TimeUnit.SECONDS);
      assertThat(symlinkRequests).hasSize(5);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void unsupportedSymlinkWindowDrainsBeforeLocalFallback() throws Exception {
    List<StreamObserver<BatchSymlinkResponse>> observers = new ArrayList<>();
    symlinkHandler =
        (request, observer) -> {
          observers.add(observer);
          if (observers.size() == 4) {
            for (var pending : observers) {
              pending.onError(Status.UNIMPLEMENTED.asRuntimeException());
              assertThat(root.getChild("link0").isSymbolicLink()).isFalse();
            }
          }
        };
    client.createSymlinks(root, symlinks(4 * 4096 + 1));
    assertThat(symlinkRequests).hasSize(4);
    assertThat(root.getDirectoryEntries()).hasSize(4 * 4096 + 1);
    assertThat(root.getChild("link0").readSymbolicLink()).isEqualTo(PathFragment.create("target"));
    assertThat(root.getChild("link16384").readSymbolicLink())
        .isEqualTo(PathFragment.create("target"));
  }

  @Test(timeout = 20000)
  public void failedSymlinkBatchDrainsOutstandingRequestsWithoutFallback() throws Exception {
    assertFailedSymlinkBatchDrains(8193, 3);
    assertFailedSymlinkBatchDrains(4 * 4096 + 1, 4);
  }

  private void assertFailedSymlinkBatchDrains(int links, int batches) throws Exception {
    symlinkRequests.clear();
    CountDownLatch received = new CountDownLatch(batches);
    List<StreamObserver<BatchSymlinkResponse>> observers = new ArrayList<>();
    symlinkHandler =
        (request, observer) -> {
          observers.add(observer);
          received.countDown();
        };
    var executor = Executors.newSingleThreadExecutor();
    try {
      var result =
          executor.submit(
              () -> {
                client.createSymlinks(root, symlinks(links));
                return null;
              });
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      observers.getFirst().onError(Status.UNAVAILABLE.asRuntimeException());
      assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
      observers.get(1).onError(Status.UNIMPLEMENTED.asRuntimeException());
      for (int i = 2; i < batches; i++) {
        observers.get(i).onNext(successfulSymlinks(symlinkRequests.get(i).getSymlinksCount()));
        observers.get(i).onCompleted();
      }
      var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
      assertThat(error).hasCauseThat().isInstanceOf(IOException.class);
      assertThat(root.getDirectoryEntries()).isEmpty();
      assertThat(symlinkRequests).hasSize(batches);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test(timeout = 20000)
  public void interruptionCancelsEveryPendingSymlinkRequest() throws Exception {
    assertInterruptionCancelsSymlinks(8193, 3);
    assertInterruptionCancelsSymlinks(4 * 4096 + 1, 4);
  }

  private void assertInterruptionCancelsSymlinks(int links, int batches) throws Exception {
    symlinkRequests.clear();
    CountDownLatch received = new CountDownLatch(batches);
    List<StreamObserver<BatchSymlinkResponse>> observers = new ArrayList<>();
    symlinkHandler =
        (request, observer) -> {
          observers.add(observer);
          received.countDown();
        };
    var result =
        new FutureTask<Boolean>(
            () -> {
              assertThrows(IOException.class, () -> client.createSymlinks(root, symlinks(links)));
              return Thread.currentThread().isInterrupted();
            });
    var caller = new Thread(result);
    caller.start();
    try {
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      caller.interrupt();
      assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
      for (var observer : observers) {
        assertThat(((ServerCallStreamObserver<?>) observer).isCancelled()).isTrue();
      }
      assertThat(root.getDirectoryEntries()).isEmpty();
    } finally {
      caller.interrupt();
      caller.join(5000);
    }
  }

  private static Map<PathFragment, PathFragment> symlinks(int count) {
    Map<PathFragment, PathFragment> links = new LinkedHashMap<>();
    for (int i = 0; i < count; i++) {
      links.put(PathFragment.create("link" + i), PathFragment.create("target"));
    }
    return links;
  }

  private static BatchSymlinkResponse successfulSymlinks(int count) {
    var response = BatchSymlinkResponse.newBuilder();
    for (int i = 0; i < count; i++) {
      response.addErrors(0);
    }
    return response.build();
  }
}
