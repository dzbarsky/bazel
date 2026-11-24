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
import static org.junit.Assert.assertThrows;

import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Differential coverage for the JDK-to-Reggie boundary used by Bazel's parallel filters. */
@RunWith(TestParameterInjector.class)
public class ReggiePatternTest {
  @Test
  public void agreesWithJdk(
      @TestParameter({
            "",
            "foo",
            ".*",
            "foo|bar",
            "(foo|bar)+",
            "(a|ab)*b",
            "a.*b",
            "a.*?b",
            "^foo$",
            "foo$",
            "foo\\z",
            "\\Afoo",
            ".",
            "[a-z]+",
            "[^a]+",
            "\\w+",
            "\\bfoo\\b",
            "\\d+",
            "\\s+",
            "\\p{L}+",
            "(?i)foo",
            "(?i)é",
            "(?s)f.*o",
            "(?m)^foo$",
            "(?x) foo # comment",
            "(?x: foo # comment\n)",
            "(?=foo)foo",
            "(?<=a)b",
            "(foo)\\1",
            "(?>foo|bar)",
            "a++b",
            "\\Qfoo.bar\\E",
            "é+",
            "😀+",
            "[a-z&&[^aeiou]]+",
            "a(?i)b",
            "(?i:a)b",
            "(?i)foo(?-i)bar",
            "^",
            "^\\z",
            "foo$|bar",
            "\\b",
            "\\B",
            "\\D+",
            "\\W+",
            "\\S+",
            "a{2,4}",
            "a?b",
            "(a|aa)*"
          })
          String regex,
      @TestParameter({
            "",
            "foo",
            "bar",
            "foobar",
            "foofoo",
            "barfoo",
            "ab",
            "aab",
            "abab",
            "ababab",
            "foo\n",
            "foo\r",
            "foo\r\n",
            "foo\u0085",
            "foo\u2028",
            "foo\u2029",
            "\nfoo\n",
            "foo.bar",
            "f\no",
            "FOO",
            "é",
            "É",
            "你好",
            "😀",
            "😀😀",
            "123",
            " ",
            "a foo b",
            "abc foo xyz",
            "bcdf",
            "aB",
            "Ab",
            "aaaab",
            "foobAr",
            "\t",
            "\u000b",
            "\f"
          })
          String input,
      @TestParameter({"0", "2", "8", "32", "16", "64", "256"}) int flags) {
    Pattern jdk = Pattern.compile(regex, flags);
    ReggiePattern reggie = ReggiePattern.compile(jdk);
    assertThat(reggie.matches(input)).isEqualTo(jdk.matcher(input).matches());
    assertThat(reggie.find(input)).isEqualTo(jdk.matcher(input).find());
  }

  @Test
  @SuppressWarnings("InvalidPatternSyntax") // Deliberately invalid input exercises the diagnostic.
  public void invalidSyntaxRetainsJdkDiagnostic() {
    PatternSyntaxException expected =
        assertThrows(PatternSyntaxException.class, () -> Pattern.compile("["));
    PatternSyntaxException actual =
        assertThrows(PatternSyntaxException.class, () -> ReggiePattern.compile("["));
    assertThat(actual.getMessage()).isEqualTo(expected.getMessage());
  }

  @Test
  public void sharedPatternCanBeUsedByConcurrentWorkers() throws Exception {
    ReggiePattern pattern = ReggiePattern.compile("(a|ab)*b");
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < 1000; i++) {
        tasks.add(() -> pattern.matches("ababababab") && !pattern.matches("ababababa"));
      }
      for (var result : executor.invokeAll(tasks)) {
        assertThat(result.get()).isTrue();
      }
    }
  }
}
