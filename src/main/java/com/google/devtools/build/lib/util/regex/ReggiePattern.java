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
 * A reusable boolean regex backed by Reggie's bytecode compiler.
 *
 * <p>Inputs and patterns use Unicode strings, not Bazel's internal string encoding. The JDK
 * validates syntax and supplies a compatibility fallback for flags or constructs Reggie cannot
 * compile and for matches that exceed Reggie's execution budget. Capture extraction, replacement,
 * and splitting continue to use the JDK API.
 */
public final class ReggiePattern {
  private static final Pattern INLINE_FLAGS = Pattern.compile("\\(\\?[idmsuxU-]");
  private final Pattern pattern;
  // DynamicCodec serializes the Pattern, then reconstructs the runtime-only engine on first use.
  @Nullable private transient volatile Engine engine;

  private record Engine(@Nullable ThreadLocal<ReggieMatcher> matchers) {}

  private ReggiePattern(Pattern pattern, Engine engine) {
    this.pattern = pattern;
    this.engine = engine;
  }

  public static ReggiePattern compile(String pattern) {
    return compile(Pattern.compile(pattern));
  }

  public static ReggiePattern compile(Pattern pattern) {
    return new ReggiePattern(pattern, compileEngine(pattern));
  }

  private static Engine compileEngine(Pattern pattern) {
    String regex = pattern.pattern();
    // Reggie 0.5.0 differs from the JDK on anchors and character-class intersections.
    // Inline flags also cannot be reapplied using Pattern.flags(): those flags describe the end
    // of the pattern, whereas a mid-pattern flag must not affect the preceding expression.
    if (!isAscii(regex)
        || regex.indexOf('^') >= 0
        || regex.indexOf('$') >= 0
        || regex.contains("&&")
        || INLINE_FLAGS.matcher(regex).find()) {
      return new Engine(null);
    }
    final int flags;
    try {
      flags = JdkPatternCompatibility.toReggieFlags(pattern.pattern(), pattern.flags());
      // Compile eagerly so unsupported patterns select the fallback before parallel evaluation.
      Reggie.compile(pattern.pattern(), flags);
    } catch (IllegalArgumentException | UnsupportedPatternException e) {
      return new Engine(null);
    }
    // NFA-backed Reggie matchers carry mutable scratch state. Never share one between workers.
    return new Engine(ThreadLocal.withInitial(() -> Reggie.compile(pattern.pattern(), flags)));
  }

  private static boolean isAscii(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 0x7f) {
        return false;
      }
    }
    return true;
  }

  private static boolean isCompatibleInput(String input) {
    // Reggie uses UTF-16 code units where the JDK matches Unicode code points, and its dot
    // handling does not exclude all of the JDK's line terminators (notably carriage return).
    return isAscii(input) && input.indexOf('\r') < 0;
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
    ThreadLocal<ReggieMatcher> matchers = engine().matchers();
    if (matchers != null && isCompatibleInput(input)) {
      try {
        return matchers.get().matches(input);
      } catch (MatchBudgetExceededException e) {
        // A valid JDK pattern must not become an internal error on a particular input.
      }
    }
    return pattern.matcher(input).matches();
  }

  public boolean find(String input) {
    ThreadLocal<ReggieMatcher> matchers = engine().matchers();
    if (matchers != null && isCompatibleInput(input)) {
      try {
        return matchers.get().find(input);
      } catch (MatchBudgetExceededException e) {
        // Preserve the existing Java regex behavior when Reggie's bounded engine gives up.
      }
    }
    return pattern.matcher(input).find();
  }
}
