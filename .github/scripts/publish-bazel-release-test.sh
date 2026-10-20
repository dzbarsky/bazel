#!/usr/bin/env bash
set -euo pipefail
python3 - "$(cd "$(dirname "$0")/../.." && pwd)" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

root = Path(sys.argv[1])
mock = r'''#!/usr/bin/env python3
import hashlib, json, os, pathlib, subprocess, sys
args = sys.argv[1:]
case = os.environ['MOCK_CASE']
state = pathlib.Path(os.environ['MOCK_STATE'])
repo = 'repos/dzbarsky/bazel'
tag = os.environ['RELEASE_TAG']
sha = os.environ['GITHUB_SHA']
def fail():
    print('Mock GitHub API failure.', file=sys.stderr)
    sys.exit(1)
def event(name):
    with (state / 'events').open('a') as out:
        out.write(name + '\n')
def emit(value):
    if '--jq' in args:
        result = subprocess.run(['jq', '-r', args[args.index('--jq') + 1]],
                                input=json.dumps(value), text=True)
        sys.exit(result.returncode)
    print(json.dumps(value))
def save(value):
    (state / 'release.json').write_text(json.dumps(value))
if '--clobber' in args:
    raise AssertionError('Replacing release assets is forbidden')
if args[:2] == ['release', 'upload']:
    event('upload')
    release = json.loads((state / 'release.json').read_text())
    assert release['draft'] is True
    files = args[3:args.index('--repo')]
    assert len(files) == 18
    release['assets'] = [dict(name=pathlib.Path(p).name, size=pathlib.Path(p).stat().st_size,
        digest='sha256:' + hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest(),
        state='uploaded') for p in files]
    save(release)
    if case == 'upload_error':
        fail()
    sys.exit(0)
assert args[0] == 'api', args
endpoint = next(arg for arg in args if arg.startswith('repos/'))
method = args[args.index('--method') + 1] if '--method' in args else 'GET'
if '/git/matching-refs/' in endpoint:
    event('tags')
    if case == 'tag_api_error': fail()
    emit([{'ref': 'refs/tags/' + tag}] if case == 'existing_tag' else [])
elif endpoint == repo + '/releases?per_page=100':
    event('releases')
    assert '--paginate' in args
    if case == 'release_api_error': fail()
    releases = json.loads(os.environ.get('MOCK_RELEASES', '[]'))
    if case in ('existing_published', 'existing_draft'):
        releases.append(dict(id=17, tag_name=tag, draft=case == 'existing_draft'))
    emit(releases)
elif method == 'POST' and endpoint == repo + '/releases':
    event('create')
    if case == 'create_error': fail()
    fields = dict(arg.split('=', 1) for arg in args if '=' in arg)
    assert fields['draft'] == 'true'
    assert fields['target_commitish'] == sha
    assert fields['tag_name'] == tag
    expected_body = f'Bazel binaries for Linux, macOS, and Windows. Use USE_BAZEL_VERSION=dzbarsky/{tag} with Bazelisk.'
    if case == 'release_notes':
        expected_body += '\n\n## Changes\n\n- Preserve `toolchain_type` aliases.'
    assert fields['body'] == expected_body, fields['body']
    release = dict(id=101, tag_name=tag, target_commitish=sha, draft=True,
                   immutable=False, assets=[], body=fields['body'])
    save(release)
    emit(release)
elif endpoint == repo + '/releases/101':
    release = json.loads((state / 'release.json').read_text())
    if method == 'PATCH':
        event('publish')
        assert 'draft=false' in args and release['draft'] is True
        if case == 'publish_error': fail()
        release.update(draft=False, immutable=case != 'mutable_release')
        save(release)
    else:
        event('draft' if release['draft'] else 'published')
        if case == 'verify_api_error': fail()
        if case == 'missing_remote_asset': release['assets'].pop()
        if case == 'wrong_remote_digest': release['assets'][0]['digest'] = 'sha256:bad'
        if case == 'wrong_remote_target': release['target_commitish'] = '2' * 40
        if case == 'wrong_remote_body': release['body'] = 'Incorrect release notes'
    emit(release)
elif endpoint == repo + '/git/ref/tags/' + tag:
    event('tag')
    emit({'object': {'type': 'commit', 'sha': '2' * 40 if case == 'wrong_tag' else sha}})
else:
    raise AssertionError(args)
'''

