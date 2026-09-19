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
package com.google.devtools.build.lib.query2.aquery;

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.packages.LabelPrinter;
import java.io.IOException;
import java.io.OutputStream;
import net.starlark.java.eval.EvalException;

/** Synchronous text formatting for callers that select and order individual actions. */
public final class ActionGraphTextOutput implements AutoCloseable {
  private final AqueryThreadsafeCallback callback;

  public ActionGraphTextOutput(
      AqueryOptions options,
      OutputStream out,
      ExtendedEventHandler events,
      LabelPrinter labelPrinter,
      AqueryUtils.ParamFileContents paramFiles) {
    // Selection and aspect traversal belong to the caller; these writers need no target accessor.
    if (options.getOutputFormat().equals("summary")) {
      var summary =
          new ActionGraphSummaryOutputFormatterCallback(
              events, options, out, null, AqueryActionFilter.emptyInstance());
      summary.deterministic = true;
      callback = summary;
    } else {
      var type =
          switch (options.getOutputFormat()) {
            case "text" -> ActionGraphTextOutputFormatterCallback.OutputType.TEXT;
            case "commands" -> ActionGraphTextOutputFormatterCallback.OutputType.COMMANDS;
            default -> throw new IllegalArgumentException(options.getOutputFormat());
          };
      var text =
          new ActionGraphTextOutputFormatterCallback(
              events, options, out, null, type, AqueryActionFilter.emptyInstance(), labelPrinter);
      text.paramFiles = paramFiles;
      text.analysisOnly = true;
      callback = text;
    }
  }

  public void writeAction(ActionAnalysisMetadata action)
      throws IOException, InterruptedException, CommandLineExpansionException, EvalException {
    if (callback instanceof ActionGraphTextOutputFormatterCallback text) {
      text.writeAction(action, text.printStream);
    } else {
      ((ActionGraphSummaryOutputFormatterCallback) callback).processAction(action);
    }
  }

  @Override
  public void close() throws IOException, InterruptedException {
    callback.close(/* failFast= */ false);
    callback.printStream.flush();
    if (callback.printStream.checkError()) {
      throw new IOException("could not write action query output");
    }
  }
}
