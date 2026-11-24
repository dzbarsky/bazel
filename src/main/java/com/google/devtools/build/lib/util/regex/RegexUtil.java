// Copyright 2025 The Bazel Authors. All rights reserved.
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

import static com.google.devtools.build.lib.util.StringEncoding.internalToUnicode;
import static com.google.devtools.build.lib.util.StringEncoding.unicodeToInternal;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** A utility class for regex-related operations. */
public final class RegexUtil {
  /**
   * Returns a {@link Predicate} that matches the input string (in internal encoding according to
   * {@link com.google.devtools.build.lib.util.StringEncoding}) against the regex pattern with the
   * same semantics as {@link java.util.regex.Matcher#matches()}, but more optimized and as if the
   * pattern was compiled with {@link Pattern#DOTALL}.
   */
  public static Predicate<String> asOptimizedMatchingPredicate(Pattern regexPattern) {
    String pattern = regexPattern.pattern();
    if (pattern.contains("|") || pattern.contains("\\Q")) {
      // Alternations make it so that appending "$" may not force a match to the end. While we could
      // wrap the pattern in "(?:" + pattern + ")$", that would no longer be amenable to the regex
      // engine's optimization pass exploited below - it only applies when the first node is a
      // literal, not a group.
      // Unmatched \Q...\E sequences can have the same effect.
      return asMatchingPredicate(regexPattern);
    }
    // If the pattern matches a literal string, optimize to an equals call.
    if (isLiteralWithEscapedDots(pattern)) {
      return unicodeToInternal(pattern.replace("\\.", "."))::equals;
    }
    // Recognize a pattern that starts with ".*" and drop it so that the engine sees the next part
    // of the pattern as the beginning and potentially optimizes the literal search.
    // We don't apply the same transformation to the end of the pattern since it is harder to
    // determine if a ".*" suffix is safe to drop due to backslash escapes.
    String suffixPattern = extractSuffix(pattern);
    if (suffixPattern == null) {
      return asMatchingPredicate(regexPattern);
    }
    // An empty suffix implies the regex is equivalent to ".*", which matches any input string.
    if (suffixPattern.isEmpty()) {
      return s -> true;
    }
    // If the pattern matches a literal suffix, optimize to a string suffix
    // match, which is by far the fastest way to match.
    if (isLiteralWithEscapedDots(suffixPattern)) {
      String literalSuffix = unicodeToInternal(suffixPattern.replace("\\.", "."));
      return s -> s.endsWith(literalSuffix);
    }
    // If the pattern matches a literal anywhere in the string (".*literal.*"), optimize to a
    // substring search. The part before the trailing ".*" has to be a complete literal, which
    // rules out a ".*" whose dot is escaped; the patterns are matched as if compiled with DOTALL,
    // so the trailing ".*" matches whatever follows.
    if (suffixPattern.endsWith(".*")) {
      String infixPattern = suffixPattern.substring(0, suffixPattern.length() - 2);
      if (isLiteralWithEscapedDots(infixPattern)) {
        String literalInfix = unicodeToInternal(infixPattern.replace("\\.", "."));
        return s -> s.contains(literalInfix);
      }
    }
    // Turn the "match" pattern into an equivalent "find" pattern, since these are the only ones
    // that benefit from the Boyer-Moore optimization in the Java regex engine.
    // https://github.com/openjdk/jdk/blob/50dced88ff1aed23bb4c8fe9e4a08e6cc200b897/src/java.base/share/classes/java/util/regex/Pattern.java#L1959-L1969
    if (BazelPatterns.mayEnableRegexComments(suffixPattern)) {
      return asMatchingPredicate(regexPattern);
    }
    ReggiePattern compiled =
        ReggiePattern.compile(Pattern.compile(suffixPattern + "\\z", Pattern.DOTALL));
    return s -> compiled.find(internalToUnicode(s));
  }

  // Recognize literals and escaped dots. A backslash can occur only as part of "\\.".
  static boolean isLiteralWithEscapedDots(String pattern) {
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        if (++i == pattern.length() || pattern.charAt(i) != '.') {
          return false;
        }
      } else if ("[](){}^$|*+?.".indexOf(c) >= 0) {
        return false;
      }
    }
    return true;
  }

  /** Returns the suffix after optional anchors and ".*", or null if this is not a suffix match. */
  @Nullable
  static String extractSuffix(String pattern) {
    int start = 0;
    while (start < pattern.length()) {
      if (pattern.charAt(start) == '^') {
        start++;
      } else if (pattern.startsWith("\\A", start)) {
        start += 2;
      } else {
        break;
      }
    }
    if (!pattern.startsWith(".*", start)) {
      return null;
    }
    start += 2;
    if (start == pattern.length()) {
      return "";
    }
    char first = pattern.charAt(start);
    if (first == '?' || first == '+') {
      return null;
    }
    // The original extractor's first suffix character was [^?+], followed by a non-DOTALL
    // ".*". Keep its treatment of supplementary characters and all five line terminators.
    for (int i = start + Character.charCount(pattern.codePointAt(start));
        i < pattern.length();
        i++) {
      char c = pattern.charAt(i);
      if (c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029') {
        return null;
      }
    }
    return pattern.substring(start);
  }

  private static Predicate<String> asMatchingPredicate(Pattern pattern) {
    ReggiePattern compiled = ReggiePattern.compile(pattern);
    return s -> compiled.matches(internalToUnicode(s));
  }

  private RegexUtil() {}
}
