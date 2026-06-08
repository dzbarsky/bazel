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

package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.SymbolGenerator;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.TokenKind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test class for {@link StarlarkInfo} and its subclasses. */
@RunWith(JUnit4.class)
public class StarlarkInfoTest {

  @Test
  public void nullLocationDefaultsToBuiltin() throws Exception {
    StarlarkInfo info = StarlarkInfo.create(makeProvider(), ImmutableMap.of(), null);
    assertThat(info.getCreationLocation()).isEqualTo(Location.BUILTIN);
  }

  @Test
  public void instancesOfUnexportedProvidersAreMutable() throws Exception {
    StarlarkProvider provider = makeProvider();
    StarlarkInfo info = makeInfoWithF1F2Values(provider, StarlarkInt.of(5), null);
    assertThat(info.isImmutable()).isFalse();
  }

  @Test
  public void instancesOfExportedProvidersMayBeImmutable() throws Exception {
    StarlarkProvider provider = makeExportedProvider();
    StarlarkInfo info = makeInfoWithF1F2Values(provider, StarlarkInt.of(5), null);
    assertThat(info.isImmutable()).isTrue();
  }

  @Test
  public void mutableIfContentsAreMutable() throws Exception {
    StarlarkProvider provider = makeExportedProvider();
    StarlarkValue v = new StarlarkValue() {};
    StarlarkInfo info = makeInfoWithF1F2Values(provider, StarlarkInt.of(5), v);
    assertThat(info.isImmutable()).isFalse();
  }

  @Test
  public void equivalence() throws Exception {
    StarlarkProvider provider1 = makeProvider();
    StarlarkProvider provider2 = makeProvider();
    // equal providers and fields
    assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
        .isEqualTo(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)));
    // different providers => unequal
    assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
        .isNotEqualTo(makeInfoWithF1F2Values(provider2, StarlarkInt.of(4), StarlarkInt.of(5)));
    // different fields => unequal
    assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
        .isNotEqualTo(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(6)));
    // different sets of fields => unequal
    assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5)))
        .isNotEqualTo(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), null));
    // different field names with the same values => unequal
    assertThat(makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), null))
        .isNotEqualTo(makeInfoWithF1F2Values(provider1, null, StarlarkInt.of(4)));
  }

  @Test
  public void concatWithDifferentProvidersFails() throws Exception {
    StarlarkProvider provider1 = makeProvider();
    StarlarkProvider provider2 = makeProvider();
    StarlarkInfo info1 = makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5));
    StarlarkInfo info2 = makeInfoWithF1F2Values(provider2, StarlarkInt.of(4), StarlarkInt.of(5));
    EvalException expected =
        assertThrows(EvalException.class, () -> info1.binaryOp(TokenKind.PLUS, info2, true));
    assertThat(expected).hasMessageThat()
        .contains("Cannot use '+' operator on instances of different providers");
  }

  @Test
  public void concatWithOverlappingFieldsFails() throws Exception {
    StarlarkProvider provider1 = makeProvider();
    StarlarkInfo info1 = makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), StarlarkInt.of(5));
    StarlarkInfo info2 = makeInfoWithF1F2Values(provider1, StarlarkInt.of(4), null);
    EvalException expected =
        assertThrows(EvalException.class, () -> info1.binaryOp(TokenKind.PLUS, info2, true));
    assertThat(expected)
        .hasMessageThat()
        .contains("cannot add struct instances with common field 'f1'");
  }

  @Test
  public void concatWithSameFields() throws Exception {
    StarlarkProvider provider = makeProvider();
    StarlarkInfo info1 = makeInfoWithF1F2Values(provider, StarlarkInt.of(4), null);
    StarlarkInfo info2 = makeInfoWithF1F2Values(provider, null, StarlarkInt.of(5));
    StarlarkInfo result = (StarlarkInfo) info1.binaryOp(TokenKind.PLUS, info2, true);
    assertThat(result.getFieldNames()).containsExactly("f1", "f2");
    assertThat(result.getValue("f1")).isEqualTo(StarlarkInt.of(4));
    assertThat(result.getValue("f2")).isEqualTo(StarlarkInt.of(5));
  }

  @Test
  public void concatWithDifferentFields() throws Exception {
    StarlarkProvider provider = makeProvider();
    StarlarkInfo info1 =
        StarlarkInfo.create(
            provider, ImmutableMap.of("e", StarlarkInt.of(5), "a", StarlarkInt.of(1)), null);
    StarlarkInfo info2 =
        StarlarkInfo.create(
            provider,
            ImmutableMap.of("f", StarlarkInt.of(6), "b", StarlarkInt.of(2), "d", StarlarkInt.of(4)),
            null);
    StarlarkInfo result = (StarlarkInfo) info1.binaryOp(TokenKind.PLUS, info2, false);
    assertThat(result.getFieldNames()).containsExactly("a", "b", "d", "e", "f").inOrder();
    assertThat(result.getValue("a")).isEqualTo(StarlarkInt.of(1));
    assertThat(result.getValue("b")).isEqualTo(StarlarkInt.of(2));
    assertThat(result.getValue("d")).isEqualTo(StarlarkInt.of(4));
    assertThat(result.getValue("e")).isEqualTo(StarlarkInt.of(5));
    assertThat(result.getValue("f")).isEqualTo(StarlarkInt.of(6));
  }

  /** Creates an unexported schemaless provider type with builtin location. */
  private static StarlarkProvider makeProvider() {
    return StarlarkProvider.builder(Location.BUILTIN)
        .buildWithIdentityToken(SymbolGenerator.createTransient().generate());
  }

  /** Creates an exported schemaless provider type with builtin location. */
  private static StarlarkProvider makeExportedProvider() {
    StarlarkProvider.Key key =
        new StarlarkProvider.Key(
            keyForBuild(Label.parseCanonicalUnchecked("//package:target")), "provider");
    return StarlarkProvider.builder(Location.BUILTIN).buildExported(key);
  }

  /**
   * Creates an instance of a provider with the given values for fields f1 and f2. Either field
   * value may be null, in which case it is omitted.
   */
  private static StarlarkInfo makeInfoWithF1F2Values(
      StarlarkProvider provider, @Nullable Object v1, @Nullable Object v2) {
    ImmutableMap.Builder<String, Object> values = ImmutableMap.builder();
    if (v1 != null) {
      values.put("f1", v1);
    }
    if (v2 != null) {
      values.put("f2", v2);
    }
    return StarlarkInfo.create(provider, values.build(), Location.BUILTIN);
  }

  @Test
  public void unorderedFieldsRetainValues() {
    StarlarkInfo info =
        StarlarkInfo.create(
            makeProvider(),
            ImmutableMap.of("z", StarlarkInt.of(1), "a", StarlarkInt.of(3), "m", StarlarkInt.of(2)),
            Location.BUILTIN);
    assertThat(info.getFieldNames()).containsExactly("a", "m", "z").inOrder();
    assertThat(info.getValue("z")).isEqualTo(StarlarkInt.of(1));
    assertThat(info.getValue("a")).isEqualTo(StarlarkInt.of(3));
    assertThat(info.getValue("m")).isEqualTo(StarlarkInt.of(2));
    assertThat(info.getValue("missing")).isNull();
  }
}
