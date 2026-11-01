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

import com.google.devtools.build.lib.query2.query.hash.TransitiveHashes;
import com.google.devtools.build.lib.util.Fingerprint;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/** Hashes the formatter's closed graph of interned configured nodes in place. */
final class CompactConfiguredHashes {
  private CompactConfiguredHashes() {}

  // Every listed dependency is an interned member of the same graph. A missing direct hash
  // or failed dependency walk poisons dependents, as in the keep-going formatter.
  static class Node<N extends Node<N>> {
    final int ordinal;
    final String name;
    List<N> deps = List.of();
    @Nullable byte[] hash;

    Node(int ordinal, String name) {
      this.ordinal = ordinal;
      this.name = name;
    }
  }

  static <N extends Node<N>> void combine(List<N> nodes, Set<N> failed) {
    int[] order = topologicalOrder(nodes);
    if (order == null) {
      // Some projected configured graphs may contain cycles. Preserve the original SCC
      // implementation, traversal and fingerprint order, using the untouched direct hashes.
      Map<N, TransitiveHashes.Node<N>> graph = HashMap.newHashMap(nodes.size());
      for (N node : nodes) {
        if (node.hash != null) {
          graph.put(node, new TransitiveHashes.Node<>(node.hash, node.deps));
        }
      }
      Map<N, byte[]> hashes = TransitiveHashes.combine(
          graph, Comparator.comparing(node -> node.name), node -> node.name, failed);
      for (N node : nodes) {
        node.hash = hashes.get(node);
      }
      return;
    }
    for (int ordinal : order) {
      N node = nodes.get(ordinal);
      if (node.hash == null || failed.contains(node)) {
        node.hash = null;
        continue;
      }
      // Dependencies have already replaced their direct hash with the final result.
      Fingerprint fp = new Fingerprint().addBytes(node.hash);
      boolean complete = true;
      for (N dep : node.deps) {
        if (dep.hash == null) {
          complete = false;
          break;
        }
        fp.addString(dep.name).addString("").addBytes(dep.hash);
      }
      node.hash = complete ? fp.digestAndReset() : null;
    }
  }

  /** Returns dependency-first ordinals, or null upon finding a cycle, before modifying hashes. */
  @Nullable
  private static <N extends Node<N>> int[] topologicalOrder(List<N> nodes) {
    byte[] state = new byte[nodes.size()]; // 0 = unseen, 1 = active, 2 = completed
    int[] nextDep = new int[nodes.size()];
    int[] order = new int[nodes.size()];
    int count = 0;
    ArrayDeque<N> stack = new ArrayDeque<>();
    for (N root : nodes) {
      if (state[root.ordinal] != 0) {
        continue;
      }
      stack.push(root);
      state[root.ordinal] = 1;
      while (!stack.isEmpty()) {
        N current = stack.peek();
        int index = current.ordinal;
        if (current.hash != null && nextDep[index] < current.deps.size()) {
          N dep = current.deps.get(nextDep[index]++);
          if (state[dep.ordinal] == 1) {
            return null;
          }
          if (state[dep.ordinal] == 0) {
            state[dep.ordinal] = 1;
            stack.push(dep);
          }
        } else {
          stack.pop();
          state[index] = 2;
          order[count++] = index;
        }
      }
    }
    return order;
  }
}
