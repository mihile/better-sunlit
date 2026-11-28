"""Publish the three Better Sunlit variants together for manual downloads."""
from pathlib import Path
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

from publication_guard import check_git_tree, check_release_jar
from publish import FILENAME, REPO, ROOT, origin_feed, release_feed_metadata, require, sha256


ASSETS = {
    'server': 'better-sunlit-server.jar',
    'client': 'better-sunlit-client.jar',
    'client-only': 'better-sunlit-client-only.jar',
}
LABELS = {
    'server': 'Better Sunlit Server.jar',
    'client': 'Better Sunlit Client.jar',
    'client-only': 'Better Sunlit Client Only.jar',
}
BUILD_META = 'assets/better_sunlit/update-build.properties'
JAVA_HOME = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'))


def run(*command, cwd=ROOT):
    subprocess.run(list(map(str, command)), cwd=cwd, check=True)


def read(*command):
    return subprocess.check_output(list(map(str, command)), cwd=ROOT, text=True, encoding='utf8').strip()


def downloads_inputs():
    feeds = {side: origin_feed(side) for side in ('client', 'server')}
    releases = {side: release_feed_metadata(side, data.decode('utf8')) for side, data in feeds.items()}
    ref = 'refs/heads/client-only'
    line = read('git', 'ls-remote', '--exit-code', '--heads', 'origin', ref)
    commit, actual_ref = line.split()
    require(actual_ref == ref and re.fullmatch(r'[a-f0-9]{40}', commit), 'client-only 브랜치 확인 실패')
    tag = f"downloads-{releases['client']['label']}-{releases['server']['label']}-{commit[:12]}"
    return tag, commit, feeds, releases


def verify_channel(side, source, feed, metadata):
    jar = source / FILENAME
    key = ROOT / 'keys' / f'{side}-public.der'
    require((source/'update.properties').read_bytes() == feed, f'{side} 릴리스와 원본 피드가 다릅니다.')
    require(sha256(jar) == metadata['sha256'], f'{side} 다운로드 해시가 피드와 다릅니다.')
    check_release_jar(jar)
    with zipfile.ZipFile(jar) as bundle:
        require(bundle.read('assets/better_sunlit/update-public-key.der') == key.read_bytes(),
                f'{side} JAR의 공개 검증 키가 저장소의 키와 다릅니다.')
        named_install = 'namedInstall=true' in bundle.read(BUILD_META).decode('utf8').splitlines()
    bin_dir = JAVA_HOME / 'bin'
    run(bin_dir/'javac.exe', '--release', '17', '-cp', jar, '-d', source, ROOT/'tools/VerifyRelease.java')
    run(bin_dir/'java.exe', '-cp', str(source) + os.pathsep + str(jar),
        'VerifyRelease', source/'update.properties', jar)
    return jar, named_install


def build_client_only(commit, working):
    run('git', 'fetch', '--no-tags', '--no-write-fetch-head', 'origin', commit)
    check_git_tree(ROOT, commit)
    archive = working / 'source.zip'
    run('git', 'archive', '--format=zip', f'--output={archive}', commit)
    checkout = working / 'client-only-source'
    with zipfile.ZipFile(archive) as sources:
        sources.extractall(checkout)
    run(sys.executable, '-X', 'utf8', 'build.py', '--instance', ROOT.parents[1],
        '--side', 'client', '--java-home', JAVA_HOME, cwd=checkout)
    output = checkout / 'build/client'
    jar = output / FILENAME
    report = json.loads((output/'build-report.json').read_text(encoding='utf8'))
    require(sha256(jar) == report['sha256'], 'client-only JAR과 빌드 해시가 다릅니다.')
    check_release_jar(jar)
    with zipfile.ZipFile(jar) as bundle:
        metadata = dict(line.split('=', 1) for line in bundle.read(BUILD_META).decode('utf8').splitlines() if '=' in line)
        menu = 'dev/sunlit/bettersunlit/client/'
        require(metadata.get('channel') == 'client' and metadata.get('track') == 'client-only'
                and menu + 'BetterSunlitMenu.class' in bundle.namelist()
                and menu + 'UpdateClient.class' not in bundle.namelist(), 'client-only 빌드에 업데이트 기능이 포함되어 있습니다.')
    return jar, report['label']


