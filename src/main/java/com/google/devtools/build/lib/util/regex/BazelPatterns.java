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

/**
 * Fixed ASCII validators compiled to matcher bytecode by Reggie's annotation processor.
 *
 * <p>These use full-string matching. Anchors are unnecessary, and explicit ASCII classes preserve
 * Java's default character-class semantics without relying on Unicode or inline flag handling. No
 * runtime pattern compilation or JDK fallback is needed for these validators.
 */
public abstract class BazelPatterns implements ReggiePatterns {
  private static final BazelPatterns INSTANCE = Reggie.patterns(BazelPatterns.class);

  @RegexPattern("[A-Za-z_][A-Za-z0-9_]*")
  protected abstract ReggieMatcher ruleName();

  public static boolean isRuleName(String input) {
    return INSTANCE.ruleName().matches(input);
  }

  @RegexPattern("[0-9]+")
  protected abstract ReggieMatcher unitlessDuration();

  public static boolean isUnitlessDuration(String input) {
    return INSTANCE.unitlessDuration().matches(input);
  }

  @RegexPattern("[_a-z0-9+\\-\\.]*")
  protected abstract ReggieMatcher cppFeatureName();

  public static boolean isCppFeatureName(String input) {
    return INSTANCE.cppFeatureName().matches(input);
  }

  @RegexPattern("[A-Za-z0-9_][A-Za-z0-9_.-]*")
  protected abstract ReggieMatcher repositoryViewName();

  public static boolean isRepositoryViewName(String input) {
    return INSTANCE.repositoryViewName().matches(input);
  }

  @RegexPattern("[0-9a-f]{40}")
  protected abstract ReggieMatcher fullGitCommit();

  public static boolean isFullGitCommit(String input) {
    return INSTANCE.fullGitCommit().matches(input);
  }

  @RegexPattern("[0-9a-fA-F]{7,64}")
  protected abstract ReggieMatcher gitCommit();

  public static boolean isGitCommit(String input) {
    return INSTANCE.gitCommit().matches(input);
  }

  @RegexPattern("(?:[<>]=?|-)[0-9]+\\.[0-9]+\\.[0-9]+")
  protected abstract ReggieMatcher bazelCompatibilityVersion();

  public static boolean isBazelCompatibilityVersion(String input) {
    return INSTANCE.bazelCompatibilityVersion().matches(input);
  }
}
