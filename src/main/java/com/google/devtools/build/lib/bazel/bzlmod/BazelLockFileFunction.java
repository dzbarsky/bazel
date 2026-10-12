// Copyright 2023 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Reads the contents of the lockfiles into {@link BazelLockFileValue}s.
 *
 * <p>See {@link BazelLockFileValue} for more information.
 */
public class BazelLockFileFunction implements SkyFunction {

  public static final Precomputed<LockfileMode> LOCKFILE_MODE = new Precomputed<>("lockfile_mode");

  private static final Pattern LOCKFILE_VERSION_PATTERN =
      Pattern.compile("\"lockFileVersion\":\\s*(\\d+)");

  static final int VERSION_HEADER_LIMIT = 8192;

  private static final Pattern POSSIBLE_MERGE_CONFLICT_PATTERN =
      Pattern.compile("<<<<<<<|=======|" + Pattern.quote("|||||||") + "|>>>>>>>");

  private final Path rootDirectory;
  private final Path outputBase;

  public BazelLockFileFunction(Path rootDirectory, Path outputBase) {
    this.rootDirectory = rootDirectory;
    this.outputBase = outputBase;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BazelLockfileFunctionException, InterruptedException {
    if (LOCKFILE_MODE.get(env) == LockfileMode.OFF) {
      return BazelLockFileValue.EMPTY_LOCKFILE;
    }

    boolean forHiddenLockfile = skyKey == BazelLockFileValue.HIDDEN_KEY;
    RootedPath lockfilePath =
        RootedPath.toRootedPath(
            Root.fromPath(forHiddenLockfile ? outputBase : rootDirectory),
            LabelConstants.MODULE_LOCKFILE_NAME);

    // Add dependency on the lockfile to recognize changes to it
    if (env.getValue(FileValue.key(lockfilePath)) == null) {
      return null;
    }

    try (SilentCloseable c =
        Profiler.instance()
            .profile(
                ProfilerTask.BZLMOD,
                forHiddenLockfile ? "parse hidden lockfile" : "parse lockfile")) {
      return getLockfileValue(
          lockfilePath, forHiddenLockfile ? LockfileMode.UPDATE : LOCKFILE_MODE.get(env));
    } catch (IOException
        | JsonSyntaxException
        | NullPointerException
        | IllegalArgumentException e) {
      if (forHiddenLockfile) {
        return BazelLockFileValue.EMPTY_LOCKFILE;
      }
      String actionSuffix;
      if (e.getMessage() != null
          && POSSIBLE_MERGE_CONFLICT_PATTERN.matcher(e.getMessage()).find()) {
        actionSuffix =
            " This looks like a merge conflict. See"
                + " https://bazel.build/external/lockfile#merge-conflicts for advice.";
      } else {
        actionSuffix = " Try deleting it and rerun the build.";
      }
      throw new BazelLockfileFunctionException(
          ExternalDepsException.withMessage(
              Code.BAD_LOCKFILE,
              "Failed to read and parse the MODULE.bazel.lock file with error: %s.%s",
              e.getMessage(),
              actionSuffix),
          Transience.PERSISTENT);
    }
  }

  public static BazelLockFileValue getLockfileValue(
      RootedPath lockfilePath, LockfileMode lockfileMode)
      throws IOException, BazelLockfileFunctionException {
    try (BufferedReader reader =
        FileSystemUtils.asByteSource(lockfilePath.asPath())
            .asCharSource(UTF_8)
            .openBufferedStream()) {
      // Bazel writes lockFileVersion near the start. Preserve the existing regex's behavior for
      // reordered and obsolete files, which may not even be parseable with the current schema.
      reader.mark(VERSION_HEADER_LIMIT + 1);
      char[] prefix = new char[VERSION_HEADER_LIMIT];
      int n = Math.max(0, reader.read(prefix));
      reader.reset();
      Reader jsonReader = reader;
      Matcher matcher = LOCKFILE_VERSION_PATTERN.matcher(CharBuffer.wrap(prefix, 0, n));
      boolean found = matcher.find();
      if (!found || matcher.end() == n) {
        // A version ending at the boundary may have additional digits. Scan all of the file when
        // the prefix does not determine the same first match as the full-file regex.
        String json = CharStreams.toString(reader);
        matcher = LOCKFILE_VERSION_PATTERN.matcher(json);
        found = matcher.find();
        jsonReader = new StringReader(json);
      }
      int version = found ? Integer.parseInt(matcher.group(1)) : -1;
      if (version == BazelLockFileValue.LOCK_FILE_VERSION) {
        BazelLockFileValue lockFileValue =
            GsonTypeAdapterUtil.LOCKFILE_GSON.fromJson(jsonReader, BazelLockFileValue.class);
        if (!isValidLockfile(lockFileValue)) {
          if (lockfileMode == LockfileMode.ERROR) {
            throw new BazelLockfileFunctionException(
                ExternalDepsException.withMessage(
                    Code.BAD_LOCKFILE,
                    "The version of MODULE.bazel.lock is not supported by this version of Bazel."
                        + " Please run `bazel mod deps --lockfile_mode=update` to update your"
                        + " lockfile."),
                Transience.PERSISTENT);
          }
          return BazelLockFileValue.EMPTY_LOCKFILE;
        }
        return lockFileValue;
      } else {
        // This is an old version, its information can't be used.
        if (lockfileMode == LockfileMode.ERROR) {
          throw new BazelLockfileFunctionException(
              ExternalDepsException.withMessage(
                  Code.BAD_LOCKFILE,
                  "The version of MODULE.bazel.lock is not supported by this version of Bazel."
                      + " Please run `bazel mod deps --lockfile_mode=update` to update your"
                      + " lockfile."),
              Transience.PERSISTENT);
        }
        return BazelLockFileValue.EMPTY_LOCKFILE;
      }
    } catch (FileNotFoundException e) {
      return BazelLockFileValue.EMPTY_LOCKFILE;
    } catch (JsonIOException e) {
      // Gson can encounter file I/O during parsing or its full-document check.
      if (e.getCause() instanceof IOException ioException) {
        throw ioException;
      }
      throw e;
    }
  }

  private static boolean isValidLockfile(@Nullable BazelLockFileValue lockFileValue) {
    if (lockFileValue == null || lockFileValue.getModuleExtensions() == null) {
      return false;
    }
    for (var extensionMap : lockFileValue.getModuleExtensions().values()) {
      for (var extension : extensionMap.values()) {
        if (extension.getGeneratedRepoSpecs() == null) {
          return false;
        }
        for (var repoSpec : extension.getGeneratedRepoSpecs().values()) {
          if (repoSpec.repoRuleId() == null) {
            return false;
          }
        }
      }
    }
    return true;
  }

  static final class BazelLockfileFunctionException extends SkyFunctionException {

    BazelLockfileFunctionException(ExternalDepsException cause, Transience transience) {
      super(cause, transience);
    }
  }
}
