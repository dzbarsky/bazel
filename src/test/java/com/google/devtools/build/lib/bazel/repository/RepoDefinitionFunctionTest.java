// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.repository;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;
import static org.junit.Assert.fail;

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Found;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.skyframe.util.SkyframeExecutorTestUtils;
import com.google.devtools.build.skyframe.EvaluationResult;
import net.starlark.java.eval.Dict;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepoDefinitionFunction}. */
@RunWith(JUnit4.class)
public final class RepoDefinitionFunctionTest extends BuildViewTestCase {

  @Test
  public void testRepoDefinitionDictionaryOrderAffectsEquality() throws Exception {
    scratch.file("BUILD");
    scratch.file(
        "repo.bzl",
        """
        def _impl(ctx):
            pass

        repo = repository_rule(
            implementation = _impl,
            attrs = {"entries": attr.string_dict()},
        )
        """);
    scratch.overwriteFile(
        "MODULE.bazel",
        """
        repo = use_repo_rule("//:repo.bzl", "repo")
        repo(name = "first", entries = {"first": "1", "second": "2"})
        repo(name = "second", entries = {"second": "2", "first": "1"})
        """);
    invalidatePackages(false);

    var firstKey = RepoDefinitionValue.key(RepositoryName.create("+repo+first"));
    var firstResult =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, firstKey, false, reporter);
    assertThat(firstResult.hasError()).isFalse();
    var first = ((Found) firstResult.get(firstKey)).repoDefinition();
    var secondKey = RepoDefinitionValue.key(RepositoryName.create("+repo+second"));
    var secondResult =
        SkyframeExecutorTestUtils.evaluate(skyframeExecutor, secondKey, false, reporter);
    assertThat(secondResult.hasError()).isFalse();
    var second = ((Found) secondResult.get(secondKey)).repoDefinition();

    assertThat(first.repoRule()).isSameInstanceAs(second.repoRule());
    assertThat(first.attrValues()).isEqualTo(second.attrValues());
    assertThat(((Dict<?, ?>) first.attrValues().attributes().get("entries")).keySet())
        .containsExactly("first", "second")
        .inOrder();
    assertThat(((Dict<?, ?>) second.attrValues().attributes().get("entries")).keySet())
        .containsExactly("second", "first")
        .inOrder();
    var reordered =
        new RepoDefinition(
            first.repoRule(), second.attrValues(), first.name(), first.originalName());
    assertThat(new Found(first)).isNotEqualTo(new Found(reordered));
  }

  @Test
  public void testRepoSpec_bazelModule() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel", "module(name='aaa',version='0.1')", "bazel_dep(name='bbb',version='1.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<RepoDefinitionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RepoDefinitionValue repoDefinitionValue = result.get(RepoDefinitionValue.key(repo));
    assertThat(repoDefinitionValue).isInstanceOf(Found.class);
    RepoDefinition repoDefinition = ((Found) repoDefinitionValue).repoDefinition();

    assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository");
    assertThat(repoDefinition.name()).isEqualTo("ccc+");
    assertThat(repoDefinition.attrValues().attributes().get("path"))
        .isEqualTo("/workspace/modules/ccc+2.0");
  }

  @Test
  public void testRepoSpec_nonRegistryOverride() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "local_path_override(module_name='ccc',path='/foo/bar/C')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<RepoDefinitionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RepoDefinitionValue repoDefinitionValue = result.get(RepoDefinitionValue.key(repo));
    assertThat(repoDefinitionValue).isInstanceOf(Found.class);
    RepoDefinition repoDefinition = ((Found) repoDefinitionValue).repoDefinition();

    assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository");
    assertThat(repoDefinition.name()).isEqualTo("ccc+");
    assertThat(repoDefinition.attrValues().attributes().get("path")).isEqualTo("/foo/bar/C");
  }

  @Test
  public void testRepoSpec_singleVersionOverride() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "single_version_override(",
        "  module_name='ccc',version='3.0')");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ccc',version='2.0')")
        .addModule(createModuleKey("ccc", "2.0"), "module(name='ccc', version='2.0')")
        .addModule(createModuleKey("ccc", "3.0"), "module(name='ccc', version='3.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ccc+");
    EvaluationResult<RepoDefinitionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RepoDefinitionValue repoDefinitionValue = result.get(RepoDefinitionValue.key(repo));
    assertThat(repoDefinitionValue).isInstanceOf(Found.class);
    RepoDefinition repoDefinition = ((Found) repoDefinitionValue).repoDefinition();

    assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository");
    assertThat(repoDefinition.name()).isEqualTo("ccc+");
    assertThat(repoDefinition.attrValues().attributes().get("path"))
        .isEqualTo("/workspace/modules/ccc+3.0");
  }

  @Test
  public void testRepoSpec_multipleVersionOverride() throws Exception {
    scratch.overwriteFile(
        "MODULE.bazel",
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bbb',version='1.0')",
        "bazel_dep(name='ccc',version='2.0')",
        "multiple_version_override(module_name='ddd',versions=['1.0','2.0'])");
    registry
        .addModule(
            createModuleKey("bbb", "1.0"),
            "module(name='bbb', version='1.0');bazel_dep(name='ddd',version='1.0')")
        .addModule(
            createModuleKey("ccc", "2.0"),
            "module(name='ccc', version='2.0');bazel_dep(name='ddd',version='2.0')")
        .addModule(createModuleKey("ddd", "1.0"), "module(name='ddd', version='1.0')")
        .addModule(createModuleKey("ddd", "2.0"), "module(name='ddd', version='2.0')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ddd+2.0");
    EvaluationResult<RepoDefinitionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RepoDefinitionValue repoDefinitionValue = result.get(RepoDefinitionValue.key(repo));
    assertThat(repoDefinitionValue).isInstanceOf(Found.class);
    RepoDefinition repoDefinition = ((Found) repoDefinitionValue).repoDefinition();

    assertThat(repoDefinition.repoRule().id().ruleName()).isEqualTo("local_repository");
    assertThat(repoDefinition.name()).isEqualTo("ddd+2.0");
    assertThat(repoDefinition.attrValues().attributes().get("path"))
        .isEqualTo("/workspace/modules/ddd+2.0");
  }

  @Test
  public void testRepoSpec_notFound() throws Exception {
    scratch.overwriteFile("MODULE.bazel", "module(name='aaa',version='0.1')");
    invalidatePackages(false);

    RepositoryName repo = RepositoryName.create("ss");
    EvaluationResult<RepoDefinitionValue> result =
        SkyframeExecutorTestUtils.evaluate(
            skyframeExecutor, RepoDefinitionValue.key(repo), false, reporter);
    if (result.hasError()) {
      fail(result.getError().toString());
    }
    RepoDefinitionValue repoDefinitionValue = result.get(RepoDefinitionValue.key(repo));
    assertThat(repoDefinitionValue).isEqualTo(RepoDefinitionValue.NOT_FOUND);
  }
}
