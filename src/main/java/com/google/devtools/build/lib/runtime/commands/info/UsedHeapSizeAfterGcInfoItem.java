// Copyright 2020 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands.info;

import com.google.common.base.Supplier;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.bugreport.BugReporter;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.runtime.MemoryPressureOptions;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.HeapOffsetHelper;
import com.google.devtools.build.lib.util.StringUtilities;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/** Info item for the used heap size after garbage collection. */
public final class UsedHeapSizeAfterGcInfoItem extends InfoItem {
  public UsedHeapSizeAfterGcInfoItem() {
    super(
        "used-heap-size-after-gc",
        "The amount of used memory in bytes after a call to System.gc().",
        true);
  }

  @Override
  public byte[] get(
      Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env)
      throws AbruptExitException {
    if ("1".equals(env.getClientEnv().get("HORAPHA_SHRINK_HEAP"))) {
      return shrinkHeapForCheckpoint(env);
    }
    System.gc();
    // TODO: b/311665999 - Remove the subtraction of FillerArray once we figure out an alternative.
    return print(
        StringUtilities.prettyPrintBytes(
            InfoItemUtils.getMemoryUsage().getUsed()
                - HeapOffsetHelper.getSizeOfFillerArrayOnHeap(
                    env.getOptions()
                        .getOptions(MemoryPressureOptions.class)
                        .getJvmHeapHistogramInternalObjectPattern()
                        .regexPattern(),
                    BugReporter.defaultInstance())));
  }

  private static byte[] shrinkHeapForCheckpoint(CommandEnvironment env) throws AbruptExitException {
    HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    String min = bean.getVMOption("MinHeapFreeRatio").getValue();
    String max = bean.getVMOption("MaxHeapFreeRatio").getValue();
    if (!"true".equals(bean.getVMOption("UseG1GC").getValue()) || Integer.parseInt(max) < 20) {
      String message = "Checkpoint heap shrinking requires G1 with MaxHeapFreeRatio >= 20";
      env.getReporterOutErr().printErrLn("ERROR: " + message);
      throw new AbruptExitException(
          DetailedExitCode.of(
              FailureDetails.FailureDetail.newBuilder()
                  .setMessage(message)
                  .setInfoCommand(
                      FailureDetails.InfoCommand.newBuilder()
                          .setCode(FailureDetails.InfoCommand.Code.HEAP_SHRINK_UNSUPPORTED))
                  .build()));
    }
    long committedBefore = InfoItemUtils.getMemoryUsage().getCommitted();
    try {
      bean.setVMOption("MinHeapFreeRatio", "10");
      bean.setVMOption("MaxHeapFreeRatio", "15");
      System.gc();
    } finally {
      // Preserve the ordinary probe's heap policy, including on a failed GC.
      try {
        bean.setVMOption("MaxHeapFreeRatio", max);
      } finally {
        bean.setVMOption("MinHeapFreeRatio", min);
      }
    }
    String restoredMin = bean.getVMOption("MinHeapFreeRatio").getValue();
    String restoredMax = bean.getVMOption("MaxHeapFreeRatio").getValue();
    if (!min.equals(restoredMin) || !max.equals(restoredMax)) {
      throw new IllegalStateException("Checkpoint GC did not restore the original heap policy");
    }
    MemoryUsage usage = InfoItemUtils.getMemoryUsage();
    env.getReporterOutErr()
        .printErrLn(
            ("HORAPHA_HEAP_SHRINK {\"committed_before\":%d,\"committed_after\":%d,"
                    + "\"used_after\":%d,\"max_heap_bytes\":%d,\"min_before\":%s,\"max_before\":%s,"
                    + "\"min_restored\":%s,\"max_restored\":%s}")
                .formatted(
                    committedBefore,
                    usage.getCommitted(),
                    usage.getUsed(),
                    usage.getMax(),
                    min,
                    max,
                    restoredMin,
                    restoredMax));
    // Do not trigger another heap-histogram GC with the ordinary ratios restored.
    return print(StringUtilities.prettyPrintBytes(usage.getUsed()));
  }
}
