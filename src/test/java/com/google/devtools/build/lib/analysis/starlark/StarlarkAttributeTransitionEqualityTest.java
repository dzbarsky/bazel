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

package com.google.devtools.build.lib.analysis.starlark;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.testing.GcFinalization;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.StarlarkDefinedConfigTransition;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment;
import com.google.devtools.build.lib.analysis.util.DummyTestFragment.DummyTestOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.packages.ConfiguredAttributeMapper;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetAndData;
import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import net.starlark.java.eval.NoneType;
import net.starlark.java.eval.Starlark;
import net.starlark.java.syntax.Location;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for memoized equality of immutable attribute transitions. */
@RunWith(JUnit4.class)
public final class StarlarkAttributeTransitionEqualityTest extends BuildViewTestCase {

  @Override
  protected ConfiguredRuleClassProvider createRuleClassProvider() {
    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder.addConfigurationFragment(DummyTestFragment.class);
    return builder.build();
  }

  private void writeTransitionFixture() throws Exception {
    scratch.file(
        "transition_cache/rules.bzl",
        """
        def _transition(settings, attr):
            return {"//command_line_option:foo":
                getattr(attr, "value", "empty") + ":" + settings["//command_line_option:foo"]}

        first = transition(implementation = _transition,
            inputs = ["//command_line_option:foo"], outputs = ["//command_line_option:foo"])

        def _impl(ctx):
            return []

        empty_rule = rule(implementation = _impl)
        cache_rule = rule(implementation = _impl, attrs = {
            "dep": attr.label(cfg = first),
            "value": attr.string(),
            "many": attr.string_list(),
        })
        """);
    scratch.file(
        "transition_cache/BUILD",
        """
        load(":rules.bzl", "cache_rule", "empty_rule")
        empty_rule(name = "dep")
        cache_rule(name = "test", dep = ":dep", value = "ordinary",
            many = ["item_%d" % i for i in range(256)])
        """);
  }

  private static StarlarkAttributeTransitionProvider transitionProvider(
      ConfiguredAttributeMapper mapper, String attribute) {
    return (StarlarkAttributeTransitionProvider)
        mapper.getAttributeDefinition(attribute).getTransitionFactory();
  }

  private static ConfigurationTransition createTransition(
      StarlarkAttributeTransitionProvider provider, ConfiguredAttributeMapper mapper) {
    return provider.create(AttributeTransitionData.builder().attributes(mapper).build());
  }

  private String transitionedFoo(ConfigurationTransition transition, BuildOptions options)
      throws Exception {
    return Iterables.getOnlyElement(
            transition.apply(TransitionUtil.restrict(transition, options), reporter).values())
        .get(DummyTestOptions.class)
        .foo;
  }

  @Test
  public void equalityPeer_doesNotRetainOtherTransition() throws Exception {
    writeTransitionFixture();
    ConfiguredTargetAndData target = getConfiguredTargetAndData("//transition_cache:test");
    ConfiguredAttributeMapper mapper = target.getAttributeMapperForTesting();
    StarlarkAttributeTransitionProvider provider = transitionProvider(mapper, "dep");
    ConfigurationTransition retained = createTransition(provider, mapper);
    StarlarkAttributeTransitionProvider otherProvider =
        new StarlarkAttributeTransitionProvider(
            provider.getStarlarkDefinedConfigTransitionForTesting());
    ConfigurationTransition other =
        createTransition(otherProvider, target.getAttributeMapperForTesting());
    assertThat(retained).isEqualTo(other);
    WeakReference<ConfigurationTransition> reference = new WeakReference<>(other);
    other = null;
    otherProvider = null;
    GcFinalization.awaitClear(reference);
    Reference.reachabilityFence(retained);
  }

  @Test
  public void equalityPeer_isNotSerialized() throws Exception {
    writeTransitionFixture();
    useConfiguration("--foo=input");
    ConfiguredTargetAndData target = getConfiguredTargetAndData("//transition_cache:test");
    ConfiguredAttributeMapper mapper = target.getAttributeMapperForTesting();
    StarlarkAttributeTransitionProvider provider = transitionProvider(mapper, "dep");
    ConfigurationTransition transition = createTransition(provider, mapper);
    ConfigurationTransition peer =
        createTransition(provider, target.getAttributeMapperForTesting());
    assertThat(transition).isEqualTo(peer);
    new SerializationTester(transition)
        .addCodec(new DynamicCodec(transition.getClass()))
        // Match the singleton constants registered by the production Starlark registry.
        .addDependency(StructProvider.class, StructProvider.STRUCT)
        .addDependency(NoneType.class, Starlark.NONE)
        .addDependency(Location.class, Location.BUILTIN)
        .addDependency(StarlarkAttributeTransitionProvider.class, provider)
        .addDependency(
            StarlarkDefinedConfigTransition.class,
            provider.getStarlarkDefinedConfigTransitionForTesting())
        .makeMemoizing()
        .setVerificationFunction(
            (ConfigurationTransition original, ConfigurationTransition restored) -> {
              assertThat(restored).isNotSameInstanceAs(original);
              assertThat(restored).isEqualTo(original);
              assertThat(transitionedFoo(restored, target.getConfiguration().getOptions()))
                  .isEqualTo("ordinary:input");
            })
        .runTests();
    Reference.reachabilityFence(peer);
  }

  @Test
  public void equalityPeer_stillChecksTransitionDefinition() throws Exception {
    Map<String, Object> firstSettings =
        new LinkedHashMap<>(ImmutableMap.of("//command_line_option:foo", "one"));
    Map<String, Object> secondSettings = new LinkedHashMap<>(firstSettings);
    StarlarkAttributeTransitionProvider firstProvider =
        new StarlarkAttributeTransitionProvider(
            StarlarkDefinedConfigTransition.newAnalysisTestTransition(
                firstSettings,
                RepositoryMapping.EMPTY,
                Label.parseCanonical("//test:defs.bzl"),
                Location.BUILTIN,
                ImmutableList.of()));
    StarlarkAttributeTransitionProvider secondProvider =
        new StarlarkAttributeTransitionProvider(
            StarlarkDefinedConfigTransition.newAnalysisTestTransition(
                secondSettings,
                RepositoryMapping.EMPTY,
                Label.parseCanonical("//test:defs.bzl"),
                Location.BUILTIN,
                ImmutableList.of()));
    ConfigurationTransition first = firstProvider.create(AttributeTransitionData.builder().build());
    ConfigurationTransition second =
        secondProvider.create(AttributeTransitionData.builder().build());
    assertThat(first).isEqualTo(second);
    secondSettings.put("//command_line_option:foo", "two");
    assertThat(first).isNotEqualTo(second);
    assertThat(second).isNotEqualTo(first);
  }
}
