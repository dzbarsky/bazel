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
publisher = root / '.github/scripts/publish-bazel-release.sh'
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
    assert len(files) == 12
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
    releases = [dict(id=16, tag_name='9.3.0-dzbarsky16', draft=False),
                dict(id=99, tag_name='9.3.0-actiond99', draft=False),
                dict(id=500, tag_name='9.3.0-dzbarsky500', draft=True)]
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
    release = dict(id=101, tag_name=tag, target_commitish=sha, draft=True,
                   immutable=False, assets=[])
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
    emit(release)
elif endpoint == repo + '/git/ref/tags/' + tag:
    event('tag')
    emit({'object': {'type': 'commit', 'sha': '2' * 40 if case == 'wrong_tag' else sha}})
else:
    raise AssertionError(args)
'''

with tempfile.TemporaryDirectory(prefix='publication-test-', dir=root) as temporary:
    temporary = Path(temporary)
    (temporary / 'bin').mkdir()
    (temporary / 'bin/gh').write_text(mock)
    (temporary / 'bin/gh').chmod(0o755)
    environment = dict(os.environ, PATH=str(temporary / 'bin') + os.pathsep + os.environ['PATH'],
                       GITHUB_REPOSITORY='dzbarsky/bazel', GITHUB_SHA='1' * 40,
                       RELEASE_TAG='9.3.0-dzbarsky17', GH_TOKEN='', GITHUB_TOKEN='')
    cases = ['success', 'existing_tag', 'existing_published', 'existing_draft',
             'tag_api_error', 'release_api_error', 'missing_local_asset', 'bad_checksum',
             'create_error', 'upload_error', 'verify_api_error', 'missing_remote_asset',
             'wrong_remote_digest', 'wrong_remote_target', 'publish_error',
             'mutable_release', 'wrong_tag']
    for case in cases:
        directory = temporary / case
        artifacts = directory / 'artifacts'
        artifacts.mkdir(parents=True)
        for system in ('linux', 'darwin'):
            for architecture in ('x86_64', 'arm64'):
                name = f"bazel-{environment['RELEASE_TAG']}-{system}-{architecture}"
                data = f'Mock binary for {system}-{architecture}\n'.encode()
                (artifacts / name).write_bytes(data)
                (artifacts / (name + '.sha256')).write_text(hashlib.sha256(data).hexdigest() + '  ' + name + '\n')
                (artifacts / (name + '.intoto.jsonl')).write_text('{"mock":"attestation"}\n')
        target = artifacts / f"bazel-{environment['RELEASE_TAG']}-linux-x86_64"
        if case == 'missing_local_asset': target.unlink()
        if case == 'bad_checksum': target.write_text('Corrupted mock binary\n')
        # Unrelated files must not be uploaded by a broad artifacts/* argument.
        (artifacts / 'unrelated.txt').write_text('Not a release asset\n')
        env = dict(environment, MOCK_CASE=case, MOCK_STATE=str(directory), TMPDIR=str(directory))
        result = subprocess.run(['bash', str(publisher), str(artifacts)], env=env,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        events = (directory / 'events').read_text().splitlines() if (directory / 'events').exists() else []
        assert (result.returncode == 0) == (case == 'success'), (case, result.stdout)
        if case == 'success':
            assert events == ['tags', 'releases', 'create', 'upload', 'draft', 'publish', 'published', 'tag'], events
        elif case not in ('publish_error', 'mutable_release', 'wrong_tag'):
            assert 'publish' not in events, (case, events)
        print('PASS', case)

    # Execute the unchanged workflow numbering code against published16 plus an
    # unrelated actiond99 release and a draft500 release.
    workflow = (root / '.github/workflows/build-and-publish-bazel.yml').read_text()
    assert 'group: bazel-9.3-release\n  cancel-in-progress: false' in workflow
    assert '--clobber' not in workflow
    start = workflow.index('        run: |\n') + len('        run: |\n')
    code = []
    for line in workflow[start:].splitlines():
        if line and not line.startswith('          '): break
        code.append(line[10:])
    output = temporary / 'github-output'
    env = dict(environment, MOCK_CASE='numbering', MOCK_STATE=str(temporary),
               INPUT_RELEASE_TAG='', GITHUB_OUTPUT=str(output))
    subprocess.run(['bash', '-c', '\n'.join(code)], env=env, check=True)
    assert output.read_text() == 'release_tag=9.3.0-dzbarsky17\n', output.read_text()
    print('PASS numbering_16_to_17')
print('All 18 mocked publication checks passed; no network calls or GitHub mutations.')
PY
