"""Prepare and verify exactly three JARs for a single public release."""
from pathlib import Path
import json
import os
import shutil
import sys
import tempfile
import zipfile

from publication_guard import check_git_tree, check_release_jar
from publish import (ASSETS, DEFAULT_JAVA_HOME, FILENAME, REPO, ROOT, SIDES,
                     origin_feed, output, release_feed_metadata, require, run,
                     sha256, sign_channel, verify_channel, verify_public_feed)

BUILD_META = 'assets/better_sunlit/update-build.properties'
ROLES = {
    'server': '멀티플레이 서버의 mods 폴더에 설치합니다. 해골 광산 엘리베이터 등 서버 연동 기능을 제공합니다.',
    'client': 'Better Sunlit이 설치된 서버에 접속하거나 싱글플레이할 때 사용합니다. 플레이어의 mods 폴더에 설치합니다.',
    'client-only': '서버에 Better Sunlit을 설치하지 않고 플레이어만 쓸 때 사용합니다. 인게임 자동 업데이트를 제공하지 않습니다.',
}


def release_notes(data):
    lines = [
        '## 어느 파일을 받으면 되나요?',
        '',
        '| 파일 | 설치 대상 |',
        '| --- | --- |',
        *(f'| `{ASSETS[side]}` | {role} |' for side, role in ROLES.items()),
        '',
        '환경에 맞는 JAR 하나를 골라 받은 파일명 그대로 `mods`에 넣으세요. 기존 Better Sunlit JAR은 교체하세요.',
        '',
        '일반 클라이언트와 서버는 새 버전의 통합 릴리스에서 각각 해당 파일을 내려받습니다.',
        '',
    ]
    if not data.get('editionOverview'):
        lines.extend(['## 변경 사항', ''])
        for entry in data['changes']:
            lines.extend([f"### {entry['title']}", '', entry['detail'], ''])
    return '\n'.join(lines)


def build_standalone(commit, release_file, data, destination):
    check_git_tree(ROOT, commit)
    with tempfile.TemporaryDirectory(prefix='bs-standalone-') as temporary:
        working = Path(temporary)
        archive = working/'source.zip'
        run(['git', 'archive', '--format=zip', f'--output={archive}', commit])
        checkout = working/'source'
        with zipfile.ZipFile(archive) as sources:
            sources.extractall(checkout)
        shutil.copy2(release_file, checkout/'releases'/release_file.name)
        subprocess.run([sys.executable, '-X', 'utf8', 'build.py', '--instance', str(ROOT.parents[1]),
                        '--side', 'client', '--build', str(data['build']), '--label', data['label'],
                        '--java-home', os.environ.get('JAVA_HOME', DEFAULT_JAVA_HOME)], cwd=checkout, check=True)
        build = checkout/'build/client'
        jar = build/FILENAME
        report = json.loads((build/'build-report.json').read_text(encoding='utf8'))
        require(sha256(jar) == report['sha256'], '클라이언트 전용 JAR과 빌드 해시가 다릅니다.')
        verify_standalone(jar, data)
        shutil.copyfile(jar, destination)


def verify_standalone(jar, data):
    check_release_jar(jar)
    with zipfile.ZipFile(jar) as bundle:
        metadata = dict(line.split('=', 1) for line in bundle.read(BUILD_META).decode('utf8').splitlines() if '=' in line)
        menu = 'dev/sunlit/bettersunlit/client/'
        require(metadata.get('channel') == 'client' and metadata.get('track') == 'client-only'
                and metadata.get('build') == str(data['build']) and metadata.get('label') == data['label']
                and menu + 'BetterSunlitMenu.class' in bundle.namelist()
                and menu + 'UpdateClient.class' not in bundle.namelist(),
                '클라이언트 전용 JAR의 빌드 번호 또는 업데이트 설정이 일치하지 않습니다.')


def prepare_bundle(folder, source_refs, commit, release_file, data):
    folder.mkdir(parents=True, exist_ok=True)
    for side in SIDES:
        shutil.copyfile(ROOT/'build'/side/FILENAME, folder/ASSETS[side])
        sign_channel(folder, side, data)
    build_standalone(commit, release_file, data, folder/ASSETS['client-only'])
    (folder/'notes.md').write_text(release_notes(data) + f'\n<!-- client-only: {commit} -->\n', encoding='utf8')
    plan = {'sources': source_refs, 'client-only': commit, 'build': data['build'],
            'notesSha256': sha256(release_file),
            'hashes': {name: sha256(folder/name) for name in ASSETS.values()}}
    staged = folder/'plan.tmp'
    staged.write_text(json.dumps(plan, indent=2), encoding='utf8')
    staged.replace(folder/'plan.json')
    return plan


def releases():
    pages = json.loads(output(['gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100']))
    return [item for page in pages for item in page]


def verify_downloads(folder, tag, target, data, feeds, hashes=None):
    release = json.loads(output(['gh', 'release', 'view', tag, '--repo', REPO,
                                 '--json', 'assets,targetCommitish']))
    require({asset['name'] for asset in release['assets']} == set(ASSETS.values())
            and (target is None or release['targetCommitish'] == target),
            'GitHub 릴리스에 있어야 하는 JAR 세 개 또는 소스 커밋이 일치하지 않습니다.')
    with tempfile.TemporaryDirectory(dir=folder, prefix='public-') as temporary:
        download = Path(temporary)
        patterns = [argument for name in ASSETS.values() for argument in ('--pattern', name)]
        run(['gh', 'release', 'download', tag, '--repo', REPO, '--dir', download, *patterns])
        for side in SIDES:
            verify_channel(folder, side, download/ASSETS[side], feeds[side])
        verify_standalone(download/ASSETS['client-only'], data)
        if hashes is not None:
            require({name: sha256(download/name) for name in ASSETS.values()} == hashes,
                    '공개 JAR 해시가 검증한 빌드와 다릅니다.')


def verify_latest():
    folder = ROOT/'build/unified/verify'
    folder.mkdir(parents=True, exist_ok=True)
    feeds = {}
    metadata = {}
    for side in SIDES:
        feed = origin_feed(side)
        path = folder/f'{side}.properties'
        path.write_bytes(feed)
        feeds[side] = path
        metadata[side] = release_feed_metadata(side, feed.decode('utf8'))
        verify_public_feed(side, feed, folder/f'{side}-raw.properties')
    client, server = metadata['client'], metadata['server']
    tag = client['tag']
    require(tag == server['tag'] == f"build-{client['label']}" and client['build'] == server['build'],
            '양쪽 업데이트 피드가 같은 통합 릴리스를 가리키지 않습니다.')
    verify_downloads(folder, tag, None, {'build': int(client['build']), 'label': client['label']}, feeds)
    require(output(['gh', 'api', f'repos/{REPO}/releases/latest', '--jq', '.tag_name']) == tag,
            '통합 릴리스에 GitHub Latest 표시가 없습니다.')
    print(f'공개 JAR 세 개와 양쪽 서명 피드 검사 완료: https://github.com/{REPO}/releases/tag/{tag}', flush=True)
