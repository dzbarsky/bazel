// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.query2.query.output;

import com.google.common.base.Preconditions;
import com.google.common.collect.MapMaker;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ComputedDefault;
import com.google.devtools.build.lib.packages.AttributeFormatter;
import com.google.devtools.build.lib.packages.AttributeProvider;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuildType.SelectorList;
import com.google.devtools.build.lib.packages.LabelPrinter;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * Contains the logic for condensing the various properties of rules that contribute to their
 * "affectedness" into a simple hash value. The resulting hash may be compared across queries to
 * tell if a rule has changed in a potentially meaningful way.
 */
public final class SyntheticAttributeHashCalculator {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final ThreadLocal<HashOutput> hashOutputByThread =
      ThreadLocal.withInitial(HashOutput::new);

  private SyntheticAttributeHashCalculator() {}

  @FunctionalInterface
  private interface SerializedAttributeLookup {
    @Nullable
    Build.Attribute get(Attribute attribute, int attrIndex);
  }

  /**
   * Returns a hash of various properties of a rule which might contribute to the rule's
   * "affectedness". This includes, but is not limited to, attribute values and error-state.
   *
   * @param rule The rule instance to calculate the hash for.
   * @param serializedAttributes Any available attribute which have already been serialized. This is
   *     an optimization to avoid re-serializing attributes internally.
   * @param extraDataForAttrHash Extra data to add to the hash.
   */
  public static String compute(
      Rule rule,
      Map<Attribute, Build.Attribute> serializedAttributes,
      Object extraDataForAttrHash,
      HashFunction hashFunction,
      boolean includeAttributeSourceAspects,
      boolean includeStarlarkRuleEnv) {
    return compute(
        rule,
        serializedAttributes,
        extraDataForAttrHash,
        hashFunction,
        includeAttributeSourceAspects,
        includeStarlarkRuleEnv,
        /* canonicalizeLabelSelects= */ false);
  }

  /** Computes the native target hash with optional ordering of label-valued select conditions. */
  public static String compute(
      Rule rule,
      Map<Attribute, Build.Attribute> serializedAttributes,
      Object extraDataForAttrHash,
      HashFunction hashFunction,
      boolean includeAttributeSourceAspects,
      boolean includeStarlarkRuleEnv,
      boolean canonicalizeLabelSelects) {
    return compute(
        rule,
        (attribute, attrIndex) -> serializedAttributes.get(attribute),
        extraDataForAttrHash,
        hashFunction,
        includeAttributeSourceAspects,
        includeStarlarkRuleEnv,
        canonicalizeLabelSelects,
        /* defaults= */ null,
        /* normalizeRawValue= */ null);
  }

  static String computeWithIndexedAttributes(
      Rule rule,
      @Nullable Build.Attribute[] serializedAttributes,
      Object extraDataForAttrHash,
      HashFunction hashFunction,
      boolean includeAttributeSourceAspects,
      boolean includeStarlarkRuleEnv,
      DefaultAttributeProtos defaults) {
    return compute(
        rule,
        (attribute, attrIndex) ->
            serializedAttributes == null ? null : serializedAttributes[attrIndex],
        extraDataForAttrHash,
        hashFunction,
        includeAttributeSourceAspects,
        includeStarlarkRuleEnv,
        /* canonicalizeLabelSelects= */ false,
        defaults,
        /* normalizeRawValue= */ null);
  }

  /** Hashes native query targets with defaults reused for the lifetime of their rule classes. */
  public static final class NativeAttributeHasher {
    private final UnaryOperator<Object> normalizeRawValue;
    private final ConcurrentMap<AttributeProvider, DefaultAttributeProtos> defaultsByProvider =
        new MapMaker().weakKeys().makeMap();

    public NativeAttributeHasher(UnaryOperator<Object> normalizeRawValue) {
      this.normalizeRawValue = normalizeRawValue;
    }

    public String compute(Rule rule, HashFunction hashFunction) {
      AttributeProvider provider = rule.getAttributeProvider();
      DefaultAttributeProtos defaults = defaultsByProvider.get(provider);
      if (defaults == null) {
        defaults =
            defaultsByProvider.computeIfAbsent(
                provider,
                unused ->
                    new DefaultAttributeProtos(
                        provider.getAttributeCount(),
                        LabelPrinter.legacy(),
                        /* includeAttributeSourceAspects= */ false));
      }
      return SyntheticAttributeHashCalculator.compute(
          rule,
          (attribute, attrIndex) -> null,
          /* extraDataForAttrHash= */ "",
          hashFunction,
          /* includeAttributeSourceAspects= */ false,
          /* includeStarlarkRuleEnv= */ true,
          /* canonicalizeLabelSelects= */ true,
          defaults,
          normalizeRawValue);
    }
  }

