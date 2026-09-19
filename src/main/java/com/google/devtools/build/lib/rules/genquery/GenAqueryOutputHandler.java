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

package com.google.devtools.build.lib.rules.genquery;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.ActionGraphContainer;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.AspectDescriptor;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Configuration;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.DepSetOfFiles;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.PathFragment;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.RuleClass;
import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Target;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.AqueryOutputHandler;
import com.google.devtools.build.lib.skyframe.actiongraph.v2.PrintTask.StreamedProtoPrintTask;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic streaming encodings of the action graph protocol, without a second graph copy. */
final class GenAqueryOutputHandler implements AqueryOutputHandler {
  private final OutputType type;
  private final OutputStream out;
  private final CodedOutputStream binary;
  private final Writer text;
  private final Map<Integer, JsonField> jsonFields = new TreeMap<>();
  private boolean firstAction = true;
  private final Map<DepSetOfFiles, Integer> canonicalDepSets = new HashMap<>();
  private final Map<Integer, Integer> depSetIds = new HashMap<>();
  private static final JsonFormat.Printer JSON =
      JsonFormat.printer().omittingInsignificantWhitespace();

  private static final class JsonField {
    final GenQueryOutputStream bytes = new GenQueryOutputStream(false);
    final Writer writer = new OutputStreamWriter(bytes, ISO_8859_1);
    boolean first = true;

    JsonField() throws IOException {}
  }

  GenAqueryOutputHandler(OutputType type, OutputStream out) throws IOException {
    this.type = type;
    this.out = out;
    binary = CodedOutputStream.newInstance(out);
    // ActionGraphDump's strings carry raw bytes in Bazel's internal Latin-1 representation.
    // As in aquery's JSON PrintStream, preserve those bytes instead of encoding them a second time.
    text = new OutputStreamWriter(out, type == OutputType.JSON ? ISO_8859_1 : UTF_8);
    if (type == OutputType.JSON) {
      // Stream the dominant field directly. Only interleaved metadata arrays need spooling.
      text.write("{\"actions\":[");
    }
  }

  private void write(int fieldNumber, Message message) throws IOException {
    var field = ActionGraphContainer.getDescriptor().findFieldByNumber(fieldNumber);
    switch (type) {
      case BINARY -> binary.writeMessage(fieldNumber, message);
      case DELIMITED_BINARY -> StreamedProtoPrintTask.print(out, message, fieldNumber);
      case TEXT -> {
        text.write(field.getName() + " {\n");
        TextFormat.printer().print(message, text);
        text.write("}\n");
      }
      case JSON -> {
        if (fieldNumber == ActionGraphContainer.ACTIONS_FIELD_NUMBER) {
          if (!firstAction) {
            text.write(',');
          }
          firstAction = false;
          JSON.appendTo(message, text);
        } else {
          JsonField buffer = jsonFields.get(fieldNumber);
          if (buffer == null) {
            buffer = new JsonField();
            jsonFields.put(fieldNumber, buffer);
          }
          if (!buffer.first) {
            buffer.writer.write(',');
          }
          buffer.first = false;
          JSON.appendTo(message, buffer.writer);
        }
      }
    }
  }

  @Override
  public void outputArtifact(Artifact message) throws IOException {
    write(ActionGraphContainer.ARTIFACTS_FIELD_NUMBER, message);
  }

  @Override
  public void outputAction(Action message) throws IOException {
    if (message.getInputDepSetIdsCount() != 0) {
      var action = message.toBuilder().clearInputDepSetIds();
      for (int id : message.getInputDepSetIdsList()) {
        action.addInputDepSetIds(depSetIds.get(id));
      }
      message = action.build();
    }
    write(ActionGraphContainer.ACTIONS_FIELD_NUMBER, message);
  }

  @Override
  public void outputTarget(Target message) throws IOException {
    write(ActionGraphContainer.TARGETS_FIELD_NUMBER, message);
  }

  @Override
  public void outputDepSetOfFiles(DepSetOfFiles message) throws IOException {
    // Remote nested-set decoding shares structurally equal branches. Canonicalize here so IDs
    // and bytes do not depend on whether the analysis graph was local or deserialized.
    var children = new LinkedHashSet<Integer>();
    for (int id : message.getTransitiveDepSetIdsList()) {
      children.add(depSetIds.get(id));
    }
    var shape =
        message.toBuilder()
            .clearId()
            .clearTransitiveDepSetIds()
            .addAllTransitiveDepSetIds(children)
            .build();
    Integer id = canonicalDepSets.get(shape);
    if (id == null) {
      id = canonicalDepSets.size() + 1;
      canonicalDepSets.put(shape, id);
      write(
          ActionGraphContainer.DEP_SET_OF_FILES_FIELD_NUMBER, shape.toBuilder().setId(id).build());
    }
    depSetIds.put(message.getId(), id);
  }

  @Override
  public void outputConfiguration(Configuration message) throws IOException {
    write(ActionGraphContainer.CONFIGURATION_FIELD_NUMBER, message);
  }

  @Override
  public void outputAspectDescriptor(AspectDescriptor message) throws IOException {
    write(ActionGraphContainer.ASPECT_DESCRIPTORS_FIELD_NUMBER, message);
  }

  @Override
  public void outputRuleClass(RuleClass message) throws IOException {
    write(ActionGraphContainer.RULE_CLASSES_FIELD_NUMBER, message);
  }

  @Override
  public void outputPathFragment(PathFragment message) throws IOException {
    write(ActionGraphContainer.PATH_FRAGMENTS_FIELD_NUMBER, message);
  }

  @Override
  public void close() throws IOException {
    if (type == OutputType.JSON) {
      text.write(']');
      var fields = jsonFields.entrySet().iterator();
      while (fields.hasNext()) {
        var entry = fields.next();
        JsonField buffer = entry.getValue();
        buffer.writer.close();
        String name =
            ActionGraphContainer.getDescriptor().findFieldByNumber(entry.getKey()).getJsonName();
        text.write(",\"" + name + "\":[");
        text.flush();
        buffer.bytes.getResult().writeTo(out);
        text.write(']');
        fields.remove();
      }
      text.write('}');
    }
    binary.flush();
    text.flush();
  }
}
