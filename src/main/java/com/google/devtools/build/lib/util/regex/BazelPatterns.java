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
package com.google.devtools.build.lib.util.regex;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggiePatterns;
import com.datadoghq.reggie.annotations.RegexPattern;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.regex.Pattern;

/**
 * Fixed validators and regex-syntax detectors, optionally compiled by Reggie.
 *
 * <p>Validators use full-string matching and explicit ASCII classes. Syntax detectors search for
 * literal flag prefixes in pattern strings. The JDK is the default engine. Reggie's generated
 * matchers are loaded only when the server opts in.
 */
public abstract class BazelPatterns implements ReggiePatterns {
  static final boolean REGGIE_ENABLED = Boolean.getBoolean("bazel.experimental.reggie");

  private static final class Generated {
    private static final BazelPatterns INSTANCE = Reggie.patterns(BazelPatterns.class);
  }

  private static final String RULE_NAME = "[A-Za-z_][A-Za-z0-9_]*";
  private static final String UNITLESS_DURATION = "[0-9]+";
  private static final String CPP_FEATURE_NAME = "[_a-z0-9+\\-\\.]*";
  private static final String REPOSITORY_VIEW_NAME = "[A-Za-z0-9_][A-Za-z0-9_.-]*";
  private static final String FULL_GIT_COMMIT = "[0-9a-f]{40}";
  private static final String GIT_COMMIT = "[0-9a-fA-F]{7,64}";
  private static final String BAZEL_COMPATIBILITY_VERSION = "(?:[<>]=?|-)[0-9]+\\.[0-9]+\\.[0-9]+";
  private static final String USER_PROVIDED_REPOSITORY_NAME = "[a-zA-Z0-9][-.A-Za-z0-9_]*";
  private static final String MODULE_NAME = "[a-z](?:[a-z0-9._-]*[a-z0-9])?";
  private static final String COMMENTS_FLAG = "\\(\\?[a-zA-Z-]*x";
  private static final String INLINE_REGEX_FLAGS = "\\(\\?[idmsuxU-]";

  private static final class Jdk {
    private static final Pattern RULE_NAME = Pattern.compile(BazelPatterns.RULE_NAME);
    private static final Pattern UNITLESS_DURATION =
        Pattern.compile(BazelPatterns.UNITLESS_DURATION);
    private static final Pattern CPP_FEATURE_NAME = Pattern.compile(BazelPatterns.CPP_FEATURE_NAME);
    private static final Pattern REPOSITORY_VIEW_NAME =
        Pattern.compile(BazelPatterns.REPOSITORY_VIEW_NAME);
    private static final Pattern FULL_GIT_COMMIT = Pattern.compile(BazelPatterns.FULL_GIT_COMMIT);
    private static final Pattern GIT_COMMIT = Pattern.compile(BazelPatterns.GIT_COMMIT);
    private static final Pattern BAZEL_COMPATIBILITY_VERSION =
        Pattern.compile(BazelPatterns.BAZEL_COMPATIBILITY_VERSION);
    private static final Pattern USER_PROVIDED_REPOSITORY_NAME =
        Pattern.compile(BazelPatterns.USER_PROVIDED_REPOSITORY_NAME);
    private static final Pattern MODULE_NAME = Pattern.compile(BazelPatterns.MODULE_NAME);
    private static final Pattern COMMENTS_FLAG = Pattern.compile(BazelPatterns.COMMENTS_FLAG);
    private static final Pattern INLINE_REGEX_FLAGS =
        Pattern.compile(BazelPatterns.INLINE_REGEX_FLAGS);
  }

  @RegexPattern(RULE_NAME)
  protected abstract ReggieMatcher ruleName();

  public static boolean isRuleName(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.ruleName().matches(input)
        : Jdk.RULE_NAME.matcher(input).matches();
  }

  @RegexPattern(UNITLESS_DURATION)
  protected abstract ReggieMatcher unitlessDuration();

  public static boolean isUnitlessDuration(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.unitlessDuration().matches(input)
        : Jdk.UNITLESS_DURATION.matcher(input).matches();
  }

  @RegexPattern(CPP_FEATURE_NAME)
  protected abstract ReggieMatcher cppFeatureName();

  public static boolean isCppFeatureName(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.cppFeatureName().matches(input)
        : Jdk.CPP_FEATURE_NAME.matcher(input).matches();
  }

  @RegexPattern(REPOSITORY_VIEW_NAME)
  protected abstract ReggieMatcher repositoryViewName();

  public static boolean isRepositoryViewName(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.repositoryViewName().matches(input)
        : Jdk.REPOSITORY_VIEW_NAME.matcher(input).matches();
  }

  @RegexPattern(FULL_GIT_COMMIT)
  protected abstract ReggieMatcher fullGitCommit();

  public static boolean isFullGitCommit(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.fullGitCommit().matches(input)
        : Jdk.FULL_GIT_COMMIT.matcher(input).matches();
  }

  @RegexPattern(GIT_COMMIT)
  protected abstract ReggieMatcher gitCommit();

  public static boolean isGitCommit(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.gitCommit().matches(input)
        : Jdk.GIT_COMMIT.matcher(input).matches();
  }

  @RegexPattern(BAZEL_COMPATIBILITY_VERSION)
  protected abstract ReggieMatcher bazelCompatibilityVersion();

  public static boolean isBazelCompatibilityVersion(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.bazelCompatibilityVersion().matches(input)
        : Jdk.BAZEL_COMPATIBILITY_VERSION.matcher(input).matches();
  }

  @RegexPattern(USER_PROVIDED_REPOSITORY_NAME)
  protected abstract ReggieMatcher userProvidedRepositoryName();

  public static boolean isUserProvidedRepositoryName(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.userProvidedRepositoryName().matches(input)
        : Jdk.USER_PROVIDED_REPOSITORY_NAME.matcher(input).matches();
  }

  @RegexPattern(MODULE_NAME)
  protected abstract ReggieMatcher moduleName();

  public static boolean isModuleName(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.moduleName().matches(input)
        : Jdk.MODULE_NAME.matcher(input).matches();
  }

  @RegexPattern(COMMENTS_FLAG)
  protected abstract ReggieMatcher commentsFlag();

  public static boolean mayEnableRegexComments(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.commentsFlag().find(input)
        : Jdk.COMMENTS_FLAG.matcher(input).find();
  }

  @RegexPattern(INLINE_REGEX_FLAGS)
  protected abstract ReggieMatcher inlineRegexFlags();

  public static boolean hasInlineRegexFlags(String input) {
    return REGGIE_ENABLED
        ? Generated.INSTANCE.inlineRegexFlags().find(input)
        : Jdk.INLINE_REGEX_FLAGS.matcher(input).find();
  }
}
