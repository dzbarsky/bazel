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
import com.datadoghq.reggie.UnsupportedPatternException;
import com.datadoghq.reggie.compat.JdkPatternCompatibility;
import com.datadoghq.reggie.runtime.MatchBudgetExceededException;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * A reusable boolean regex that can opt in to Reggie's bytecode compiler.
 *
 * <p>Inputs and patterns use Unicode strings, not Bazel's internal string encoding. The JDK
 * validates syntax and supplies a compatibility fallback for flags or constructs Reggie cannot
 * compile and for matches that exceed Reggie's execution budget. Capture extraction, replacement,
 * and splitting continue to use the JDK API.
 */
public final class ReggiePattern {
  private final Pattern pattern;
  // DynamicCodec serializes the Pattern, then reconstructs the runtime-only engine on first use.
  @Nullable private transient volatile Engine engine;

  private record Engine(@Nullable ThreadLocal<ReggieMatcher> matchers) {
    private static final Engine JDK = new Engine(null);
  }

  private ReggiePattern(Pattern pattern) {
    this.pattern = pattern;
    this.engine = compileEngine(pattern);
  }

  public static ReggiePattern compile(String pattern) {
    return compile(Pattern.compile(pattern));
  }

  public static ReggiePattern compile(Pattern pattern) {
    return new ReggiePattern(pattern);
  }

  private static Engine compileEngine(Pattern pattern) {
    if (!BazelPatterns.REGGIE_ENABLED) {
      return Engine.JDK;
    }
    String regex = pattern.pattern();
    // Reggie 0.5.0 differs from the JDK on anchors and character-class intersections.
    // Inline flags also cannot be reapplied using Pattern.flags(): those flags describe the end
    // of the pattern, whereas a mid-pattern flag must not affect the preceding expression.
    if (!isCompatibleText(regex)
        || regex.indexOf('^') >= 0
        || regex.indexOf('$') >= 0
        || regex.contains("&&")
        || BazelPatterns.hasInlineRegexFlags(regex)) {
      return Engine.JDK;
    }
    final int flags;
    try {
      flags = JdkPatternCompatibility.toReggieFlags(regex, pattern.flags());
      // Compile eagerly so unsupported patterns select the fallback before parallel evaluation.
      Reggie.compile(regex, flags);
    } catch (IllegalArgumentException | UnsupportedPatternException e) {
      return Engine.JDK;
    }
    // NFA-backed Reggie matchers carry mutable scratch state. Never share one between workers.
    return new Engine(ThreadLocal.withInitial(() -> Reggie.compile(regex, flags)));
  }

  private static boolean isCompatibleText(String text) {
    // Reggie uses UTF-16 code units where the JDK matches Unicode code points. Its dot also
    // does not exclude carriage returns the way the JDK does.
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c > 0x7f || c == '\r') {
        return false;
      }
    }
    return true;
  }

  private Engine engine() {
    Engine result = engine;
    if (result == null) {
      synchronized (this) {
        result = engine;
        if (result == null) {
          result = compileEngine(pattern);
          engine = result;
        }
      }
    }
    return result;
  }

  public String pattern() {
    return pattern.pattern();
  }

  public boolean matches(String input) {
    return evaluate(input, /* find= */ false);
  }

  public boolean find(String input) {
    return evaluate(input, /* find= */ true);
  }

  boolean usesReggie() {
    return engine().matchers() != null;
  }

  private boolean evaluate(String input, boolean find) {
    ThreadLocal<ReggieMatcher> matchers = engine().matchers();
    if (matchers != null && isCompatibleText(input)) {
      try {
        ReggieMatcher matcher = matchers.get();
        return find ? matcher.find(input) : matcher.matches(input);
      } catch (MatchBudgetExceededException e) {
        // A valid JDK pattern must not become an internal error on a particular input.
      }
    }
    var matcher = pattern.matcher(input);
    return find ? matcher.find() : matcher.matches();
  }
}
