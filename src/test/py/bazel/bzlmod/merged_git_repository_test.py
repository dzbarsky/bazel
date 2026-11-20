# Copyright 2026 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""End-to-end tests for merged_git_repository() in the root MODULE.bazel."""

from pathlib import Path
import tempfile

from absl.testing import absltest
from src.test.py.bazel import test_base


class MergedGitRepositoryTest(test_base.TestBase):

  def setUp(self):
    super().setUp()
    self.ScratchFile('.bazelrc', ['common --experimental_merged_git_repository'])
    self.upstream = Path(tempfile.mkdtemp(dir=self._tests_root))
    self.git('init')
    self.write('MODULE.bazel', 'module(name="upstream", repo_name="library")\n')

  def write(self, path, text):
    file = self.upstream / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text)

  def git(self, *args):
    return self.RunProgram(['git', *args], cwd=self.upstream)[1]

  def commit(self):
    self.git('add', '.')
    self.git(
        '-c', 'user.name=test', '-c', 'user.email=test@example.org',
        'commit', '-m', 'update',
    )
    return self.git('rev-parse', 'HEAD')[0]

  def merge(self, *lines, remote=None, prefer_imported=()):
    self.ScratchFile('revision', [self.commit()])
    self.ScratchFile('MODULE.bazel', [
        'module(name = "consumer")',
        'merged_git_repository(',
        '    remote = %r,' % (remote or self.upstream.as_uri()),
        '    commit_file = "revision",',
        '    prefer_imported = %r,' % list(prefer_imported),
        ')',
        *lines,
    ])

  def testSharedModuleAndPackageIdentity(self):
    self.RunProgram(['git', 'init'])
    self.write('MODULE.bazel', """
module(name = "upstream", repo_name = "library")
include("//shared:deps.MODULE.bazel")
try_include("//shared:override.MODULE.bazel")
try_include("//shared:missing.MODULE.bazel")
""")
    self.write('shared/deps.MODULE.bazel', """
bazel_dep(name = "helper", version = "1.0", dev_dependency = True)
extension = use_extension("@library//shared:ext.bzl", "extension")
extension.input(file = "//shared:input")
use_repo(extension, "generated")
""")
    self.write(
        'shared/override.MODULE.bazel',
        'local_path_override(module_name = "helper", path = "vendor/helper")\n',
    )
    self.write('shared/ext.bzl', """
def _repo(rctx):
    if rctx.execute(["git", "rev-parse", "--git-dir"], working_directory = str(rctx.workspace_root)).return_code:
        fail("expected the caller's git checkout")
    joined = rctx.read(rctx.workspace_root.get_child("shared", "input"))
    rctx.file("value", rctx.attr.text + joined)
    rctx.file("BUILD.bazel", 'exports_files(["value"])')

repo = repository_rule(implementation = _repo, attrs = {"text": attr.string()})

def _impl(ctx):
    if len(ctx.modules) != 1 or not ctx.modules[0].is_root:
        fail("expected one module")
    tags = ctx.modules[0].tags.input
    if len(tags) != 2:
        fail("expected both declarations on one extension")
    repo(name = "generated", text = "".join([ctx.read(t.file) for t in tags]))

extension = module_extension(
    implementation = _impl,
    tag_classes = {"input": tag_class(attrs = {"file": attr.label()})},
)
""")
    self.write('shared/defs.bzl', """
Info = provider(fields = ["file"])

def _impl(ctx):
    if ctx.label.repo_name or ctx.workspace_name != "_main":
        fail("expected main repo")
    return [Info(file = ctx.file.src)]

library = rule(implementation = _impl, attrs = {"src": attr.label(allow_single_file=True)})
""")
    self.write('shared/input', 'shared\n')
    self.write('shared/BUILD.bazel', """
load(":defs.bzl", "library")
library(name = "lib", src = "//app:local", visibility = ["//app:__pkg__"])
exports_files(["input"])
""")
    self.write('vendor/helper/MODULE.bazel', """
module(name = "helper")
bazel_dep(name = "upstream", repo_name = "backref")
""")
    self.write('vendor/helper/BUILD.bazel', """
alias(name = "value", actual = "@backref//shared:input", visibility = ["//visibility:public"])
""")
    self.merge(
        'extension = use_extension("//shared:ext.bzl", "extension")',
        'extension.input(file = "@consumer//app:local")',
    )
    self.ScratchFile('app/defs.bzl', ["""
load("@library//shared:defs.bzl", "Info")

def _impl(ctx):
    local = ctx.attr.dep[Info].file
    if local.short_path != "app/local" or local.owner.repo_name:
        fail(local.short_path)
    out = ctx.actions.declare_file(ctx.label.name + ".txt")
    inputs = [local] + ctx.files.data
    ctx.actions.run_shell(
        inputs = inputs,
        outputs = [out],
        command = 'cat "$@" > ' + out.path,
        arguments = [f.path for f in inputs],
    )
    return [DefaultInfo(files = depset([out]))]

app = rule(
    implementation = _impl,
    attrs = {"dep": attr.label(providers = [Info]), "data": attr.label_list(allow_files=True)},
)
"""])
    self.ScratchFile('app/local', ['local'])
    self.ScratchFile('app/BUILD.bazel', [
        'load(":defs.bzl", "app")',
        'exports_files(["local"], visibility = ["//shared:__pkg__"])',
        'app(name = "app", dep = "//shared:lib",',
        '    data = ["@generated//:value", "@helper//:value"])',
    ])
    self.RunBazel(['build', ':app'], cwd=self.Path('app'))
    output = Path('bazel-bin/app/app.txt')
    self.assertEqual(output.read_text(), 'local\nshared\nlocal\nshared\nshared\n')
    self.ScratchFile('app/local', ['edited'])
    self.RunBazel(['build', '@library//app'])
    self.assertEqual(output.read_text(), 'edited\nshared\nedited\nshared\nshared\n')
    _, labels, _ = self.RunBazel([
        'query', 'set(@library//shared:lib @consumer//shared:lib //shared:lib)',
    ])
    self.assertEqual(labels, ['//shared:lib'])
    self.assertFalse(Path('shared').exists())
    self.assertEqual(self.RunBazel(['info', 'workspace'])[1], [self._test_cwd])

  def testRecursivePatternsAndSymlinksAtMergeBoundary(self):
    marker = (
        'DONT_FOLLOW_SYMLINKS_WHEN_TRAVERSING_THIS_DIRECTORY'
        '_VIA_A_RECURSIVE_TARGET_PATTERN'
    )
    self.write('tree/imported/BUILD.bazel', 'filegroup(name="imported")')
    self.write('tree/ignored/BUILD.bazel', 'filegroup(name="ignored")')
    self.write('.bazelignore', 'tree/ignored\n')
    (self.upstream / 'tree' / 'link').symlink_to('local')
    self.merge()
    self.ScratchFile('tree/' + marker, [])
    self.ScratchFile('tree/local/BUILD.bazel', ['filegroup(name="local")'])
    labels = ['//tree/imported:imported', '//tree/local:local']
    self.assertEqual(self.RunBazel(['query', '//...'])[1], labels)
    self.assertEqual(self.RunBazel(['query', '//tree/link:local'])[1], [
        '//tree/link:local',
    ])
    # Rebuild the projection after creating the convenience symlinks, then
    # rediscover only source packages, even when they contain outputs.
    self.RunBazel(['build', '//tree/imported'])
    self.assertEqual(self.RunBazel(['query', '//...'])[1], labels)
    Path('tree', marker).unlink()
    self.assertEqual(
        self.RunBazel(['query', '//tree/...'])[1],
        sorted(labels + ['//tree/link:local']),
    )

  def testGlobsAndPinChanges(self):
    self.write('pkg/BUILD.bazel', """
genrule(
    name = "collect",
    srcs = glob(["**/*.txt"]),
    outs = ["collected"],
    cmd = "cat $(SRCS) > $@",
)
""")
    self.write('pkg/base.txt', 'base v1\n')
    self.write('pkg/both.txt', 'fallback\n')
    self.write('pkg/nested/BUILD.bazel', 'filegroup(name="separate")')
    self.write('pkg/nested/hidden.txt', 'subpkg\n')
    self.merge(remote=str(self.upstream), prefer_imported=['pkg/BUILD.bazel'])
    original = Path('revision').read_text()
    self.write('pkg/base.txt', 'base v2\n')
    self.write('MODULE.bazel', 'module(name="renamed")')
    revision = self.commit()
    self.write('pkg/base.txt', 'dirty\n')
    self.ScratchFile('pkg/BUILD.bazel', ['filegroup(name="local")'])
    self.ScratchFile('pkg/both.txt', ['override'])

    def collect(*flags):
      self.RunBazel(['build', '//pkg:collect', *flags])
      return Path('bazel-bin/pkg/collected').read_text().splitlines()

    self.assertEqual(collect(), ['base v1', 'override'])
    self.ScratchFile('pkg/added.txt', ['added'])
    Path('pkg/both.txt').unlink()
    self.assertEqual(collect(), ['added', 'base v1', 'fallback'])
    self.ScratchFile('revision', [revision])
    self.assertEqual(collect(), ['added', 'base v2', 'fallback'])
    self.assertEqual(self.RunBazel(['query', '@renamed//pkg:collect'])[1], [
        '//pkg:collect',
    ])
    self.assertEqual(self.RunBazel(['query', '//pkg/...'])[1], [
        '//pkg:collect', '//pkg/nested:separate',
    ])
    Path('revision').write_text(original)
    self.assertEqual(collect('--nofetch'), ['added', 'base v1', 'fallback'])
    self.ScratchFile('MODULE.bazel', ['module(name="consumer")'])
    self.assertEqual(self.RunBazel(['query', '//pkg/...'])[1], ['//pkg:local'])
    self.assertEqual((self.upstream / 'pkg/base.txt').read_text(), 'dirty\n')

  def testInvalidPinFailsWithoutUsingLastSnapshot(self):
    self.write('BUILD.bazel', 'filegroup(name="lib")')
    self.merge()
    self.RunBazel(['query', '//:lib'])
    original = Path('revision').read_text()
    for pin, message in [
        ('HEAD', 'full 40-character'),
        ('0' * 40, 'fetching is disabled'),
    ]:
      self.ScratchFile('revision', [pin])
      code, _, stderr = self.RunBazel(
          ['query', '--nofetch', '//:lib'], allow_failure=True
      )
      self.assertNotEqual(code, 0)
      self.assertIn(message, '\n'.join(stderr))
    Path('revision').write_text(original)
    self.RunBazel(['query', '//:lib'])

  def testRejectsIndirectAndNestedMerges(self):
    self.write('BUILD.bazel', 'filegroup(name="lib")')
    commit = self.commit()
    call = 'merged_git_repository(remote=%r, commit=%r)' % (
        self.upstream.as_uri(), commit,
    )
    self.ScratchFile('MODULE.bazel', [call])
    code, _, stderr = self.RunBazel(
        ['query', '--noexperimental_merged_git_repository', '//:lib'],
        allow_failure=True,
    )
    self.assertNotEqual(code, 0)
    self.assertIn('--experimental_merged_git_repository', '\n'.join(stderr))
    self.RunBazel(['query', '//:lib'])
    for lines, message in [
        ([call, call], 'expected a single declaration'),
        (['alias = merged_git_repository', 'alias(remote="x")'],
         'must be called once directly'),
        (['merged_git_repository(remote=str(123), commit="x")'],
         'must be a string literal'),
    ]:
      self.ScratchFile('MODULE.bazel', lines)
      code, _, stderr = self.RunBazel(
          ['query', '//:lib'], allow_failure=True
      )
      self.assertNotEqual(code, 0)
      self.assertIn(message, '\n'.join(stderr))
    self.write('MODULE.bazel', call)
    self.merge()
    code, _, stderr = self.RunBazel(['query', '//:lib'], allow_failure=True)
    self.assertNotEqual(code, 0)
    self.assertIn('must be called once directly', '\n'.join(stderr))


if __name__ == '__main__':
  absltest.main()
