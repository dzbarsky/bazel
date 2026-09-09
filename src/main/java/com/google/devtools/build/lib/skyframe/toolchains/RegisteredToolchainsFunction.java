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

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.CommonOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection;
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo;
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.platform.PlatformProviderUtils;
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue;
import com.google.devtools.build.lib.bazel.bzlmod.ExternalDepsException;
import com.google.devtools.build.lib.bazel.bzlmod.Module;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.cmdline.SignedTargetPattern;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.rules.platform.ToolchainRule;
import com.google.devtools.build.lib.server.FailureDetails.Toolchain.Code;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.ConfiguredValueCreationException;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.skyframe.TargetPatternUtil;
import com.google.devtools.build.lib.skyframe.TargetPatternUtil.InvalidTargetPatternException;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * {@link SkyFunction} that returns all registered toolchains available for toolchain resolution.
 */
public class RegisteredToolchainsFunction implements SkyFunction {

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RegisteredToolchainsValue.Key key = (RegisteredToolchainsValue.Key) skyKey;
    BuildConfigurationValue configuration =
        (BuildConfigurationValue) env.getValue(key.getConfigurationKey());
    RepositoryMappingValue mainRepoMapping =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    if (env.valuesMissing()) {
      return null;
    }

    TargetPattern.Parser mainRepoParser =
        new TargetPattern.Parser(
            PathFragment.EMPTY_FRAGMENT, RepositoryName.MAIN, mainRepoMapping.repositoryMapping());
    ImmutableList.Builder<SignedTargetPattern> targetPatternBuilder = new ImmutableList.Builder<>();

    // Get the toolchains from the configuration.
    // Reverse the list so the last one defined takes precedences.
    PlatformConfiguration platformConfiguration =
        configuration.getFragment(PlatformConfiguration.class);
    try {
      targetPatternBuilder.addAll(
          TargetPatternUtil.parseAllSigned(
              platformConfiguration.getExtraToolchains().reverse(), mainRepoParser));
    } catch (InvalidTargetPatternException e) {
      throw new RegisteredToolchainsFunctionException(
          new InvalidToolchainLabelException(e), Transience.PERSISTENT);
    }

    // Get registered toolchains from the external dep graph.
    ImmutableList<TargetPattern> bzlmodToolchains = getBzlmodToolchains(env);
    if (bzlmodToolchains == null) {
      return null;
    }
    targetPatternBuilder.addAll(TargetPatternUtil.toSigned(bzlmodToolchains));

    // Expand target patterns.
    ImmutableSet<Label> toolchainLabels;
    try {
      toolchainLabels =
          TargetPatternUtil.expandTargetPatterns(
              env, targetPatternBuilder.build(), FilteringPolicies.ruleTypeExplicit("toolchain"));
      if (env.valuesMissing()) {
        return null;
      }
    } catch (TargetPatternUtil.InvalidTargetPatternException e) {
      throw new RegisteredToolchainsFunctionException(
          new InvalidToolchainLabelException(e), Transience.PERSISTENT);
    }

    toolchainLabels = filterToolchainTypes(env, toolchainLabels, key.toolchainType());
    if (toolchainLabels == null) {
      return null;
    }

    // Debugging retains all declarations so resolution can explain every rejection.
    if (!key.debug()) {
      toolchainLabels = filterTargetPlatforms(env, key.targetPlatformKey(), toolchainLabels);
      if (toolchainLabels == null) {
        return null;
      }
    }

    // Load the configured target for each, and get the declared toolchain providers.
    ImmutableList<DeclaredToolchainInfo> registeredToolchains =
        configureRegisteredToolchains(env, configuration, toolchainLabels);
    if (env.valuesMissing()) {
      return null;
    }

    // Check which toolchains are valid according to their configuration.
    ImmutableList.Builder<DeclaredToolchainInfo> validToolchains = new ImmutableList.Builder<>();
    // Some toolchains end up with repeated reasons, so use a HashBasedTable to handle duplicates.
    Table<Label, Label, String> rejectedToolchains = key.debug() ? HashBasedTable.create() : null;
    for (DeclaredToolchainInfo toolchain : registeredToolchains) {
      try {
        Consumer<String> errorHandler =
            key.debug()
                ? message ->
                    rejectedToolchains.put(
                        toolchain.toolchainType().typeLabel(), toolchain.targetLabel(), message)
                : null;
        if (ConfigMatchingUtil.validate(
            toolchain.targetLabel(),
            toolchain.targetSettings(),
            errorHandler,
            ToolchainRule.TARGET_SETTING_ATTR)) {
          validToolchains.add(toolchain);
        }
      } catch (InvalidConfigurationException e) {
        throw new RegisteredToolchainsFunctionException(
            new InvalidToolchainLabelException(toolchain.targetLabel(), e), Transience.PERSISTENT);
      }
    }

