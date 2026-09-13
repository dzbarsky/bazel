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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Compactable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.StarlarkType;
import net.starlark.java.syntax.TokenKind;
import net.starlark.java.syntax.Types;

/**
 * A struct-like Info (provider instance) for providers defined in Starlark that don't have a
 * schema.
 */
public class StarlarkInfoNoSchema extends StarlarkInfo {
  // Keep names shared when structs are deserialized as well as when they are created.
  @AutoCodec
  static final class FieldNames {
    private static final Interner<FieldNames> interner = BlazeInterners.newWeakInterner();
    private final ImmutableList<String> names;

    private FieldNames(ImmutableList<String> names) {
      this.names = names;
    }

    @AutoCodec.Interner
    static FieldNames intern(FieldNames fields) {
      return interner.intern(fields);
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof FieldNames other && names.equals(other.names);
    }

    @Override
    public int hashCode() {
      return names.hashCode();
    }
  }

  private final Provider provider;
  private final FieldNames fieldNames;
  // Values in the same order as the sorted field names.
  private final Object[] table;

  private StarlarkInfoNoSchema(
      Provider provider, ImmutableList<String> fieldNames, Object[] table) {
    this.provider = provider;
    this.fieldNames = FieldNames.intern(new FieldNames(fieldNames));
    this.table = table;
  }

  StarlarkInfoNoSchema(Provider provider, Map<String, Object> values) {
    this.provider = provider;
    ImmutableList<String> keys = ImmutableList.sortedCopyOf(values.keySet());
    this.fieldNames = FieldNames.intern(new FieldNames(keys));
    this.table = new Object[keys.size()];
    for (int i = 0; i < table.length; i++) {
      table[i] = Starlark.checkValid(values.get(keys.get(i)));
    }
  }

  @Override
  public Provider getProvider() {
    return provider;
  }

  private StarlarkType getStructType(StarlarkSemantics semantics) {
    int n = table.length;
    ImmutableMap.Builder<String, StarlarkType> fieldTypes = ImmutableMap.builderWithExpectedSize(n);
    for (int i = 0; i < n; i++) {
      String name = fieldNames.names.get(i);
      StarlarkType type = Starlark.getStarlarkTypePropagatingStackOverflow(table[i], semantics);
      fieldTypes.put(name, type);
    }
    return Types.struct(fieldTypes.buildOrThrow());
  }

  @Override
  public StarlarkType getStarlarkType(StarlarkSemantics semantics) {
    if (provider instanceof StarlarkType type) {
      // This is the case for StarlarkProvider. Do nominal typing.
      // TODO: #27370 - Should we emit the fields and types for schemaless starlark providers?
      return type;
    } else {
      // Untyped struct; provider is either StructProvider (`struct` in BUILD API) or one of a few
      // non-StructProvider builtin providers that deliberately construct untyped struct values
      // (e.g. Actions or ApplePlatform). Do structural typing.
      // TODO: #27370 - Should we synthesize a nominal StarlarkType for the value type of such
      // non-StructProvider builtin providers?
      return getStructType(semantics);
    }
  }

  /**
   * Creates a schemaless provider instance with the given provider type and field values.
   *
   * @param provider A {@code Provider} without a schema. {@code StarlarkProvider} with a schema is
   *     not supported by this call.
   * @param values the field values
   */
  static StarlarkInfo createSchemaless(Provider provider, Map<String, Object> values) {
    Preconditions.checkArgument(
        !(provider instanceof StarlarkProvider)
            || ((StarlarkProvider) provider).getFields() == null);
    return new StarlarkInfoNoSchema(provider, values);
  }

  static StarlarkProvider.StarlarkInfoFactory newStarlarkInfoFactory(
      StarlarkProvider provider, StarlarkThread thread) {
    return new StarlarkInfoFactory(provider, thread);
  }

  /**
   * Constructs a StarlarkInfo with calls forwarded from one of the StarlarkInfo ArgumentProcessor
   * implementations. Checks that each key is provided at most once. This class exists solely for
   * the StarlarkInfo ArgumentProcessors.
   */
  static class StarlarkInfoFactory extends StarlarkProvider.StarlarkInfoFactory {
    private final Map<String, Object> namedArgMap;

    StarlarkInfoFactory(StarlarkProvider provider, StarlarkThread thread) {
      super(provider, thread);
      this.namedArgMap = new HashMap<>();
    }

    @Override
    public void addNamedArg(String name, Object value) throws EvalException {
      // TODO(b/380824219): Evaluate whether we can know the number of named args here, and then
      // place the args into the table directly.
      Object oldValue = namedArgMap.put(name, value);
      if (oldValue != null) {
        throw Starlark.errorf(
            "got multiple values for parameter %s in call to instantiate provider %s",
            name, provider.getPrintableName());
      }
    }