  private static String compute(
      Rule rule,
      SerializedAttributeLookup serializedAttributes,
      Object extraDataForAttrHash,
      HashFunction hashFunction,
      boolean includeAttributeSourceAspects,
      boolean includeStarlarkRuleEnv,
      boolean canonicalizeLabelSelects,
      @Nullable DefaultAttributeProtos defaults,
      @Nullable UnaryOperator<Object> normalizeRawValue) {
    HashOutput cachedOutput = hashOutputByThread.get();
    // A nested hash on the same thread needs its own encoder.
    HashOutput output = cachedOutput.inUse ? new HashOutput() : cachedOutput;
    boolean completed = false;
    try {
      output.begin(hashFunction);
      writeRule(
          rule,
          serializedAttributes,
          extraDataForAttrHash,
          includeAttributeSourceAspects,
          includeStarlarkRuleEnv,
          canonicalizeLabelSelects,
          defaults,
          normalizeRawValue,
          output.codedOut);
      String hash = output.finish();
      completed = true;
      return hash;
    } finally {
      if (!completed && output == cachedOutput) {
        // The encoder may still contain bytes from the failed hash.
        hashOutputByThread.remove();
      }
      output.clear();
    }
  }

  private static void writeRule(
      Rule rule,
      SerializedAttributeLookup serializedAttributes,
      Object extraDataForAttrHash,
      boolean includeAttributeSourceAspects,
      boolean includeStarlarkRuleEnv,
      boolean canonicalizeLabelSelects,
      @Nullable DefaultAttributeProtos defaults,
      @Nullable UnaryOperator<Object> normalizeRawValue,
      CodedOutputStream codedOut) {
    RuleClass ruleClass = rule.getRuleClassObject();
    if (ruleClass.isStarlark() && includeStarlarkRuleEnv) {
      try {
        codedOut.writeByteArrayNoTag(
            Preconditions.checkNotNull(ruleClass.getRuleDefinitionEnvironmentDigest(), rule));
      } catch (IOException e) {
        throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
      }
    }

    RawAttributeMapper rawAttributeMapper = RawAttributeMapper.of(rule);
    var attributes = rule.getAttributeProvider().getAttributes();
    for (int attrIndex = 0; attrIndex < attributes.size(); attrIndex++) {
      Attribute attr = attributes.get(attrIndex);
      String attrName = attr.getName();

      if (attrName.equals("generator_location")) {
        // generator_location can be ignored for the purpose of telling if a rule has changed.
        continue;
      }

      Object valueToHash = rawAttributeMapper.getRawAttributeValue(rule, attr);
      if (normalizeRawValue != null) {
        valueToHash = normalizeRawValue.apply(valueToHash);
      }

      if (valueToHash instanceof ComputedDefault computedDefault) {
        // ConfiguredDefaults need special handling to detect changes in evaluated values.
        if (!computedDefault.dependencies().isEmpty()) {
          // TODO(b/29038463): We're skipping computed defaults that depend on other configurable
          // attributes because there currently isn't a way to evaluate such a computed default;
          // there isn't *one* value it evaluates to.
          continue;
        }

        try {
          valueToHash = computedDefault.getDefault(rawAttributeMapper);
        } catch (IllegalArgumentException e) {
          // TODO(mschaller): Catching IllegalArgumentException isn't ideal. It's thrown by
          // AbstractAttributeMapper#get if the attribute's type doesn't match its value, which
          // would happen if a ComputedDefault function accessed an attribute whose value was
          // configurable. We check whether the ComputedDefault declared any configurable
          // attribute dependencies above, but someone could make a mistake and fail to declare
          // something. There's no mechanism that enforces correct declaration right now.
          // This allows us to recover from such an error by skipping an attribute, as opposed to
          // crashing.
          logger.atWarning().log(
              "Recovering from failed evaluation of ComputedDefault attribute value: %s", e);
          continue;
        }
      }

      Build.Attribute attrPb =
          valueToHash instanceof SelectorList<?> ? null : serializedAttributes.get(attr, attrIndex);
      if (attrPb == null) {
        if (defaults != null
            && !(valueToHash instanceof SelectorList<?>)
            && valueToHash == attr.getDefaultValueUnchecked()
            && !(canonicalizeLabelSelects
                && attrName.equals(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE))) {
          try {
            codedOut.writeRawBytes(defaults.getHashBytes(attrIndex, attr, valueToHash));
          } catch (IOException e) {
            throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
          }
          continue;
        }
        // We didn't already serialize the attribute or it's a SelectorList. Latter may
        // have been flattened while we want the full representation, so we start from scratch.
        attrPb =
            AttributeFormatter.getAttributeProto(
                attr,
                valueToHash,
                /* explicitlySpecified= */ false, // We care about value, not how it was set.
                /* encodeBooleanAndTriStateAsIntegerAndString= */ false,
                /* sourceAspect= */ null,
                includeAttributeSourceAspects,
                LabelPrinter.legacy());
      }

      if (canonicalizeLabelSelects && attrName.equals(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
        // Bazel synthesizes this private membership list from condition keys in traversal order.
        var labels = new ArrayList<>(attrPb.getStringListValueList());
        labels.sort(Comparator.naturalOrder());
        attrPb = attrPb.toBuilder().clearStringListValue().addAllStringListValue(labels).build();
      } else if (canonicalizeLabelSelects
          && valueToHash instanceof SelectorList<?>
          && (attr.getType() == BuildType.LABEL || attr.getType() == BuildType.LABEL_LIST)) {
        // Bazel resolves these arms by condition and specialization. Keep the order of
        // concatenated selects and each arm's value, and leave user-dictionary order intact.
        var builder = attrPb.toBuilder();
        for (var selector : builder.getSelectorListBuilder().getElementsBuilderList()) {
          var entries = new ArrayList<>(selector.getEntriesList());
          entries.sort(Comparator.comparing(Build.Attribute.SelectorEntry::getLabel));
          selector.clearEntries().addAllEntries(entries);
        }
        attrPb = builder.build();
      }
      try {
        attrPb.writeTo(codedOut);
      } catch (IOException e) {
        throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
      }
    }

    try {
      // Rules can be considered changed when the containing package goes in/out of error.
      codedOut.writeBoolNoTag(rule.getPackageoid().containsErrors());
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
    }

    try {
      // Include a summary of any package-wide data that applies to this target (e.g. custom make
      // variables aka `vardef`).
      codedOut.writeStringNoTag((String) extraDataForAttrHash);

      Label transitiveVisibility = rule.getTransitiveVisibility();
      codedOut.writeBoolNoTag(transitiveVisibility != null);
      if (transitiveVisibility != null) {
        codedOut.writeStringNoTag(transitiveVisibility.getCanonicalForm());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Unexpected IO failure writing to digest stream", e);
    }
  }

  private static final class HashOutput extends OutputStream {
    private final CodedOutputStream codedOut = CodedOutputStream.newInstance(this);
    private Hasher hasher;
    private MessageDigest sha256;
    private byte[] sha256Result;
    private boolean inUse;

    void begin(HashFunction hashFunction) {
      inUse = true;
      if (hashFunction != DigestHashFunction.SHA256.getHashFunction()) {
        hasher = hashFunction.newHasher();
      } else if (sha256 == null) {
        sha256 = DigestHashFunction.SHA256.newMessageDigest();
        sha256Result = new byte[hashFunction.bits() / Byte.SIZE];
      }
    }

    String finish() {
      try {
        codedOut.flush();
      } catch (IOException e) {
        throw new IllegalStateException("Unexpected flush failure", e);
      }
      if (hasher != null) {
        return BaseEncoding.base64().encode(hasher.hash().asBytes());
      }
      try {
        Preconditions.checkState(
            sha256.digest(sha256Result, 0, sha256Result.length) == sha256Result.length,
            "Unexpected SHA-256 digest length");
      } catch (DigestException e) {
        throw new IllegalStateException("Could not write the SHA-256 digest", e);
      }
      return BaseEncoding.base64().encode(sha256Result);
    }

    void clear() {
      hasher = null;
      inUse = false;
    }

    @Override
    public void write(int value) {
      if (hasher != null) {
        hasher.putByte((byte) value);
      } else {
        sha256.update((byte) value);
      }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      if (hasher != null) {
        hasher.putBytes(bytes, offset, length);
      } else {
        sha256.update(bytes, offset, length);
      }
    }
  }
}
