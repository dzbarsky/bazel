// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec;

import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;

/** Options controlling the metadata-based cache of computed file digests. */
@OptionsClass
public abstract class CacheFileDigestsOptions extends OptionsBase {
  public static final String OPTION_NAME = "cache_computed_file_digests";

  @Option(
      name = OPTION_NAME,
      defaultValue = "50000",
      converter = Converters.NonNegativeIntegerConverter.class,
      documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
      effectTags = {OptionEffectTag.UNKNOWN},
      help =
          "If greater than 0, configures Bazel to cache file digests in memory based on their "
              + "metadata instead of recomputing the digests from disk every time they are needed. "
              + "Setting this to 0 ensures correctness because not all file changes can be noted "
              + "from file metadata. When not 0, the number indicates the size of the cache as the "
              + "number of file digests to be cached. Each cached digest takes roughly 100 bytes "
              + "of heap. The cache is most effective when it can hold the digests of all files "
              + "that a build digests, which includes source files and outputs of locally "
              + "executed actions. For query, an explicit value applies only to that command. "
              + "An omitted value keeps the current cache.")
  public abstract int getCacheSizeForComputedFileDigests();
}