with tempfile.TemporaryDirectory(prefix='publication-test-', dir=root) as temporary:
    temporary = Path(temporary)
    fixture = temporary / 'repository'
    scripts = fixture / '.github/scripts'
    scripts.mkdir(parents=True)
    for name in ('publish-bazel-release.sh', 'bazel-release-tag.sh'):
        shutil.copyfile(root / '.github/scripts' / name, scripts / name)
    publisher = scripts / 'publish-bazel-release.sh'
    selector = scripts / 'bazel-release-tag.sh'
    module = fixture / 'MODULE.bazel'
    module.write_text('module(\n    name = "bazel",\n    version = "10.0.0-prerelease",\n)\n')
    (temporary / 'bin').mkdir()
    (temporary / 'bin/gh').write_text(mock)
    (temporary / 'bin/gh').chmod(0o755)
    environment = dict(os.environ, PATH=str(temporary / 'bin') + os.pathsep + os.environ['PATH'],
                       GITHUB_REPOSITORY='dzbarsky/bazel', GITHUB_SHA='1' * 40,
                       GITHUB_REF_NAME='dzbarsky-20.10.2026',
                       RELEASE_TAG='10.0.0-dzbarsky20261020r17', GH_TOKEN='', GITHUB_TOKEN='')
    cases = ['success', 'release_notes', 'missing_notes', 'wrong_remote_body',
             'existing_tag', 'existing_published', 'existing_draft',
             'tag_api_error', 'release_api_error', 'missing_local_asset', 'bad_checksum',
             'create_error', 'upload_error', 'verify_api_error', 'missing_remote_asset',
             'wrong_remote_digest', 'wrong_remote_target', 'publish_error',
             'mutable_release', 'wrong_tag']
    invalid_publisher_tags = {
        'wrong_release_date': '10.0.0-dzbarsky20261021',
        'wrong_bazel_version': '9.4.0-dzbarsky20261020',
        'bare_branch_tag': 'dzbarsky-20.10.2026',
        'revision_one': '10.0.0-dzbarsky20261020r1',
    }
    cases.extend(invalid_publisher_tags)
    windows_failures = {
        f'windows_{architecture}_{failure}': (architecture, failure)
        for architecture in ('x86_64', 'arm64')
        for failure in ('missing_exe', 'missing_checksum', 'missing_attestation',
                        'unsuffixed_exe', 'bad_checksum')
    }
    cases.extend(windows_failures)
    for case in cases:
        directory = temporary / case
        artifacts = directory / 'artifacts'
        artifacts.mkdir(parents=True)
        for system in ('linux', 'darwin', 'windows'):
            for architecture in ('x86_64', 'arm64'):
                name = f"bazel-{environment['RELEASE_TAG']}-{system}-{architecture}"
                if system == 'windows':
                    name += '.exe'
                data = f'Mock binary for {system}-{architecture}\n'.encode()
                (artifacts / name).write_bytes(data)
                (artifacts / (name + '.sha256')).write_text(hashlib.sha256(data).hexdigest() + '  ' + name + '\n')
                (artifacts / (name + '.intoto.jsonl')).write_text('{"mock":"attestation"}\n')
        target = artifacts / f"bazel-{environment['RELEASE_TAG']}-linux-x86_64"
        if case == 'missing_local_asset': target.unlink()
        if case == 'bad_checksum': target.write_text('Corrupted mock binary\n')
        if case in windows_failures:
            architecture, failure = windows_failures[case]
            name = f"bazel-{environment['RELEASE_TAG']}-windows-{architecture}.exe"
            target = artifacts / name
            if failure == 'missing_exe':
                target.unlink()
            elif failure == 'missing_checksum':
                (artifacts / (name + '.sha256')).unlink()
            elif failure == 'missing_attestation':
                (artifacts / (name + '.intoto.jsonl')).unlink()
            elif failure == 'unsuffixed_exe':
                unsuffixed_name = name[:-4]
                for suffix in ('', '.sha256', '.intoto.jsonl'):
                    (artifacts / (name + suffix)).rename(artifacts / (unsuffixed_name + suffix))
                checksum = artifacts / (unsuffixed_name + '.sha256')
                checksum.write_text(checksum.read_text().replace(name, unsuffixed_name))
            elif failure == 'bad_checksum':
                target.write_text('Corrupted mock Windows binary\n')
        # Unrelated files must not be uploaded by a broad artifacts/* argument.
        (artifacts / 'unrelated.txt').write_text('Not a release asset\n')
        if case == 'release_notes':
            notes = directory / 'notes.md'
            notes.write_text('## Changes\n\n- Preserve `toolchain_type` aliases.\n')
        env = dict(environment, MOCK_CASE=case, MOCK_STATE=str(directory), TMPDIR=str(directory))
        env.pop('RELEASE_NOTES_FILE', None)
        if case in invalid_publisher_tags:
            env['RELEASE_TAG'] = invalid_publisher_tags[case]
        if case in ('release_notes', 'missing_notes'):
            env['RELEASE_NOTES_FILE'] = str(directory / 'notes.md')
        result = subprocess.run(['bash', str(publisher), str(artifacts)], env=env,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        events = (directory / 'events').read_text().splitlines() if (directory / 'events').exists() else []
        assert (result.returncode == 0) == (case in ('success', 'release_notes')), (case, result.stdout)
        if case in ('success', 'release_notes'):
            assert events == ['tags', 'releases', 'create', 'upload', 'draft', 'publish', 'published', 'tag'], events
        elif case in windows_failures or case in invalid_publisher_tags or case in ('missing_notes', 'missing_local_asset', 'bad_checksum'):
            assert events == [], (case, events)
        elif case not in ('publish_error', 'mutable_release', 'wrong_tag'):
            assert 'publish' not in events, (case, events)
        print('PASS', case)

    workflow = (root / '.github/workflows/build-and-publish-bazel.yml').read_text()
    assert "      - 'dzbarsky-??.??.????'" in workflow
    assert 'group: bazel-release-${{ github.ref }}\n  cancel-in-progress: false' in workflow
    assert 'run: bash .github/scripts/bazel-release-tag.sh select' in workflow
    assert '--clobber' not in workflow

    prefix = '10.0.0-dzbarsky20261020'
    def release(tag, draft=False):
        return dict(id=17, tag_name=tag, draft=draft)

    unrelated = [release('9.4.0-dzbarsky99'), release('10.0.0-dzbarsky20261019r20'),
                 release('10.0.0-actiond99'), release(prefix + 'r500', draft=True),
                 release(prefix + 'r01'), release(prefix + 'r1')]
    selections = [
        ('first', '', unrelated, prefix),
        ('second', '', unrelated + [release(prefix)], prefix + 'r2'),
        ('highest_published', '', unrelated + [release(prefix), release(prefix + 'r16'),
                                               release(prefix + 'r3')], prefix + 'r17'),
        ('manual_first', prefix, unrelated, prefix),
        ('manual_revision', prefix + 'r2', unrelated, prefix + 'r2'),
        ('different_date', '10.0.0-dzbarsky20261021', unrelated, None),
        ('different_version', '9.4.0-dzbarsky20261020', unrelated, None),
        ('wrong_prefix', '10.0.0-actiond20261020', unrelated, None),
        ('branch_as_tag', 'dzbarsky-20.10.2026', unrelated, None),
        ('zero_revision', prefix + 'r0', unrelated, None),
        ('one_revision', prefix + 'r1', unrelated, None),
        ('leading_zero_revision', prefix + 'r02', unrelated, None),
        ('non_numeric_revision', prefix + 'rabc', unrelated, None),
        ('unsafe_tag', prefix + '/../../master', unrelated, None),
    ]
    for name, input_tag, releases, expected in selections:
        directory = temporary / ('selection_' + name)
        directory.mkdir()
        output = directory / 'github-output'
        env = dict(environment, MOCK_CASE='numbering', MOCK_STATE=str(directory),
                   MOCK_RELEASES=json.dumps(releases),
                   INPUT_RELEASE_TAG=input_tag, GITHUB_OUTPUT=str(output))
        result = subprocess.run(['bash', str(selector), 'select'], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        events = (directory / 'events').read_text().splitlines() if (directory / 'events').exists() else []
        assert (result.returncode == 0) == (expected is not None), (name, result.stdout)
        assert events == ([] if input_tag else ['releases']), (name, events)
        if expected is None:
            assert not output.exists(), (name, output.read_text())
        else:
            assert output.read_text() == 'release_tag=' + expected + '\n', (name, output.read_text())
        print('PASS selection_' + name)

    for name, branch, version, expected in [
        ('next_branch', 'dzbarsky-02.01.2027', '10.0.0-prerelease', '10.0.0-dzbarsky20270102'),
        ('new_module_version', 'dzbarsky-20.10.2026', '11.0.0-prerelease', '11.0.0-dzbarsky20261020'),
        ('stable_module_version', 'dzbarsky-20.10.2026', '10.1.2', '10.1.2-dzbarsky20261020'),
        ('invalid_branch', 'codex/bazel-9.4-jvm-buildbuddy-release', '10.0.0-prerelease', None),
        ('invalid_date_format', 'dzbarsky-2026.10.20', '10.0.0-prerelease', None),
        ('invalid_module_version', 'dzbarsky-20.10.2026', 'unversioned', None),
    ]:
        directory = temporary / ('selection_' + name)
        directory.mkdir()
        output = directory / 'github-output'
        module.write_text(f'module(\n    name = "bazel",\n    version = "{version}",\n)\n')
        env = dict(environment, MOCK_CASE='numbering', MOCK_STATE=str(directory),
                   GITHUB_REF_NAME=branch, INPUT_RELEASE_TAG='', GITHUB_OUTPUT=str(output))
        result = subprocess.run(['bash', str(selector), 'select'], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        assert (result.returncode == 0) == (expected is not None), (name, result.stdout)
        if expected is None:
            assert not output.exists(), (name, output.read_text())
        else:
            assert output.read_text() == 'release_tag=' + expected + '\n', (name, output.read_text())
        print('PASS selection_' + name)
print(f'All {len(cases) + len(selections) + 6} mocked publication checks passed; no network calls or GitHub mutations.')
PY
