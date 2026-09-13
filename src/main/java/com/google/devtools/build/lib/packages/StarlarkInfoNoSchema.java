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
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.Compactable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.TokenKind;

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

  // TODO(adonovan): restrict type of provider to StarlarkProvider?
  // Do we ever need StarlarkInfos of BuiltinProviders? Such BuiltinProviders could
  // be  moved to Starlark using bzl builtins injection.
  // Alternatively: what about this implementation is specific to StarlarkProvider?
  // It's really just a "generic" or "dynamic" representation of a struct,
  // analogous to reflection versus generated message classes in the protobuf world.
  // The efficient table algorithms would be a nice addition to the Starlark
  // interpreter, to allow other clients to define their own fast structs
  // (or to define a standard one). See also comments at Info about upcoming clean-ups.
  private StarlarkInfoNoSchema(
      Provider provider, ImmutableList<String> fieldNames, Object[] table, @Nullable Location loc) {
    super(loc);
    this.provider = provider;
    this.fieldNames = FieldNames.intern(new FieldNames(fieldNames));
    this.table = table;
  }

  StarlarkInfoNoSchema(Provider provider, Map<String, Object> values, @Nullable Location loc) {
    super(loc);
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

  /**
   * Creates a schemaless provider instance with the given provider type and field values.
   *
   * @param provider A {@code Provider} without a schema. {@code StarlarkProvider} with a schema is
   *     not supported by this call.
   * @param values the field values
   * @param loc the creation location for this instance. Built-in provider instances may use {@link
   *     Location#BUILTIN}, which is the default if null.
   */
  static StarlarkInfo createSchemaless(
      Provider provider, Map<String, Object> values, @Nullable Location loc) {
    Preconditions.checkArgument(
        !(provider instanceof StarlarkProvider)
            || ((StarlarkProvider) provider).getFields() == null);
    return new StarlarkInfoNoSchema(provider, values, loc);
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
    public StarlarkInfo createFromArgs(StarlarkThread thread) throws EvalException {
      return new StarlarkInfoNoSchema(provider, namedArgMap, thread.getCallerLocation());
    }

    @Override
    public StarlarkInfo createFromMap(Map<String, Object> map, StarlarkThread thread)
        throws EvalException {
      return new StarlarkInfoNoSchema(provider, map, thread.getCallerLocation());
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

  @Nullable
  @Override
  public Object getValue(String name) {
    int i = Collections.binarySearch(fieldNames.names, name);
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

    return new StarlarkInfoNoSchema(x.provider, fields.build(), ztable, Location.BUILTIN);
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