def prepare_assets(tag, commit, feeds, releases):
    folder = ROOT / 'build/downloads' / tag
    folder.mkdir(parents=True, exist_ok=True)
    rename_required = []
    with tempfile.TemporaryDirectory(prefix='bs-downloads-') as temporary:
        working = Path(temporary)
        for side in ('client', 'server'):
            source = working / side
            source.mkdir()
            run('gh', 'release', 'download', releases[side]['tag'], '--repo', REPO,
                '--dir', source, '--pattern', FILENAME, '--pattern', 'update.properties')
            jar, named_install = verify_channel(side, source, feeds[side], releases[side])
            shutil.copyfile(jar, folder/ASSETS[side])
            if not named_install:
                rename_required.append(f'`{ASSETS[side]}`')
        jar, label = build_client_only(commit, working)
        shutil.copyfile(jar, folder/ASSETS['client-only'])

    hashes = {name: sha256(folder/name) for name in ASSETS.values()}
    install = '사용하는 환경에 맞는 파일 하나를 골라 받은 이름 그대로 `mods` 폴더에 넣으세요.'
    if rename_required:
        install += '\n' + ', '.join(rename_required) + '의 경우 인게임 업데이트에 `better-sunlit.jar`라는 이름이 필요합니다.'
    notes = f"""## 다운로드

| 파일 | 설치 대상 | 빌드 |
| --- | --- | --- |
| `{ASSETS['server']}` | 서버 | [{releases['server']['label']}](https://github.com/{REPO}/releases/tag/{releases['server']['tag']}) |
| `{ASSETS['client']}` | 일반 클라이언트 | [{releases['client']['label']}](https://github.com/{REPO}/releases/tag/{releases['client']['tag']}) |
| `{ASSETS['client-only']}` | 서버에 Better Sunlit을 설치하지 않고 클라이언트만 쓸 때 | [{label} ({commit[:7]})](https://github.com/{REPO}/tree/{commit}) |

{install}
클라이언트 전용판은 인게임 업데이트를 제공하지 않습니다.

<details>
<summary>SHA-256</summary>

""" + ''.join(f'    {digest}  {name}\n' for name, digest in hashes.items()) + '\n</details>\n'
    (folder/'notes.md').write_text(notes, encoding='utf8')
    (folder/'hashes.json').write_text(json.dumps(hashes, indent=2), encoding='utf8')
    labels = [item['label'] for item in releases.values()] + [label]
    return max(labels, key=lambda value: tuple(map(int, value.split('.'))))


def verify_assets(tag, commit, folder):
    expected = json.loads((folder/'hashes.json').read_text(encoding='utf8'))
    require(expected.keys() == set(ASSETS.values()), '다운로드 검사 목록의 JAR 종류가 다릅니다.')
    release = json.loads(read('gh', 'release', 'view', tag, '--repo', REPO, '--json', 'assets,targetCommitish'))
    require(release['targetCommitish'] == commit and {asset['name'] for asset in release['assets']} == expected.keys(),
            'GitHub 릴리스의 소스 커밋 또는 첨부 파일 목록이 다릅니다.')
    with tempfile.TemporaryDirectory(dir=folder, prefix='verify-') as temporary:
        patterns = [item for name in expected for item in ('--pattern', name)]
        run('gh', 'release', 'download', tag, '--repo', REPO, '--dir', temporary, *patterns)
        for name, digest in expected.items():
            require(sha256(Path(temporary)/name) == digest, f'GitHub 다운로드 해시 불일치: {name}')


def publish_downloads(prepare_only=False):
    tag, commit, feeds, releases = downloads_inputs()
    latest = read('gh', 'api', f'repos/{REPO}/releases/latest', '--jq', '.tag_name')
    if not prepare_only and latest == tag:
        print(f'최신 다운로드 묶음과 동일합니다: {tag}')
        return

    folder = ROOT / 'build/downloads' / tag
    pages = json.loads(read('gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100'))
    existing = next((item for page in pages for item in page if item['tag_name'] == tag), None)
    if existing is None:
        newest_label = prepare_assets(tag, commit, feeds, releases)
    if prepare_only:
        if existing is not None:
            verify_assets(tag, commit, folder)
        print(f'다운로드 묶음 사전 검증 완료: {folder}')
        return
    if existing is None:
        title = f'Better Sunlit {newest_label} · 다운로드'
        run('gh', 'release', 'create', tag, '--repo', REPO, '--target', commit,
            '--title', title, '--notes-file', folder/'notes.md', '--draft', '--latest=false',
            *(f'{folder/ASSETS[side]}#{LABELS[side]}' for side in ASSETS))
    verify_assets(tag, commit, folder)
    require(downloads_inputs()[0] == tag, '검증 중 배포 버전이 바뀌었습니다. 다운로드 묶음을 다시 실행하세요.')
    run('gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--latest')
    verify_assets(tag, commit, folder)
    require(read('gh', 'api', f'repos/{REPO}/releases/latest', '--jq', '.tag_name') == tag,
            'GitHub 최신 릴리스가 다운로드 묶음을 가리키지 않습니다.')
    print(f'공개 다운로드 묶음 및 재다운로드 검증 완료: https://github.com/{REPO}/releases/tag/{tag}')


if __name__ == '__main__':
    from release import release_lock

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-only', action='store_true')
    with release_lock():
        publish_downloads(parser.parse_args().prepare_only)
