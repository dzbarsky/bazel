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
package com.google.devtools.build.lib.actions;

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn;
import com.google.devtools.build.lib.util.DetailedExitCode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ActionExecutionExceptionTest {
  @Test
  public void cacheProbeMissSurvivesReportedWrapper() {
    ActionExecutionException miss = exception(Spawn.Code.CACHE_PROBE_MISS, false);

    assertThat(miss.isCacheProbeMiss()).isTrue();
    assertThat(miss.showError()).isFalse();
    assertThat(new AlreadyReportedActionExecutionException(miss).isCacheProbeMiss()).isTrue();
  }

  @Test
  public void ordinaryExecutionDenialIsNotCacheProbeMiss() {
    ActionExecutionException denied = exception(Spawn.Code.EXECUTION_DENIED, false);

    assertThat(denied.isCacheProbeMiss()).isFalse();
    assertThat(denied.showError()).isTrue();
  }

  @Test
  public void catastropheIsNeverAnExpectedMiss() {
    ActionExecutionException catastrophe = exception(Spawn.Code.CACHE_PROBE_MISS, true);

    assertThat(catastrophe.isCacheProbeMiss()).isFalse();
    assertThat(catastrophe.showError()).isTrue();
  }

  private static ActionExecutionException exception(Spawn.Code code, boolean catastrophe) {
    return new ActionExecutionException(
        "cache probe",
        /* action= */ null,
        catastrophe,
        DetailedExitCode.of(
            FailureDetail.newBuilder().setSpawn(Spawn.newBuilder().setCode(code)).build()));
  }
}
