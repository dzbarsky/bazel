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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.datadoghq.reggie.Reggie;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Compares the generated validators with the original JDK expressions at their call sites. */
@RunWith(TestParameterInjector.class)
public class BazelPatternsTest {
  private enum Validator {
    RULE_NAME("[A-Za-z_][A-Za-z0-9_]*", BazelPatterns::isRuleName, "my_rule_1"),
    UNITLESS_DURATION("^[0-9]+$", BazelPatterns::isUnitlessDuration, "123"),
    CPP_FEATURE_NAME("^[_a-z0-9+\\-\\.]*$", BazelPatterns::isCppFeatureName, "a_b+1.2-3"),
    REPOSITORY_VIEW_NAME(
        "[A-Za-z0-9_][A-Za-z0-9_.-]*", BazelPatterns::isRepositoryViewName, "repo.name-1"),
    FULL_GIT_COMMIT("[0-9a-f]{40}", BazelPatterns::isFullGitCommit, "a".repeat(40)),
    GIT_COMMIT("^[0-9a-fA-F]{7,64}$", BazelPatterns::isGitCommit, "012AbCd"),
    BAZEL_COMPATIBILITY_VERSION(
        "(>|<|-|<=|>=)(\\d+\\.){2}\\d+", BazelPatterns::isBazelCompatibilityVersion, ">=1.2.3");

    private final Pattern jdk;
    private final Predicate<String> generated;
    private final String validInput;

    Validator(String regex, Predicate<String> generated, String validInput) {
      this.jdk = Pattern.compile(regex);
      this.generated = generated;
      this.validInput = validInput;
    }

    void check(String input) {
      if (generated.test(input) != jdk.matcher(input).matches()) {
        fail(name() + " disagrees with JDK on " + input.codePoints().boxed().toList());
      }
    }
  }

  @Test
  public void unicodeAndBoundariesAgreeWithJdk(@TestParameter Validator validator) {
    assertThat(validator.generated.test(validator.validInput)).isTrue();
    for (String input :
        List.of(
            "",
            "_",
            "-",
            "=1.2.3",
            "-=1.2.3",
            "<1.2.3",
            "<=1.2.3",
            ">1.2.3",
            ">=1.2.3",
            "-1.2.3",
            ">=01.002.0003",
            ">=1.2",
            ">=1.2.3.4",
            ">=١.٢.٣",
            "é",
            "你好",
            "😀",
            "a".repeat(6),
            "a".repeat(7),
            "a".repeat(39),
            "a".repeat(40),
            "a".repeat(41),
            "a".repeat(64),
            "a".repeat(65),
            "F".repeat(40),
            "0".repeat(1000))) {
      validator.check(input);
    }
    // Cover every UTF-16 code unit, including isolated surrogates and all line terminators,
    // both by itself and adjacent to a known matching input.
    for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
      String character = String.valueOf((char) c);
      validator.check(character);
      validator.check(character + validator.validInput);
      validator.check(validator.validInput + character);
    }
  }

  @Test
  public void randomizedInputsAgreeWithJdk(@TestParameter Validator validator) {
    Random random = new Random(0);
    String alphabet = "abcXYZ0123456789_-+.<>=! \t\r\n";
    for (int i = 0; i < 10000; i++) {
      StringBuilder input = new StringBuilder();
      for (int remaining = random.nextInt(70); remaining > 0; remaining--) {
        input.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      validator.check(input.toString());
    }
  }

  @Test
  public void sharedGeneratedMatchersAreSafeForParallelValidation() throws Exception {
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < 1000; i++) {
        tasks.add(
            () -> {
              for (Validator validator : Validator.values()) {
                if (!validator.generated.test(validator.validInput)
                    || validator.generated.test(validator.validInput + "\n")) {
                  return false;
                }
              }
              return true;
            });
      }
      for (var result : executor.invokeAll(tasks)) {
        assertThat(result.get()).isTrue();
      }
    }
  }

  @Test
  public void validatorsDoNotPopulateRuntimeCompilationCache() {
    for (Validator validator : Validator.values()) {
      assertThat(validator.generated.test(validator.validInput)).isTrue();
    }
    assertThat(Reggie.cacheSize()).isEqualTo(0);
  }
}
