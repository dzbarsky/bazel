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

import static org.junit.Assert.fail;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Differential checks against the original regexes for regex-syntax inspection. */
@RunWith(JUnit4.class)
public class RegexSyntaxTest {
  private static final Pattern LITERAL = Pattern.compile("(?:[^\\[\\](){}^$|*+?.\\\\]|\\\\\\.)*");
  private static final Pattern SUFFIX = Pattern.compile("(?:\\^|\\\\A)*\\.\\*(?<suffix>[^?+].*)?");
  private static final Pattern COMMENTS = Pattern.compile("\\(\\?[a-zA-Z-]*x");
  private static final Pattern INLINE_FLAGS = Pattern.compile("\\(\\?[idmsuxU-]");

  private static void check(String input) {
    Matcher suffix = SUFFIX.matcher(input);
    String expectedSuffix = suffix.matches() ? Objects.toString(suffix.group("suffix"), "") : null;
    if (RegexUtil.isLiteralWithEscapedDots(input) != LITERAL.matcher(input).matches()
        || !Objects.equals(RegexUtil.extractSuffix(input), expectedSuffix)
        || BazelPatterns.mayEnableRegexComments(input) != COMMENTS.matcher(input).find()
        || BazelPatterns.hasInlineRegexFlags(input) != INLINE_FLAGS.matcher(input).find()) {
      fail("Regex helper disagrees with JDK for " + input.codePoints().boxed().toList());
    }
  }

  @Test
  public void everyUtf16CodeUnitAndBoundaryAgreesWithJdk() {
    for (int c = Character.MIN_VALUE; c <= Character.MAX_VALUE; c++) {
      String character = String.valueOf((char) c);
      for (String input :
          List.of(
              character,
              ".*" + character,
              ".*a" + character,
              ".*" + character + "a",
              "^^\\A.*" + character,
              "\\" + character,
              "(?" + character + "x",
              character + "(?x)",
              "(?x)" + character)) {
        check(input);
      }
    }
    for (String input :
        List.of(
            "",
            ".*",
            ".*?",
            ".*+",
            "^^\\A.*",
            ".*😀",
            ".*😀a",
            ".*a😀",
            "\\.",
            "\\\\.",
            "(?x:foo)",
            "(?-x)",
            "(?ix-m:foo)",
            ".*\r\n")) {
      check(input);
    }
  }

  @Test
  public void randomizedSyntaxAgreesWithJdk() {
    Random random = new Random(0);
    String alphabet = "abcAix-\\.^$|?+*[](){}\r\n";
    for (int i = 0; i < 20000; i++) {
      StringBuilder input = new StringBuilder();
      for (int remaining = random.nextInt(32); remaining > 0; remaining--) {
        input.append(
            random.nextBoolean()
                ? alphabet.charAt(random.nextInt(alphabet.length()))
                : (char) random.nextInt(Character.MAX_VALUE + 1));
      }
      check(input.toString());
      check("^^\\A.*" + input);
      check("(?" + input + "x");
    }
  }
}
