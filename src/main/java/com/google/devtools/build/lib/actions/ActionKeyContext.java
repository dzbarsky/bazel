// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetFingerprintCache;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkSemantics;

/** Per-command state for action keys and command-line expansion. */
public class ActionKeyContext {

  private final NestedSetFingerprintCache nestedSetFingerprintCache =
      new NestedSetFingerprintCache();

  @Nullable private MapEachCache mapEachCache;

  /** Called before command execution; this option is deliberately not part of BuildOptions. */
  public void setMapEachCachingEnabled(boolean enabled) {
    mapEachCache = enabled ? new MapEachCache(64L << 20) : null;
  }

  @Nullable
  public MapEachCache getMapEachCache() {
    return mapEachCache;
  }

  /**
   * Successful String/None callback results, shared only for this command.
   *
   * <p>Weights include a conservative entry charge and two bytes per string character. Caffeine's
   * eviction is eventually consistent, so this is not a hard instantaneous heap limit. Values
   * larger than 16 KiB of character payload are never admitted. The entire cache is dropped at
   * command end.
   */
  public static final class MapEachCache {
    static final int ENTRY_WEIGHT = 512;
    static final int MAX_VALUE_CHARS = 8192;

    private final Cache<Key, Object> cache;
    private final LongAdder oversizedValues = new LongAdder();

    MapEachCache(long maximumWeight) {
      cache =
          Caffeine.newBuilder()
              .maximumWeight(maximumWeight)
              .weigher(
                  (Key key, Object value) ->
                      ENTRY_WEIGHT + (value instanceof String string ? 2 * string.length() : 0))
              .executor(Runnable::run)
              .recordStats()
              .build();
    }

    /** Artifact equality can ignore its owner, which remains visible to Starlark callbacks. */
    private static final class Key {
      @Nullable private CommandLineItem artifact;
      private final StarlarkFunction function;
      private final StarlarkSemantics semantics;
      private final Object fileMappingKey;

      private Key(
          @Nullable CommandLineItem artifact,
          StarlarkFunction function,
          StarlarkSemantics semantics,
          Object fileMappingKey) {
        this.artifact = artifact;
        this.function = function;
        this.semantics = semantics;
        this.fileMappingKey = fileMappingKey;
      }

      @Override
      public boolean equals(Object other) {
        return other instanceof Key key
            && artifact == key.artifact
            && function == key.function
            && semantics == key.semantics
            && fileMappingKey.equals(key.fileMappingKey);
      }

      @Override
      public int hashCode() {
        int hash = System.identityHashCode(artifact);
        hash = 31 * hash + System.identityHashCode(function);
        hash = 31 * hash + System.identityHashCode(semantics);
        return 31 * hash + fileMappingKey.hashCode();
      }
    }

    public Lookup newLookup(
        StarlarkFunction function, StarlarkSemantics semantics, Object fileMappingKey) {
      return new Lookup(function, semantics, fileMappingKey);
    }

    /** One vector invocation owns this lookup; it must not be shared between threads. */
    public final class Lookup {
      private final Key key;

      private Lookup(
          StarlarkFunction function, StarlarkSemantics semantics, Object fileMappingKey) {
        key = new Key(null, function, semantics, fileMappingKey);
      }

      @Nullable
      public Object get(CommandLineItem artifact) throws InterruptedException {
        // A hit skips Starlark.call, including its normal interruption check.
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
        key.artifact = artifact;
        return cache.getIfPresent(key);
      }

      public void put(CommandLineItem artifact, Object value) {
        if (value instanceof String string) {
          if (string.length() > MAX_VALUE_CHARS) {
            oversizedValues.increment();
            return;
          }
        } else if (value != Starlark.NONE) {
          throw new IllegalArgumentException("Only String and None map_each results may be cached");
        }
        // Never store the mutable lookup key. These private copies are never mutated or exposed.
        cache.put(new Key(artifact, key.function, key.semantics, key.fileMappingKey), value);
      }
    }

    public long hitCount() {
      return cache.stats().hitCount();
    }

    public long missCount() {
      return cache.stats().missCount();
    }

    long estimatedSize() {
      return cache.estimatedSize();
    }

    long weightedSize() {
      cache.cleanUp();
      return cache.policy().eviction().orElseThrow().weightedSize().orElseThrow();
    }

    public String statsSummary() {
      long weight = weightedSize();
      var stats = cache.stats();
      return "map_each cache: hits="
          + stats.hitCount()
          + " misses="
          + stats.missCount()
          + " entries="
          + cache.estimatedSize()
          + " accounted_bytes="
          + weight
          + " evictions="
          + stats.evictionCount()
          + " oversized_values="
          + oversizedValues.sum();
    }
  }

  public <T> void addNestedSetToFingerprint(Fingerprint fingerprint, NestedSet<T> nestedSet)
      throws InterruptedException {
    nestedSetFingerprintCache.addNestedSetToFingerprint(fingerprint, nestedSet);
  }

  public <T> void addNestedSetToFingerprint(
      CommandLineItem.MapFn<? super T> mapFn, Fingerprint fingerprint, NestedSet<T> nestedSet)
      throws CommandLineExpansionException, InterruptedException {
    nestedSetFingerprintCache.addNestedSetToFingerprint(mapFn, fingerprint, nestedSet);
  }

  public <T> void addNestedSetToFingerprint(
      CommandLineItem.ExceptionlessMapFn<? super T> mapFn,
      Fingerprint fingerprint,
      NestedSet<T> nestedSet) {
    nestedSetFingerprintCache.addNestedSetToFingerprintExceptionless(mapFn, fingerprint, nestedSet);
  }

  public static <T> String describeNestedSetFingerprint(
      CommandLineItem.ExceptionlessMapFn<? super T> mapFn, NestedSet<T> nestedSet) {
    return NestedSetFingerprintCache.describedNestedSetFingerprint(mapFn, nestedSet);
  }

  public void clear() {
    nestedSetFingerprintCache.clear();
    // No expansion retains this cache, and its inline executor queues no maintenance work.
    mapEachCache = null;
  }
}