    return RegisteredToolchainsValue.create(
        validToolchains.build(),
        rejectedToolchains != null ? ImmutableTable.copyOf(rejectedToolchains) : null);
  }

  @Nullable
  private static ImmutableSet<Label> filterToolchainTypes(
      Environment env, ImmutableSet<Label> labels, Label requestedType)
      throws InterruptedException {
    SkyframeLookupResult packages =
        env.getValuesAndExceptions(
            labels.stream().map(Label::getPackageIdentifier).collect(toImmutableSet()));
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableMap.Builder<Label, Label> declaredTypes = ImmutableMap.builder();
    for (Label label : labels) {
      Target target = getTarget(packages, label);
      if (target instanceof Rule rule
          && !rule.getRuleClassObject().isStarlark()
          && rule.getRuleClass().equals(ToolchainRule.RULE_NAME)) {
        Label declaredType =
            RawAttributeMapper.of(rule).get(ToolchainRule.TOOLCHAIN_TYPE_ATTR, BuildType.LABEL);
        // Keep malformed declarations for configured target analysis to report.
        if (declaredType != null) {
          declaredTypes.put(label, declaredType);
        }
      }
    }
    ImmutableMap<Label, Label> types = declaredTypes.buildOrThrow();
    ImmutableSet<PackageIdentifier> typePackages =
        types.values().stream().map(Label::getPackageIdentifier).collect(toImmutableSet());
    SkyframeLookupResult typeValues = env.getValuesAndExceptions(typePackages);
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableSet.Builder<Label> result = ImmutableSet.builder();
    for (Label label : labels) {
      Label declaredType = types.get(label);
      // A native toolchain_type always identifies itself. Aliases and other rules still need
      // configuration, since they can resolve to the requested type through a select().
      if (declaredType != null && !declaredType.equals(requestedType)) {
        Target typeTarget = getTarget(typeValues, declaredType);
        if (typeTarget instanceof Rule rule
            && !rule.getRuleClassObject().isStarlark()
            && rule.getRuleClass().equals("toolchain_type")) {
          continue;
        }
      }
      result.add(label);
    }
    return result.build();
  }

  @Nullable
  private static ImmutableSet<Label> filterTargetPlatforms(
      Environment env, ConfiguredTargetKey platformKey, ImmutableSet<Label> labels)
      throws InterruptedException {
    Map<ConfiguredTargetKey, PlatformInfo> platforms;
    try {
      platforms = PlatformLookupUtil.getPlatformInfo(ImmutableList.of(platformKey), env);
    } catch (PlatformLookupUtil.InvalidPlatformException e) {
      // The normal resolution path reports invalid platforms.
      return labels;
    }
    if (platforms == null) {
      return null;
    }

    SkyframeLookupResult packages =
        env.getValuesAndExceptions(
            labels.stream().map(Label::getPackageIdentifier).collect(toImmutableSet()));
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableMap.Builder<Label, List<Label>> constraintsBuilder = ImmutableMap.builder();
    for (Label label : labels) {
      Target target = getTarget(packages, label);
      if (isNativeRule(target, ToolchainRule.RULE_NAME)) {
        RawAttributeMapper attributes = RawAttributeMapper.of((Rule) target);
        if (!attributes.get(ToolchainRule.USE_TARGET_PLATFORM_CONSTRAINTS_ATTR, Type.BOOLEAN)) {
          constraintsBuilder.put(
              label,
              attributes.get(ToolchainRule.TARGET_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST));
        }
      }
    }
    ImmutableMap<Label, List<Label>> constraints = constraintsBuilder.buildOrThrow();
    ImmutableSet<Label> constraintLabels =
        constraints.values().stream().flatMap(List::stream).collect(toImmutableSet());
    ImmutableMap<Label, Label> nativeConstraints = resolveNativeConstraints(env, constraintLabels);
    if (nativeConstraints == null) {
      return null;
    }
    List<ConstraintValueInfo> constraintValues;
    try {
      constraintValues =
          ConstraintValueLookupUtil.getConstraintValueInfo(
              nativeConstraints.values().stream()
                  .map(
                      label ->
                          ConfiguredTargetKey.builder()
                              .setLabel(label)
                              .setConfigurationKey(
                                  BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                              .build())
                  .collect(toImmutableSet()),
              env);
    } catch (ConstraintValueLookupUtil.InvalidConstraintValueException e) {
      // Preserve configured target analysis and its error context for malformed declarations.
      return labels;
    }
    if (constraintValues == null) {
      return null;
    }
    ImmutableMap.Builder<Label, ConstraintValueInfo> valuesBuilder = ImmutableMap.builder();
    for (ConstraintValueInfo value : constraintValues) {
      valuesBuilder.put(value.label(), value);
    }
    ImmutableMap<Label, ConstraintValueInfo> values = valuesBuilder.buildOrThrow();
    ImmutableSet.Builder<Label> result = ImmutableSet.builder();
    for (Label label : labels) {
      List<Label> required = constraints.get(label);
      // Configurable constraint aliases still need the original configuration.
      if (required != null && nativeConstraints.keySet().containsAll(required)) {
        List<ConstraintValueInfo> expected =
            required.stream().map(nativeConstraints::get).map(values::get).toList();
        try {
          ConstraintCollection.builder().addConstraints(expected).build();
          if (!platforms.get(platformKey).constraints().containsAll(expected)) {
            continue;
          }
        } catch (ConstraintCollection.DuplicateConstraintException e) {
          // Keep conflicting constraints for the toolchain rule's attribute error.
        }
      }
      result.add(label);
    }
    return result.build();
  }

  @Nullable
  private static ImmutableMap<Label, Label> resolveNativeConstraints(
      Environment env, ImmutableSet<Label> labels) throws InterruptedException {
    Map<Label, Label> aliases = new HashMap<>();
    Set<Label> nativeConstraints = new HashSet<>();
    Set<Label> visited = new HashSet<>();
    ImmutableSet<Label> pending = labels;
    while (!pending.isEmpty()) {
      SkyframeLookupResult packages =
          env.getValuesAndExceptions(
              pending.stream().map(Label::getPackageIdentifier).collect(toImmutableSet()));
      if (env.valuesMissing()) {
        return null;
      }
      visited.addAll(pending);
      ImmutableSet.Builder<Label> next = ImmutableSet.builder();
      for (Label label : pending) {
        Target target = getTarget(packages, label);
        if (isNativeRule(target, "constraint_value")) {
          nativeConstraints.add(label);
        } else if (isNativeRule(target, "alias")) {
          RawAttributeMapper attributes = RawAttributeMapper.of((Rule) target);
          if (!attributes.isConfigurable("actual")) {
            Label actual = attributes.get("actual", BuildType.LABEL);
            if (actual != null) {
              aliases.put(label, actual);
              if (!visited.contains(actual)) {
                next.add(actual);
              }
            }
          }
        }
      }
      pending = next.build();
    }

    ImmutableMap.Builder<Label, Label> resolved = ImmutableMap.builder();
    for (Label label : labels) {
      Label actual = label;
      Set<Label> chain = new HashSet<>();
      while (aliases.containsKey(actual) && chain.add(actual)) {
        actual = aliases.get(actual);
      }
      // Cycles, configurable aliases, and invalid constraints retain normal analysis.
      if (nativeConstraints.contains(actual)) {
        resolved.put(label, actual);
      }
    }
    return resolved.buildOrThrow();
  }

  private static boolean isNativeRule(@Nullable Target target, String ruleClass) {
    return target instanceof Rule rule
        && !rule.getRuleClassObject().isStarlark()
        && rule.getRuleClass().equals(ruleClass);
  }

  @Nullable
  private static Target getTarget(SkyframeLookupResult packages, Label label) {
    try {
      PackageValue value =
          (PackageValue)
              packages.getOrThrow(label.getPackageIdentifier(), NoSuchPackageException.class);
      return value == null ? null : value.getPackage().getTargets().get(label.getName());
    } catch (NoSuchPackageException e) {
      // Let configured target analysis report the invalid declaration with its usual context.
      return null;
    }
  }

  @Nullable
  private static ImmutableList<TargetPattern> getBzlmodToolchains(Environment env)
      throws InterruptedException, RegisteredToolchainsFunctionException {
    BazelDepGraphValue bazelDepGraphValue =
        (BazelDepGraphValue) env.getValue(BazelDepGraphValue.KEY);
    if (bazelDepGraphValue == null) {
      return null;
    }
    ImmutableList.Builder<TargetPattern> toolchains = ImmutableList.builder();
    for (Module module : bazelDepGraphValue.getDepGraph().values()) {
      TargetPattern.Parser parser =
          new TargetPattern.Parser(
              PathFragment.EMPTY_FRAGMENT,
              bazelDepGraphValue.getCanonicalRepoNameLookup().inverse().get(module.getKey()),
              bazelDepGraphValue.getFullRepoMapping(module.getKey()));
      for (String pattern : module.getToolchainsToRegister()) {
        try {
          toolchains.add(parser.parse(pattern));
        } catch (TargetParsingException e) {
          throw new RegisteredToolchainsFunctionException(
              new InvalidToolchainLabelException(pattern, e), Transience.PERSISTENT);
        }
      }
    }
    return toolchains.build();
  }

  @Nullable
  private static ImmutableList<DeclaredToolchainInfo> configureRegisteredToolchains(
      Environment env, BuildConfigurationValue configuration, Set<Label> labels)
      throws InterruptedException, RegisteredToolchainsFunctionException {
    ImmutableSet<ActionLookupKey> keys =
        labels.stream()
            .map(
                label ->
                    ConfiguredTargetKey.builder()
                        .setLabel(label)
                        .setConfiguration(configuration)
                        .build())
            .collect(toImmutableSet());

    SkyframeLookupResult values = env.getValuesAndExceptions(keys);
    ImmutableList.Builder<DeclaredToolchainInfo> toolchains = new ImmutableList.Builder<>();
    boolean valuesMissing = false;
    for (ActionLookupKey key : keys) {
      Label toolchainLabel = key.getLabel();
      try {
        SkyValue value = values.getOrThrow(key, ConfiguredValueCreationException.class);
        if (value == null) {
          valuesMissing = true;
          continue;
        }

        ConfiguredTarget target = ((ConfiguredTargetValue) value).getConfiguredTarget();
        DeclaredToolchainInfo toolchainInfo = PlatformProviderUtils.declaredToolchainInfo(target);
        if (toolchainInfo == null) {
          throw new RegisteredToolchainsFunctionException(
              new InvalidToolchainLabelException(toolchainLabel), Transience.PERSISTENT);
        }
        toolchains.add(toolchainInfo);
      } catch (ConfiguredValueCreationException e) {
        throw new RegisteredToolchainsFunctionException(
            new InvalidToolchainLabelException(toolchainLabel, e), Transience.PERSISTENT);
      }
    }

    if (valuesMissing) {
      return null;
    }
    return toolchains.build();
  }

  /**
   * Used to indicate that the given {@link Label} represents a {@link ConfiguredTarget} which is
   * not a valid {@link DeclaredToolchainInfo} provider.
   */
  public static final class InvalidToolchainLabelException extends ToolchainException {

    public InvalidToolchainLabelException(Label invalidLabel) {
      super(
          formatMessage(
              invalidLabel.getCanonicalForm(),
              "target does not provide the DeclaredToolchainInfo provider"));
    }

    public InvalidToolchainLabelException(TargetPatternUtil.InvalidTargetPatternException e) {
      this(e.getInvalidPattern(), e.getTpe());
    }

    public InvalidToolchainLabelException(String invalidPattern, TargetParsingException e) {
      super(formatMessage(invalidPattern, e.getMessage()), e);
    }

    public InvalidToolchainLabelException(Label invalidLabel, ConfiguredValueCreationException e) {
      super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e);
    }

    public InvalidToolchainLabelException(Label invalidLabel, InvalidConfigurationException e) {
      super(formatMessage(invalidLabel.getCanonicalForm(), e.getMessage()), e);
    }

    @Override
    protected Code getDetailedCode() {
      return Code.INVALID_TOOLCHAIN;
    }

    private static String formatMessage(String invalidPattern, String reason) {
      return String.format("invalid registered toolchain '%s': %s", invalidPattern, reason);
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * #compute}.
   */
  public static class RegisteredToolchainsFunctionException extends SkyFunctionException {

    public RegisteredToolchainsFunctionException(
        InvalidToolchainLabelException cause, Transience transience) {
      super(cause, transience);
    }

    public RegisteredToolchainsFunctionException(
        ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