    @Override
    public StarlarkInfo createFromArgs() {
      return new StarlarkInfoNoSchema(provider, namedArgMap);
    }

    @Override
    public StarlarkInfo createFromMap(Map<String, Object> map) {
      return new StarlarkInfoNoSchema(provider, map);
    }
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return fieldNames.names;
  }

  @Override
  public boolean isImmutable() {
    // If the provider is not yet exported, the hash code of the object is subject to change.
    if (!provider.isExported()) {
      return false;
    }
    for (int i = 0; i < table.length; i++) {
      if (!Starlark.isImmutable(table[i])) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void checkHashable() throws EvalException {
    super.checkHashable(); // Verifies that the values are immutable.
    // Bazel has historically allowed structs of immutable values to be considered hashable even if
    // those values are not Starlark-hashable by themselves (e.g. frozen lists). This is
    // inconsistent and arguably wrong, but fixing it would be a breaking change.
    // Thus, instead of checking whether the values are Starlark-hashable, below we only check
    // whether they have a usable hashCode() implementation.
    for (int i = 0; i < table.length; i++) {
      Object val = table[i];
      if (!Starlark.isAcyclic(val)) {
        // A self-referential value's hashCode() can cause a stack overflow. Trigger it early; the
        // StackOverflowError will be caught by Starlark.checkHashable() and rethrown as an
        // EvalException.
        var unused = val.hashCode();
      }
    }
  }

  @Nullable
  @Override
  public Object getValue(String name) {
    int i;
    if (table.length <= BINARY_SEARCH_THRESHOLD) {
      i = fieldNames.names.indexOf(name);
    } else {
      i = Collections.binarySearch(fieldNames.names, name);
    }
    return i < 0 ? null : table[i];
  }

  @Nullable
  @Override
  public StarlarkInfo binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
    if (op == TokenKind.PLUS && that instanceof StarlarkInfo) {
      final Provider thatProvider = ((StarlarkInfo) that).getProvider();
      if (!provider.equals(thatProvider)) {
        throw Starlark.errorf(
            "Cannot use '+' operator on instances of different providers (%s and %s)",
            provider.getPrintableName(), thatProvider.getPrintableName());
      }
      Preconditions.checkArgument(that instanceof StarlarkInfoNoSchema);
      return thisLeft
          ? plus(this, (StarlarkInfoNoSchema) that) //
          : plus((StarlarkInfoNoSchema) that, this);
    }
    return null;
  }

  private static StarlarkInfo plus(StarlarkInfoNoSchema x, StarlarkInfoNoSchema y)
      throws EvalException {
    int xsize = x.table.length;
    int ysize = y.table.length;
    int zsize = xsize + ysize;
    ImmutableList.Builder<String> fields = ImmutableList.builderWithExpectedSize(zsize);
    Object[] ztable = new Object[zsize];
    int xi = 0;
    int yi = 0;
    int zi = 0;
    while (xi < xsize && yi < ysize) {
      String xk = x.fieldNames.names.get(xi);
      String yk = y.fieldNames.names.get(yi);
      int cmp = xk.compareTo(yk);
      if (cmp < 0) {
        fields.add(xk);
        ztable[zi] = x.table[xi];
        xi++;
      } else if (cmp > 0) {
        fields.add(yk);
        ztable[zi] = y.table[yi];
        yi++;
      } else {
        throw Starlark.errorf("cannot add struct instances with common field '%s'", xk);
      }
      zi++;
    }
    while (xi < xsize) {
      fields.add(x.fieldNames.names.get(xi));
      ztable[zi] = x.table[xi];
      xi++;
      zi++;
    }
    while (yi < ysize) {
      fields.add(y.fieldNames.names.get(yi));
      ztable[zi] = y.table[yi];
      yi++;
      zi++;
    }

    return new StarlarkInfoNoSchema(x.provider, fields.build(), ztable);
  }

  @Override
  public final boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StarlarkInfoNoSchema other)) {
      return false;
    }
    return provider.equals(other.provider)
        && fieldNames.equals(other.fieldNames)
        && Arrays.equals(table, other.table);
  }

  @Override
  public final int hashCode() {
    return 31 * (31 * provider.hashCode() + fieldNames.hashCode()) + Arrays.hashCode(table);
  }

  @Override
  public StarlarkInfoNoSchema unsafeOptimizeMemoryLayout() {
    for (int i = 0; i < table.length; i++) {
      if (table[i] instanceof Compactable compactable) {
        table[i] = compactable.unsafeOptimizeMemoryLayout();
      }
    }
    return this;
  }
}
