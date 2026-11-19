// Copyright 2024 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.BazelStarlarkEnvironment;
import com.google.devtools.build.lib.packages.DotBazelFileSyntaxChecker;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.StarlarkUtil;
import java.util.HashSet;
import java.util.Set;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.StringLiteral;
import net.starlark.java.syntax.SyntaxError;

/**
 * Represents a compiled MODULE.bazel file, ready to be executed on a {@link StarlarkThread}. It's
 * been successfully checked for syntax errors.
 *
 * <p>Use the {@link #parseAndCompile} factory method instead of directly instantiating this record.
 */
public record CompiledModuleFile(
    ModuleFile moduleFile,
    Program program,
    Module predeclaredEnv,
    ImmutableList<IncludeStatement> includeStatements) {
  public static final String INCLUDE_IDENTIFIER = "include";
  public static final String TRY_INCLUDE_IDENTIFIER = "try_include";

  record IncludeStatement(
      String directive, String includeLabel, boolean devDependency, Location location) {
    boolean optional() {
      return directive.equals(TRY_INCLUDE_IDENTIFIER);
    }
  }

  /** Parses and compiles a given module file, checking it for syntax errors. */
  public static CompiledModuleFile parseAndCompile(
      ModuleFile moduleFile,
      ModuleKey moduleKey,
      StarlarkSemantics starlarkSemantics,
      BazelStarlarkEnvironment starlarkEnv,
      ExtendedEventHandler eventHandler)
      throws ExternalDepsException {
    ParserInput parserInput;
    try {
      parserInput =
          StarlarkUtil.createParserInput(
              moduleFile.getContent(),
              moduleFile.getLocation(),
              starlarkSemantics.get(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
              eventHandler);
    } catch (
        @SuppressWarnings("UnusedException") // createParserInput() reports its own error message
        StarlarkUtil.InvalidUtf8Exception e) {
      throw ExternalDepsException.withMessage(
          Code.BAD_MODULE, "error reading MODULE.bazel file for %s", moduleKey);
    }
    StarlarkFile starlarkFile = StarlarkFile.parse(parserInput);
    if (!starlarkFile.ok()) {
      Event.replayEventsOn(eventHandler, starlarkFile.errors());
      throw ExternalDepsException.withMessage(
          Code.BAD_MODULE, "error parsing MODULE.bazel file for %s", moduleKey);
    }
    try {
      ImmutableList<IncludeStatement> includeStatements = checkModuleFileSyntax(starlarkFile);
      Module predeclaredEnv =
          Module.withPredeclared(starlarkSemantics, starlarkEnv.getModuleBazelEnv());
      Program program = Program.compileFile(starlarkFile, predeclaredEnv);
      return new CompiledModuleFile(moduleFile, program, predeclaredEnv, includeStatements);
    } catch (SyntaxError.Exception e) {
      Event.replayEventsOn(eventHandler, e.errors());
      throw ExternalDepsException.withMessage(
          Code.BAD_MODULE, "syntax error in MODULE.bazel file for %s", moduleKey);
    }
  }

  private static class SyntaxChecker extends DotBazelFileSyntaxChecker {

    final ImmutableList.Builder<IncludeStatement> includeStatements = ImmutableList.builder();

    // MODULE.bazel can bind either directive name to an ordinary variable, e.g. an extension proxy.
    private final Set<String> shadowedDirectives = new HashSet<>();

    SyntaxChecker() {
      super("MODULE.bazel files", /* canLoadBzl= */ false, /* allowLiteralStarStarArgs= */ true);
      // Directive names in keyword arguments and object fields are ordinary identifiers.
      this.skipNonSymbolIdentifiers = true;
    }

    private boolean isIncludeDirective(String name) {
      return (name.equals(INCLUDE_IDENTIFIER) || name.equals(TRY_INCLUDE_IDENTIFIER))
          && !shadowedDirectives.contains(name);
    }

    @Override
    public void visit(ExpressionStatement node) {
      // We can assume this statement isn't nested in any block, since we don't allow
      // `if`/`def`/`for` in MODULE.bazel.
      if (node.getExpression() instanceof CallExpression call
          && call.getFunction() instanceof Identifier id
          && isIncludeDirective(id.getName())) {
        if (!call.getArguments().isEmpty()
            && call.getArguments().getFirst() instanceof Argument.Positional pos
            && pos.getValue() instanceof StringLiteral str
            && (call.getArguments().size() == 1
                || (call.getArguments().size() == 2
                    && call.getArguments().get(1) instanceof Argument.Keyword keyword
                    && keyword.getName().equals("dev_dependency")
                    && keyword.getValue() instanceof Identifier devDependency
                    && (devDependency.getName().equals("True")
                        || devDependency.getName().equals("False"))))) {
          boolean devDependency =
              call.getArguments().size() == 2
                  && ((Identifier) call.getArguments().get(1).getValue()).getName().equals("True");
          includeStatements.add(
              new IncludeStatement(
                  id.getName(), str.getValue(), devDependency, call.getStartLocation()));
          // Nothing else to check, we can stop visiting sub-nodes now.
          return;
        }
        error(
            node.getStartLocation(),
            "the `"
                + id.getName()
                + "` directive MUST be called with one positional string literal argument "
                + "and, optionally, `dev_dependency` as a boolean literal");
        return;
      }
      super.visit(node);
    }

    @Override
    public void visit(AssignmentStatement node) {
      visit(node.getRHS());
      if (node.getLHS() instanceof Identifier id && isIncludeDirective(id.getName())) {
        shadowedDirectives.add(id.getName());
      } else {
        visit(node.getLHS());
      }
    }

    @Override
    public void visit(Identifier node) {
      if (isIncludeDirective(node.getName())) {
        error(
            node.getStartLocation(),
            "the `" + node.getName() + "` directive MUST be called directly at the top-level");
      }
      super.visit(node);
    }
  }

  /**
   * Checks MODULE.bazel syntax and collects top-level {@code include} and {@code try_include}
   * calls. Each call takes a string literal and an optional boolean literal {@code dev_dependency}
   * keyword argument. Assigning to either name shadows that directive for the rest of the file.
   */
  @VisibleForTesting
  static ImmutableList<IncludeStatement> checkModuleFileSyntax(StarlarkFile starlarkFile)
      throws SyntaxError.Exception {
    SyntaxChecker checker = new SyntaxChecker();
    checker.check(starlarkFile);
    return checker.includeStatements.build();
  }

  public void runOnThread(StarlarkThread thread) throws EvalException, InterruptedException {
    Starlark.execFileProgram(program, predeclaredEnv, thread);
  }
}
