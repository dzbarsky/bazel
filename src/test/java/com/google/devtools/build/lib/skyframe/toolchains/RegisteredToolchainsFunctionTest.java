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

package com.google.devtools.build.lib.skyframe.toolchains;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static com.google.devtools.build.skyframe.EvaluationResultSubjectFactory.assertThatEvaluationResult;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.EqualsTester;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.CommonOptions;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.rules.platform.ToolchainTestCase;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RegisteredToolchainsFunction} and {@link RegisteredToolchainsValue}. */
@RunWith(JUnit4.class)
public class RegisteredToolchainsFunctionTest extends ToolchainTestCase {

  private SkyKey registeredToolchainsKey(Label type, boolean debug) {
    return RegisteredToolchainsValue.key(
        targetConfigKey,
        type,
        ConfiguredTargetKey.builder()
            .setLabel(targetConfig.getFragment(PlatformConfiguration.class).getTargetPlatform())
            .setConfigurationKey(
                BuildConfigurationKey.create(
                    CommonOptions.noConfigOptions(targetConfig.getOptions())))
            .build(),
        debug);
  }

  @Test
  public void incompatibleTargetPlatformIsNotConfigured() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        config_setting(name = "optimized", values = {"compilation_mode": "opt"})
        toolchain(
            name = "mac_toolchain",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = ["//constraints:mac"],
            target_settings = [":optimized"],
            toolchain = ":impl",
        )
        """);
    useConfiguration("--platforms=//platforms:linux", "--extra_toolchains=//extra:mac_toolchain");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    assertThat(getKnownConfigurations("//extra:mac_toolchain")).isEmpty();
    assertThat(getKnownConfigurations("//extra:optimized")).isEmpty();
    assertToolchainLabels(result.get(key))
        .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"));
  }

  @Test
  public void unconditionalConstraintAliasChainsAreFiltered() throws Exception {
    scratch.file(
        "aliases/BUILD",
        """
        package(default_visibility = ["//visibility:public"])
        alias(name = "mac", actual = "//constraints:mac")
        alias(name = "mac_chain", actual = ":mac")
        alias(name = "linux", actual = "//constraints:linux")
        alias(name = "linux_chain", actual = ":linux")
        """);
    scratch.file(
        "extra/BUILD",
        """
        config_setting(name = "optimized", values = {"compilation_mode": "opt"})
        toolchain(
            name = "mac_toolchain",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = ["//aliases:mac_chain"],
            target_settings = [":optimized"],
            toolchain = ":mac_impl",
        )
        toolchain(
            name = "linux_toolchain",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = ["//aliases:linux_chain"],
            toolchain = ":linux_impl",
        )
        """);
    useConfiguration("--platforms=//platforms:linux", "--extra_toolchains=//extra:all");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    assertThat(getKnownConfigurations("//extra:mac_toolchain")).isEmpty();
    assertThat(getKnownConfigurations("//extra:optimized")).isEmpty();
    assertToolchainLabels(result.get(key))
        .contains(Label.parseCanonicalUnchecked("//extra:linux_impl"));
  }

  @Test
  public void constraintAliasCycleStillReportsError() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        alias(name = "a", actual = ":b")
        alias(name = "b", actual = ":a")
        toolchain(
            name = "toolchain",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = [":a"],
            toolchain = ":impl",
        )
        """);
    reporter.removeHandler(failFastHandler);
    useConfiguration("--platforms=//platforms:linux", "--extra_toolchains=//extra:toolchain");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThat(result.hasError()).isTrue();
  }

  @Test
  public void configurableConstraintAliasUsesOriginalConfiguration() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        config_setting(name = "optimized", values = {"compilation_mode": "opt"})
        alias(name = "os", actual = ":selected_os")
        alias(
            name = "selected_os",
            actual = select({
                ":optimized": "//constraints:linux",
                "//conditions:default": "//constraints:mac",
            }),
        )
        toolchain(
            name = "toolchain",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = [":os"],
            toolchain = ":impl",
        )
        """);
    useConfiguration(
        "--platforms=//platforms:linux", "--extra_toolchains=//extra:toolchain", "-c", "opt");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    DeclaredToolchainInfo toolchain = result.get(key).registeredToolchains().get(0);
    assertThat(toolchain.targetLabel())
        .isEqualTo(Label.parseCanonicalUnchecked("//extra:toolchain"));
    assertThat(toolchain.targetConstraints().get(setting)).isEqualTo(linuxConstraint);
  }

  @Test
  public void implicitDefaultConstraintMatches() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        platform(name = "empty")
        toolchain(
            name = "default_toolchain",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = ["//constraints:default_value"],
            toolchain = ":impl",
        )
        """);
    useConfiguration("--platforms=//extra:empty", "--extra_toolchains=//extra:default_toolchain");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(key)).contains(Label.parseCanonicalUnchecked("//extra:impl"));
  }

  @Test
  public void conflictingTargetConstraintsStillReportError() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        toolchain(
            name = "bad",
            toolchain_type = "//toolchain:test_toolchain",
            target_compatible_with = ["//constraints:linux", "//constraints:mac"],
            toolchain = ":impl",
        )
        """);
    reporter.removeHandler(failFastHandler);
    useConfiguration("--platforms=//platforms:linux", "--extra_toolchains=//extra:bad");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThat(result.hasError()).isTrue();
    assertContainsEvent("Duplicate constraint values detected");
  }

  @Test
  public void missingToolchainTypeReportsErrorWithKeepGoing() throws Exception {
    scratch.file("extra/BUILD", "toolchain(name = 'bad', toolchain = ':impl')");
    reporter.removeHandler(failFastHandler);
    useConfiguration("--extra_toolchains=//extra:bad");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);

    EvaluationResult<RegisteredToolchainsValue> result;
    try {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true);
      result =
          SkyframeExecutorTestUtils.evaluate(
              getSkyframeExecutor(), key, /* keepGoing= */ true, reporter);
    } finally {
      getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false);
    }

    assertThat(result.hasError()).isTrue();
    assertContainsEvent("missing value for mandatory attribute 'toolchain_type'");
  }

  @Test
  public void onlyRequestedTypeIsConfigured() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        toolchain_type(name = "other_type")
        toolchain(
            name = "other_toolchain",
            toolchain_type = ":other_type",
            toolchain = ":other_impl",
        )
        """);
    useConfiguration("--platforms=//platforms:mac", "--extra_toolchains=//extra:other_toolchain");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(key))
        .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl"));
    assertThat(getKnownConfigurations("//extra:other_toolchain")).isEmpty();

    SkyKey otherKey =
        registeredToolchainsKey(
            Label.parseCanonicalUnchecked("//extra:other_type"), /* debug= */ false);
    result = requestToolchainsFromSkyframe(otherKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(otherKey))
        .containsExactly(Label.parseCanonicalUnchecked("//extra:other_impl"));
    assertThat(getKnownConfigurations("//extra:other_toolchain")).isNotEmpty();
  }

  @Test
  public void configurableTypeAliasIsPreserved() throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        toolchain_type(name = "other_type")
        config_setting(name = "optimized", values = {"compilation_mode": "opt"})
        alias(
            name = "type_alias",
            actual = select({
                ":optimized": "//toolchain:test_toolchain",
                "//conditions:default": ":other_type",
            }),
        )
        toolchain(
            name = "extra_toolchain",
            toolchain_type = ":type_alias",
            toolchain = select({
                ":optimized": ":optimized_impl",
                "//conditions:default": ":default_impl",
            }),
        )
        """);
    useConfiguration("--extra_toolchains=//extra:extra_toolchain", "-c", "opt");
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(key))
        .contains(Label.parseCanonicalUnchecked("//extra:optimized_impl"));

    useConfiguration("--extra_toolchains=//extra:extra_toolchain", "-c", "fastbuild");
    key =
        registeredToolchainsKey(
            Label.parseCanonicalUnchecked("//extra:other_type"), /* debug= */ false);
    result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(key))
        .contains(Label.parseCanonicalUnchecked("//extra:default_impl"));
  }

  @Test
  public void testRegisteredToolchains() throws Exception {
    useConfiguration("--platforms=//platforms:mac");
    // Request the toolchains.
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertThatEvaluationResult(result).hasEntryThat(toolchainsKey).isNotNull();

    RegisteredToolchainsValue value = result.get(toolchainsKey);

    // Check that the number of toolchains created for this test is correct.
    assertThat(
            value.registeredToolchains().stream()
                .filter(toolchain -> toolchain.toolchainType().equals(testToolchainTypeInfo))
                .collect(Collectors.toList()))
        .hasSize(1);

    assertThat(
            value.registeredToolchains().stream()
                .anyMatch(
                    toolchain ->
                        toolchain.toolchainType().equals(testToolchainTypeInfo)
                            && toolchain.execConstraints().get(setting).equals(linuxConstraint)
                            && toolchain.targetConstraints().get(setting).equals(macConstraint)
                            && toolchain
                                .resolvedToolchainLabel()
                                .equals(
                                    Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl"))))
        .isTrue();
  }

  @Test
  public void testRegisteredToolchains_flagOverride() throws Exception {

    // Add an extra toolchain.
    scratch.file(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        """);

    rewriteModuleDotBazel(
        """
        register_toolchains('//toolchain:toolchain_2')
        """);
    useConfiguration("--platforms=//platforms:linux", "--extra_toolchains=//extra:extra_toolchain");

    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_toolchains flag is first in the list.
    assertToolchainLabels(result.get(toolchainsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"),
            Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"))
        .inOrder();
  }

  @Test
  public void testRegisteredToolchains_flagOverride_multiple() throws Exception {

    // Add an extra toolchain.
    scratch.file(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain(
            name = "extra_toolchain_1",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            toolchain = ":extra_toolchain_impl_1",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl_1",
            data = "extra",
        )

        toolchain(
            name = "extra_toolchain_2",
            exec_compatible_with = ["//constraints:mac"],
            target_compatible_with = ["//constraints:linux"],
            toolchain = ":extra_toolchain_impl_2",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl_2",
            data = "extra2",
        )
        """);

    useConfiguration(
        "--platforms=//platforms:linux",
        "--extra_toolchains=//extra:extra_toolchain_1",
        "--extra_toolchains=//extra:extra_toolchain_2");

    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_toolchains flag is first in the list.
    assertToolchainLabels(result.get(toolchainsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl_2"),
            Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl_1"),
            Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"))
        .inOrder();
  }

  @Test
  public void testRegisteredToolchains_notToolchain() throws Exception {
    rewriteModuleDotBazel(
        """
        register_toolchains("//error:not_a_toolchain")
        """);
    scratch.file("error/BUILD", "filegroup(name = 'not_a_toolchain')");

    // Request the toolchains.
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(toolchainsKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "invalid registered toolchain '//error:not_a_toolchain': "
                + "target does not provide the DeclaredToolchainInfo provider");
  }

  // Test confirming that targets with the kind `toolchain rule` will be properly rejected if they
  // don't provide the DeclaredToolchainInfo provider.
  @Test
  public void testRegisteredToolchains_fakeToolchain() throws Exception {
    rewriteModuleDotBazel(
        """
        register_toolchains("//error:not_a_toolchain")
        """);
    scratch.file(
        "error/fake_toolchain.bzl",
        """
        def _fake_impl(ctx):
          pass

        toolchain = rule(implementation = _fake_impl)
        """);
    scratch.file(
        "error/BUILD",
        """
        load(':fake_toolchain.bzl', fake_toolchain='toolchain')
        fake_toolchain(name = 'not_a_toolchain')
        """);

    // Request the toolchains.
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(toolchainsKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "invalid registered toolchain '//error:not_a_toolchain': "
                + "target does not provide the DeclaredToolchainInfo provider");
  }

  // Test exercising an edge case in the current RegisteredToolchainsFunction logic: if a target
  // has the kind `toolchain rule`, it must provide the DeclaredToolchainInfo provider, or the
  // RegisteredToolchainsFunction will fail.
  @Test
  public void testRegisteredToolchains_wildcard_fakeToolchain() throws Exception {
    rewriteModuleDotBazel(
        """
        register_toolchains("//error:all")
        """);
    scratch.file(
        "error/fake_toolchain.bzl",
        """
        def _fake_impl(ctx):
          pass

        toolchain = rule(implementation = _fake_impl)
        """);
    scratch.file(
        "error/BUILD",
        """
        load(':fake_toolchain.bzl', fake_toolchain='toolchain')
        fake_toolchain(name = 'not_a_toolchain')
        """);

    // Request the toolchains.
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result)
        .hasErrorEntryForKeyThat(toolchainsKey)
        .hasExceptionThat()
        .hasMessageThat()
        .contains(
            "invalid registered toolchain '//error:not_a_toolchain': "
                + "target does not provide the DeclaredToolchainInfo provider");
  }

  @Test
  public void testRegisteredToolchains_targetPattern_workspace() throws Exception {
    scratch.appendFile("extra/BUILD", "filegroup(name = 'not_a_platform')");
    addToolchain(
        "extra",
        "extra_toolchain1",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "foo");
    addToolchain(
        "extra",
        "extra_toolchain2",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:mac"),
        "bar");
    addToolchain(
        "extra/more",
        "more_toolchain",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    rewriteModuleDotBazel(
        """
        register_toolchains("//extra/...")
        """);

    useConfiguration("--platforms=//platforms:linux");
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey), PackageIdentifier.createInMainRepo("extra"))
        .containsExactly(
            Label.parseCanonicalUnchecked("//extra:extra_toolchain1_impl"),
            Label.parseCanonicalUnchecked("//extra/more:more_toolchain_impl"));
  }

  @Test
  public void testRegisteredToolchains_targetPattern_flagOverride() throws Exception {
    scratch.appendFile("extra/BUILD", "filegroup(name = 'not_a_platform')");
    addToolchain(
        "extra",
        "extra_toolchain1",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:linux"),
        "foo");
    addToolchain(
        "extra",
        "extra_toolchain2",
        ImmutableList.of("//constraints:linux"),
        ImmutableList.of("//constraints:mac"),
        "bar");
    addToolchain(
        "extra/more",
        "more_toolchain",
        ImmutableList.of("//constraints:mac"),
        ImmutableList.of("//constraints:linux"),
        "baz");
    useConfiguration("--platforms=//platforms:linux", "--extra_toolchains=//extra/...");

    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey))
        .containsAtLeast(
            Label.parseCanonicalUnchecked("//extra:extra_toolchain1_impl"),
            Label.parseCanonicalUnchecked("//extra/more:more_toolchain_impl"));
  }

  private void addSimpleToolchain(String packageName, String toolchainName) throws Exception {
    addToolchain(packageName, toolchainName, ImmutableList.of(), ImmutableList.of(), "foo");
  }

  @Test
  public void testRegisteredToolchains_targetPattern_order() throws Exception {
    addSimpleToolchain("extra", "bbb");
    addSimpleToolchain("extra", "ccc");
    addSimpleToolchain("extra", "aaa");
    addSimpleToolchain("extra/yyy", "bbb");
    addSimpleToolchain("extra/yyy", "ccc");
    addSimpleToolchain("extra/yyy", "aaa");
    addSimpleToolchain("extra/xxx", "bbb");
    addSimpleToolchain("extra/xxx", "ccc");
    addSimpleToolchain("extra/xxx", "aaa");
    addSimpleToolchain("extra/zzz", "bbb");
    addSimpleToolchain("extra/zzz", "ccc");
    addSimpleToolchain("extra/zzz", "aaa");
    addSimpleToolchain("extra/yyy/yyy", "bbb");
    addSimpleToolchain("extra/yyy/yyy", "ccc");
    addSimpleToolchain("extra/yyy/yyy", "aaa");
    addSimpleToolchain("extra/yyy/xxx", "bbb");
    addSimpleToolchain("extra/yyy/xxx", "ccc");
    addSimpleToolchain("extra/yyy/xxx", "aaa");
    addSimpleToolchain("extra/yyy/zzz", "bbb");
    addSimpleToolchain("extra/yyy/zzz", "ccc");
    addSimpleToolchain("extra/yyy/zzz", "aaa");
    addSimpleToolchain("extra/xxx/yyy", "bbb");
    addSimpleToolchain("extra/xxx/yyy", "ccc");
    addSimpleToolchain("extra/xxx/yyy", "aaa");
    addSimpleToolchain("extra/xxx/xxx", "bbb");
    addSimpleToolchain("extra/xxx/xxx", "ccc");
    addSimpleToolchain("extra/xxx/xxx", "aaa");
    addSimpleToolchain("extra/xxx/zzz", "bbb");
    addSimpleToolchain("extra/xxx/zzz", "ccc");
    addSimpleToolchain("extra/xxx/zzz", "aaa");
    rewriteModuleDotBazel(
        """
        register_toolchains("//extra/...")
        """);

    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey), PackageIdentifier.createInMainRepo("extra"))
        .containsExactly(
            Label.parseCanonicalUnchecked("//extra/xxx/xxx:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/xxx:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/xxx:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/yyy:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/yyy:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/yyy:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/zzz:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/zzz:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx/zzz:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/xxx:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/xxx:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/xxx:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/xxx:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/yyy:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/yyy:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/yyy:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/zzz:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/zzz:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy/zzz:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/yyy:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra/zzz:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra/zzz:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra/zzz:ccc_impl"),
            Label.parseCanonicalUnchecked("//extra:aaa_impl"),
            Label.parseCanonicalUnchecked("//extra:bbb_impl"),
            Label.parseCanonicalUnchecked("//extra:ccc_impl"))
        .inOrder();
  }

  @Test
  public void testRegisteredToolchains_reload() throws Exception {
    rewriteModuleDotBazel(
        """
        register_toolchains("//toolchain:toolchain_1")
        """);

    useConfiguration("--platforms=//platforms:mac");
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey))
        .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl"));

    // Re-write the MODULE.bazel.
    rewriteModuleDotBazel(
        """
        register_toolchains("//toolchain:toolchain_2")
        """);

    useConfiguration("--platforms=//platforms:linux");
    toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    result = requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey))
        .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"));
  }

  @Test
  public void testRegisteredToolchains_bzlmod() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "register_toolchains('//:tool')",
        "register_toolchains('//:dev_tool',dev_dependency=True)",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='1.1')",
        "bazel_dep(name='toolchain_def',version='1.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb',version='1.0')",
            "register_toolchains('//:tool')",
            "register_toolchains('//:dev_tool',dev_dependency=True)",
            "bazel_dep(name='ddd',version='1.0')",
            "bazel_dep(name='toolchain_def',version='1.0')")
        .addModule(
            createModuleKey("ccc", "1.1"),
            "module(name='ccc',version='1.1')",
            "register_toolchains('//:tool')",
            "register_toolchains('//:dev_tool',dev_dependency=True)",
            "bazel_dep(name='ddd',version='1.1')",
            "bazel_dep(name='toolchain_def',version='1.0')")
        // ddd@1.0 is not selected
        .addModule(
            createModuleKey("ddd", "1.0"),
            "module(name='ddd',version='1.0')",
            "register_toolchains('//:tool')",
            "register_toolchains('//:dev_tool',dev_dependency=True)",
            "bazel_dep(name='toolchain_def',version='1.0')")
        .addModule(
            createModuleKey("ddd", "1.1"),
            "module(name='ddd',version='1.1')",
            "register_toolchains('@eee//:tool', '//:tool')",
            "register_toolchains('@eee//:dev_tool',dev_dependency=True)",
            "bazel_dep(name='eee',version='1.0')",
            "bazel_dep(name='toolchain_def',version='1.0')")
        .addModule(
            createModuleKey("eee", "1.0"),
            "module(name='eee',version='1.0')",
            "bazel_dep(name='toolchain_def',version='1.0')")
        .addModule(
            createModuleKey("toolchain_def", "1.0"), "module(name='toolchain_def',version='1.0')");

    // Everyone depends on toolchain_def@1.0 for the declare_toolchain macro.
    Path toolchainDefDir = moduleRoot.getRelative("toolchain_def+1.0");
    scratch.file(toolchainDefDir.getRelative("REPO.bazel").getPathString());
    scratch.file(
        toolchainDefDir.getRelative("BUILD").getPathString(),
        "toolchain_type(name = 'test_toolchain')");
    scratch.file(
        toolchainDefDir.getRelative("toolchain_def.bzl").getPathString(),
        "def _impl(ctx):",
        "    toolchain = platform_common.ToolchainInfo(data = ctx.attr.data)",
        "    return [toolchain]",
        "test_toolchain = rule(implementation = _impl, attrs = {'data': attr.string()})",
        "def declare_toolchain(name):",
        "    native.toolchain(",
        "        name = name,",
        "        toolchain_type = Label('//:test_toolchain'),",
        "        toolchain = ':' + name + '_impl')",
        "    test_toolchain(",
        "        name = name + '_impl',",
        "        data = 'stuff')");

    // Now create the toolchains for each module.
    for (String repo : ImmutableList.of("bbb+1.0", "ccc+1.1", "ddd+1.0", "ddd+1.1", "eee+1.0")) {
      scratch.file(moduleRoot.getRelative(repo).getRelative("REPO.bazel").getPathString());
      scratch.file(
          moduleRoot.getRelative(repo).getRelative("BUILD").getPathString(),
          "load('@toolchain_def//:toolchain_def.bzl', 'declare_toolchain')",
          "declare_toolchain(name='tool')",
          "declare_toolchain(name='dev_tool')");
    }
    scratch.overwriteFile(
        "BUILD",
        "load('@toolchain_def//:toolchain_def.bzl', 'declare_toolchain')",
        "declare_toolchain(name='dev_tool')",
        "declare_toolchain(name='tool')");
    invalidatePackages();

    SkyKey toolchainsKey =
        registeredToolchainsKey(
            Label.parseCanonicalUnchecked("@@toolchain_def+//:test_toolchain"), /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    if (result.hasError()) {
      throw result.getError().getException();
    }
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the toolchains registered with bzlmod come in the BFS order
    assertToolchainLabels(result.get(toolchainsKey))
        .containsAtLeast(
            // Root module toolchains
            Label.parseCanonical("//:tool_impl"),
            Label.parseCanonical("//:dev_tool_impl"),
            // Other modules' toolchains
            Label.parseCanonical("@@bbb+//:tool_impl"),
            Label.parseCanonical("@@ccc+//:tool_impl"),
            Label.parseCanonical("@@eee+//:tool_impl"),
            Label.parseCanonical("@@ddd+//:tool_impl"))
        .inOrder();
  }

  @Test
  public void testRegisteredToolchains_targetSetting() throws Exception {
    // Add an extra toolchain with a target_setting
    scratch.file(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            target_settings = [
                ":optimized",
            ],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        """);

    rewriteModuleDotBazel(
        """
        register_toolchains("//toolchain:toolchain_2", "//extra:extra_toolchain")
        """);

    useConfiguration("--platforms=//platforms:linux");
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the target registered with the extra_toolchains flag is not present, because of
    // the configuration.
    RegisteredToolchainsValue registeredToolchainsValue = result.get(toolchainsKey);
    assertToolchainLabels(registeredToolchainsValue)
        .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"));
    assertToolchainLabels(registeredToolchainsValue)
        .doesNotContain(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertThat(registeredToolchainsValue.rejectedToolchains()).isNull();
  }

  @Test
  public void testRegisteredToolchains_targetSetting_debug() throws Exception {
    // Add an extra toolchain with a target_setting
    scratch.file(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            target_settings = [
                ":optimized",
            ],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        """);

    rewriteModuleDotBazel(
        """
        register_toolchains("//toolchain:toolchain_1", "//extra:extra_toolchain")
        """);

    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ true);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the message about the unmatched config_setting is present.
    RegisteredToolchainsValue registeredToolchainsValue = result.get(toolchainsKey);
    assertThat(registeredToolchainsValue.rejectedToolchains()).isNotNull();
    assertThat(registeredToolchainsValue.rejectedToolchains())
        .containsCell(
            testToolchainTypeLabel,
            Label.parseCanonicalUnchecked("//extra:extra_toolchain"),
            "mismatching target_settings: optimized");
  }

  @Test
  public void testRegisteredToolchains_targetSetting_featureFlag() throws Exception {
    // Add an extra toolchain with a target_setting
    scratch.file(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "flagged",
            flag_values = {":flag": "default"},
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            target_settings = [
                ":flagged",
            ],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        """);

    rewriteModuleDotBazel(
        """
        register_toolchains("//toolchain:toolchain_1", "//extra:extra_toolchain")
        """);

    // Target settings are evaluated in the target configuration, just like select() conditions,
    // so they aren't affected by the trimming of feature flags on dependency edges.
    useConfiguration(
        "--platforms=//platforms:linux", "--enforce_transitive_configs_for_config_feature_flag");
    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(toolchainsKey))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
  }

  @Test
  public void testRegisteredToolchainsValue_equalsAndHashCode() throws Exception {
    DeclaredToolchainInfo toolchain1 =
        DeclaredToolchainInfo.builder()
            .toolchainType(
                ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//test:toolchain")))
            .addExecConstraints(ImmutableList.of())
            .addTargetConstraints(ImmutableList.of())
            .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//test/toolchain_impl_1"))
            .targetLabel(Label.parseCanonicalUnchecked("//test/toolchain_1"))
            .build();
    DeclaredToolchainInfo toolchain2 =
        DeclaredToolchainInfo.builder()
            .toolchainType(
                ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//test:toolchain")))
            .addExecConstraints(ImmutableList.of())
            .addTargetConstraints(ImmutableList.of())
            .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//test/toolchain_impl_2"))
            .targetLabel(Label.parseCanonicalUnchecked("//test/toolchain_2"))
            .build();

    new EqualsTester()
        .addEqualityGroup(
            RegisteredToolchainsValue.create(
                ImmutableList.of(toolchain1, toolchain2), /* rejectedToolchains= */ null),
            RegisteredToolchainsValue.create(
                ImmutableList.of(toolchain1, toolchain2), /* rejectedToolchains= */ null))
        .addEqualityGroup(
            RegisteredToolchainsValue.create(
                ImmutableList.of(toolchain1), /* rejectedToolchains= */ null))
        .addEqualityGroup(
            RegisteredToolchainsValue.create(
                ImmutableList.of(toolchain2), /* rejectedToolchains= */ null))
        .addEqualityGroup(
            RegisteredToolchainsValue.create(
                ImmutableList.of(toolchain2, toolchain1), /* rejectedToolchains= */ null))
        .testEquals();
  }

  @Test
  public void testRegisteredToolchains_wildcard_nonRuleTargets() throws Exception {
    // Add a toolchain and a non-rule target in the root package.
    scratch.file(
        "BUILD",
        """
        load('//toolchain:toolchain_def.bzl', 'test_toolchain')

        exports_files(['some_file'])

        toolchain(
            name = 'root_toolchain',
            exec_compatible_with = ['//constraints:linux'],
            target_compatible_with = ['//constraints:linux'],
            toolchain = ':root_toolchain_impl',
            toolchain_type = '//toolchain:test_toolchain',
        )

        test_toolchain(
            name = 'root_toolchain_impl',
            data = 'root',
        )
        """);

    useConfiguration("--extra_toolchains=//:*");

    SkyKey toolchainsKey = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    EvaluationResult<RegisteredToolchainsValue> result =
        requestToolchainsFromSkyframe(toolchainsKey);
    assertThatEvaluationResult(result).hasNoError();

    // Verify that the toolchain was registered and the filegroup was filtered out.
    assertToolchainLabels(result.get(toolchainsKey))
        .contains(Label.parseCanonicalUnchecked("//:root_toolchain_impl"));
  }

  private BuildConfigurationKey noConfigKey() {
    return BuildConfigurationKey.create(CommonOptions.noConfigOptions(targetConfig.getOptions()));
  }

  private void writeOptimizedToolchain(String extraAttrs) throws Exception {
    scratch.file(
        "extra/BUILD",
        """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "optimized",
            values = {"compilation_mode": "opt"},
        )

        alias(
            name = "type_alias",
            actual = "//toolchain:test_toolchain",
        )

        alias(
            name = "optimized_alias",
            actual = ":optimized",
        )

        config_setting(
            name = "debug",
            values = {"compilation_mode": "dbg"},
        )

        config_setting(
            name = "stripped",
            values = {"strip": "always"},
        )

        # Resolves to a different config_setting depending on the configuration.
        alias(
            name = "optimized_or_stripped",
            actual = select({
                ":debug": ":stripped",
                "//conditions:default": ":optimized",
            }),
        )

        filegroup(name = "not_a_setting")

        toolchain(
            name = "extra_toolchain",
            toolchain = ":extra_toolchain_impl",
            %s
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        """
            .formatted(extraAttrs));
  }

  private RegisteredToolchainsValue requestToolchains(String... flags) throws Exception {
    useConfiguration(
        ImmutableList.<String>builder()
            .add("--extra_toolchains=//extra:extra_toolchain")
            .add(flags)
            .build()
            .toArray(String[]::new));
    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ true);
    var result = requestToolchainsFromSkyframe(key);
    assertThatEvaluationResult(result).hasNoError();
    return result.get(key);
  }

  @Test
  public void targetSettings_toolchainAnalyzedWithoutConfiguration() throws Exception {
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = [":optimized"],
        """);

    assertToolchainLabels(requestToolchains("-c", "opt"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    var fastbuild = requestToolchains("-c", "fastbuild");
    assertToolchainLabels(fastbuild)
        .doesNotContain(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertThat(fastbuild.rejectedToolchains())
        .containsCell(
            testToolchainTypeLabel,
            Label.parseCanonicalUnchecked("//extra:extra_toolchain"),
            "mismatching target_settings: optimized");

    assertThat(getKnownConfigurations("//extra:extra_toolchain")).containsExactly(noConfigKey());
    assertThat(getKnownConfigurations("//extra:optimized")).doesNotContain(noConfigKey());
  }

  @Test
  public void targetSettings_select_toolchainAnalyzedPerConfiguration() throws Exception {
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = select({
            ":optimized": [":optimized"],
            "//conditions:default": [],
        }),
        """);

    assertToolchainLabels(requestToolchains("-c", "opt"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertToolchainLabels(requestToolchains("-c", "fastbuild"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));

    assertThat(getKnownConfigurations("//extra:extra_toolchain")).doesNotContain(noConfigKey());
    assertThat(getKnownConfigurations("//extra:extra_toolchain")).hasSize(2);
  }

  @Test
  public void aliasedToolchainType_toolchainAnalyzedPerConfiguration() throws Exception {
    writeOptimizedToolchain(
        """
        toolchain_type = ":type_alias",
        """);

    assertToolchainLabels(requestToolchains())
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));

    assertThat(getKnownConfigurations("//extra:extra_toolchain")).contains(targetConfigKey);
    assertThat(getKnownConfigurations("//extra:extra_toolchain")).doesNotContain(noConfigKey());
  }

  @Test
  public void aliasedTargetSetting_toolchainAnalyzedWithoutConfiguration() throws Exception {
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = [":optimized_alias"],
        """);

    assertToolchainLabels(requestToolchains("-c", "opt"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertToolchainLabels(requestToolchains("-c", "fastbuild"))
        .doesNotContain(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));

    assertThat(getKnownConfigurations("//extra:extra_toolchain")).containsExactly(noConfigKey());
    assertThat(getKnownConfigurations("//extra:optimized_alias")).doesNotContain(noConfigKey());
  }

  @Test
  public void configurableAliasTargetSetting_toolchainAnalyzedWithoutConfiguration()
      throws Exception {
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = [":optimized_or_stripped"],
        """);

    assertToolchainLabels(requestToolchains("-c", "opt"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertToolchainLabels(requestToolchains("-c", "fastbuild"))
        .doesNotContain(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertToolchainLabels(requestToolchains("-c", "dbg", "--strip=always"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertToolchainLabels(requestToolchains("-c", "dbg", "--strip=never"))
        .doesNotContain(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));

    assertThat(getKnownConfigurations("//extra:extra_toolchain")).containsExactly(noConfigKey());
  }

  @Test
  public void targetSettings_notAConfigSetting() throws Exception {
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = [":not_a_setting"],
        """);
    useConfiguration("--extra_toolchains=//extra:extra_toolchain");
    reporter.removeHandler(failFastHandler);

    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    assertThatEvaluationResult(requestToolchainsFromSkyframe(key)).hasErrorEntryForKeyThat(key);
    assertContainsEvent(
        "in target_settings attribute of toolchain rule //extra:extra_toolchain: filegroup rule"
            + " '//extra:not_a_setting' is misplaced here (expected config_setting)");
  }

  @Test
  public void targetSettings_notVisible() throws Exception {
    scratch.file(
        "private/BUILD",
        """
        config_setting(
            name = "optimized",
            values = {"compilation_mode": "opt"},
            visibility = ["//visibility:private"],
        )
        """);
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = ["//private:optimized"],
        """);
    useConfiguration("--extra_toolchains=//extra:extra_toolchain");
    reporter.removeHandler(failFastHandler);

    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    assertThatEvaluationResult(requestToolchainsFromSkyframe(key)).hasErrorEntryForKeyThat(key);
    assertContainsEvent(
        "Visibility error:\ntarget '//private:optimized' is not visible from\n"
            + "target '//extra:extra_toolchain'");
  }

  @Test
  public void targetSettings_testonly() throws Exception {
    scratch.file(
        "testonly/BUILD",
        """
        config_setting(
            name = "optimized",
            testonly = True,
            values = {"compilation_mode": "opt"},
            visibility = ["//visibility:public"],
        )
        """);
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = ["//testonly:optimized"],
        """);
    useConfiguration("--extra_toolchains=//extra:extra_toolchain");
    reporter.removeHandler(failFastHandler);

    SkyKey key = registeredToolchainsKey(testToolchainTypeLabel, /* debug= */ false);
    assertThatEvaluationResult(requestToolchainsFromSkyframe(key)).hasErrorEntryForKeyThat(key);
    assertContainsEvent(
        "non-test target '//extra:extra_toolchain' depends on testonly target"
            + " '//testonly:optimized' and doesn't have testonly attribute set");
  }

  @Test
  public void targetSettings_deprecated() throws Exception {
    scratch.file(
        "deprecated/BUILD",
        """
        config_setting(
            name = "optimized",
            deprecation = "Use something else",
            values = {"compilation_mode": "opt"},
            visibility = ["//visibility:public"],
        )
        """);
    writeOptimizedToolchain(
        """
        toolchain_type = "//toolchain:test_toolchain",
        target_settings = ["//deprecated:optimized"],
        """);

    assertToolchainLabels(requestToolchains("-c", "opt"))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));
    assertContainsEvent(
        "target '//extra:extra_toolchain' depends on deprecated target '//deprecated:optimized':"
            + " Use something else");
  }

  @Test
  public void sharedDeclarationRespectsVisibilityFlagsAcrossConfigurations() throws Exception {
    scratch.file(
        "private_type/BUILD",
        "toolchain_type(name = 'private', visibility = ['//visibility:private'])");
    writeOptimizedToolchain("toolchain_type = '//private_type:private',");
    reporter.removeHandler(failFastHandler);
    Label type = Label.parseCanonicalUnchecked("//private_type:private");
    useConfiguration("--extra_toolchains=//extra:extra_toolchain", "--check_visibility");
    SkyKey checked = registeredToolchainsKey(type, /* debug= */ false);
    assertThatEvaluationResult(requestToolchainsFromSkyframe(checked))
        .hasErrorEntryForKeyThat(checked);
    assertContainsEvent("is not visible from");

    useConfiguration("--extra_toolchains=//extra:extra_toolchain", "--nocheck_visibility");
    SkyKey unchecked = registeredToolchainsKey(type, /* debug= */ false);
    var result = requestToolchainsFromSkyframe(unchecked);
    assertThatEvaluationResult(result).hasNoError();
    assertToolchainLabels(result.get(unchecked))
        .contains(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"));

    useConfiguration("--extra_toolchains=//extra:extra_toolchain", "--check_visibility");
    checked = registeredToolchainsKey(type, /* debug= */ false);
    assertThatEvaluationResult(requestToolchainsFromSkyframe(checked))
        .hasErrorEntryForKeyThat(checked);
  }
}
