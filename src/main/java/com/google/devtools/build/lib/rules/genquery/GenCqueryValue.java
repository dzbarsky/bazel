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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult;
import com.google.devtools.build.skyframe.SkyValue;
import javax.annotation.Nullable;

/** Query bytes, shared with the output action, without retaining the query graph. */
public final class GenCqueryValue implements SkyValue {
  @Nullable private GenQueryResult result;

  GenCqueryValue(GenQueryResult result) {
    this.result = result;
  }

  GenQueryResult getResult() {
    return checkNotNull(result);
  }

  /** Releases this reference at the analysis-discard barrier; the action still owns its result. */
  public void clear() {
    result = null;
  }

  @Override
  public boolean isCleared() {
    return result == null;
  }
}
