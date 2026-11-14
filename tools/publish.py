"""Sign a build, publish its immutable release and verify the public feed."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from publication_guard import check_git_tree, check_release_jar

ROOT = Path(__file__).resolve().parents[1]
REPO = 'mihile/better-sunlit'
FILENAME = 'better-sunlit.jar'


def run(command, **kwargs):
    return subprocess.run(list(map(str, command)), check=True, **kwargs)


def output(command):
    return subprocess.check_output(list(map(str, command)), text=True, encoding='utf8').strip()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(args):
    build = ROOT / 'build' / args.side
    report = json.loads((build / 'build-report.json').read_text(encoding='utf8'))
    changes = json.loads(args.changes.read_text(encoding='utf8'))
    jar = build / FILENAME
    require(report['build'] == changes['build'] and report['label'] == changes['label']
            and report['side'] == args.side,
            f"{args.side} 빌드 보고서와 릴리스 설명의 빌드 번호·이름·채널이 다릅니다. 다시 빌드하세요.")
    require(sha256(jar) == report['sha256'] and jar.stat().st_size == report['size'],
            f'{args.side} JAR과 빌드 보고서의 해시 또는 크기가 다릅니다.')
    check_release_jar(jar)
    entries = changes.get('changes', changes.get('features', []))
    require(0 < len(entries) <= 40, '릴리스 변경 내역은 1~40개여야 합니다.')
    key = args.keys / (args.side + '.pk8')
    require(key.is_file() and not key.resolve().is_relative_to(ROOT.resolve()),
            f'{args.side} 개인 서명 키가 없거나 저장소 안에 있습니다. 원래 키를 확인하세요.')

    tag = args.side + '-build-' + changes['label']
    release = build / 'release'
    release.mkdir(exist_ok=True)
    (release / FILENAME).write_bytes(jar.read_bytes())
    props = {'schema': '1', 'mod': 'better_sunlit', 'channel': args.side, 'pack': 'sunlit-valley',
             'version': '1.0.0', 'filename': FILENAME, 'build': str(report['build']),
             'label': report['label'], 'title': changes['title'], 'tag': tag,
             'url': f'https://github.com/{REPO}/releases/download/{tag}/{FILENAME}',
             'size': str(report['size']), 'sha256': report['sha256']}
    for index, feature in enumerate(entries):
        title = feature['title']
        require(0 < len(title) <= 80 and ' :: ' not in title, '릴리스 변경 제목 형식이 올바르지 않습니다.')
        note = title + ' :: ' + feature['detail']
        require(len(note) <= 1200 and not any(ord(char) < 32 for char in note),
                '릴리스 변경 설명이 너무 길거나 제어 문자를 포함합니다.')
        props[f'notes.{index}'] = note

    def escape(value):
        return str(value).replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r')

    payload = build / 'payload.properties'
    payload.write_text(''.join(key + '=' + escape(value) + '\n' for key, value in props.items()), encoding='utf8')
    tools = build / 'maintainer-tools'
    tools.mkdir(exist_ok=True)
    java = args.java_home / 'bin/java.exe'
    javac = args.java_home / 'bin/javac.exe'
    run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', jar, '-d', tools,
         ROOT/'tools/SignRelease.java', ROOT/'tools/VerifyRelease.java'])
    run([java, '-cp', tools, 'SignRelease', 'sign', key, payload, release/'update.properties'])
    classpath = str(tools) + os.pathsep + str(jar)
    run([java, '-cp', classpath, 'VerifyRelease', release/'update.properties', release/FILENAME])
    (release/'SHA256SUMS.txt').write_text(''.join(sha256(release/name) + '  ' + name + '\n'
                                                   for name in [FILENAME, 'update.properties']), encoding='utf8')
    notes = '# ' + changes['title'] + ' · ' + args.side + '\n\n' + ''.join(
        '### ' + feature['title'] + '\n\n' + feature['detail'] + '\n\n' for feature in entries)
    (release/'notes.md').write_text(notes, encoding='utf8')
    return build, report, changes, jar, tag, release, java, classpath


def origin_feed(side):
    return subprocess.check_output(['gh', 'api', '-H', 'Accept: application/vnd.github.raw+json',
        f'repos/{REPO}/contents/feed/update.properties?ref={side}'], cwd=ROOT)


def verify_public_feed(side, expected, destination, commit=None):
    # Branch raw URLs can retain an older commit even with a unique query.
    # Require the live branch at GitHub's origin AND the immutable raw commit.
    require(origin_feed(side) == expected, f'{side} GitHub 원본 브랜치의 피드가 게시한 피드와 다릅니다.')
    if commit is None:
        ref = output(['git', '-C', ROOT, 'ls-remote', '--heads', 'origin', side])
        require(bool(ref), f'{side} 공개 브랜치를 찾지 못했습니다.')
        commit = ref.split()[0]
    url = f'https://raw.githubusercontent.com/{REPO}/{commit}/feed/update.properties'
    last_error = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'Cache-Control': 'no-cache',
                                                            'User-Agent': 'Better-Sunlit-Release'})
            with urllib.request.urlopen(request, timeout=15) as response:
                content = response.read()
            require(content == expected, f'{side} 게시 커밋의 공개 피드가 서명한 피드와 다릅니다.')
            destination.write_bytes(content)
            # A stale branch cache does not mean publication failed. Its origin
            # and exact commit above must still pass; genuine mismatches fail.
            try:
                branch_url = f'https://raw.githubusercontent.com/{REPO}/{side}/feed/update.properties'
                with urllib.request.urlopen(branch_url, timeout=5) as response:
                    branch_content = response.read()
                if branch_content != expected:
                    print(f'[안내] {side} 브랜치 다운로드 캐시 갱신 대기 중. 원본·게시 커밋 검증은 완료됐습니다.', flush=True)
            except (urllib.error.URLError, TimeoutError):
                print(f'[안내] {side} 브랜치 캐시 응답 확인 지연. 원본·게시 커밋 검증은 완료됐습니다.', flush=True)
            return
        except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(1)
    raise RuntimeError(f'{side} 공개 피드를 확인하지 못했습니다: {last_error}')


def verify_public_release(args, prepared, commit=None):
    build, report, changes, jar, tag, release, java, classpath = prepared
    check = build / 'public-verification'
    check.mkdir(exist_ok=True)
    run(['gh', 'release', 'download', tag, '--repo', REPO, '--dir', check, '--clobber',
         '--pattern', FILENAME, '--pattern', 'update.properties'])
    require((check/FILENAME).read_bytes() == jar.read_bytes(), '공개 JAR이 검증한 로컬 JAR과 다릅니다.')
    require((check/'update.properties').read_bytes() == (release/'update.properties').read_bytes(),
            '공개 릴리스 피드가 서명한 피드와 다릅니다.')
    run([java, '-cp', classpath, 'VerifyRelease', check/'update.properties', check/FILENAME])
    public_feed = check / 'public-feed.properties'
    verify_public_feed(args.side, (release/'update.properties').read_bytes(), public_feed, commit)
    run([java, '-cp', classpath, 'VerifyRelease', public_feed, check/FILENAME])
    print('공개 릴리스와 피드 재다운로드 및 서명 검증 완료:', REPO, tag, flush=True)


def verification_inputs(args):
    # Recovery reads the original artifacts and never signs or creates a release.
    build = ROOT / 'build' / args.side
    report = json.loads((build/'build-report.json').read_text(encoding='utf8'))
    changes = json.loads(args.changes.read_text(encoding='utf8'))
    jar = build/FILENAME
    require(report['side'] == args.side and report['build'] == changes['build'] and
            report['label'] == changes['label'] and sha256(jar) == report['sha256'],
            '재검증할 기존 빌드와 릴리스 설명이 일치하지 않습니다.')
    tools = build/'maintainer-tools'
    tools.mkdir(exist_ok=True)
    java = args.java_home/'bin/java.exe'
    run([args.java_home/'bin/javac.exe', '--release', '17', '-cp', jar, '-d', tools,
         ROOT/'tools/VerifyRelease.java'])
    return (build, report, changes, jar, args.side+'-build-'+changes['label'], build/'release',
            java, str(tools)+os.pathsep+str(jar))


def publish(args, prepared):
    build, report, changes, jar, tag, release, java, classpath = prepared
    branch = output(['git', '-C', ROOT, 'branch', '--show-current'])
    require(branch == args.side, '게시할 채널과 현재 Git 브랜치가 다릅니다.')
    require(not output(['git', '-C', ROOT, 'status', '--porcelain']), '게시 전에 소스 변경을 커밋하세요.')
    check_git_tree(ROOT, remote='origin/' + args.side)
    head = output(['git', '-C', ROOT, 'rev-parse', 'HEAD'])
    require(output(['git', '-C', ROOT, 'rev-parse', 'origin/' + args.side]) == head,
            '검증한 소스를 해당 원격 브랜치에 먼저 push하세요.')
    remote = output(['git', '-C', ROOT, 'ls-remote', '--heads', 'origin', args.side])
    require(remote.split()[:1] == [head], '검증 이후 원격 브랜치가 변경되었습니다. 다시 동기화하세요.')
    pages = json.loads(output(['gh', 'api', '--paginate', '--slurp', f'repos/{REPO}/releases?per_page=100']))
    for old in (item for page in pages for item in page):
        old_tag = old.get('tag_name', '')
        require(old_tag != tag, f'{tag} 릴리스가 이미 존재합니다.')
        match = re.fullmatch(re.escape(args.side) + r'-build-(\d{4})\.(\d{2})\.(\d{2})\.(\d+)', old_tag)
        if match and not old.get('draft'):
            year, month, day, sequence = match.groups()
            require(report['build'] > int(year + month + day + sequence.zfill(2)),
                    f'{args.side} 빌드 번호가 기존 공개 빌드보다 커야 합니다.')

    created = False
    feed_pushed = False
    try:
        run(['gh', 'release', 'create', tag, '--repo', REPO, '--target', head,
             '--title', changes['title'] + ' · ' + args.side, '--notes-file', release/'notes.md',
             '--latest=false', release/FILENAME, release/'update.properties', release/'SHA256SUMS.txt'])
        created = True
        # Advance only this channel after its immutable signed assets exist.
        feed = ROOT / 'feed'
        feed.mkdir(exist_ok=True)
        (feed/'update.properties').write_bytes((release/'update.properties').read_bytes())
        run(['git', '-C', ROOT, 'add', 'feed/update.properties'])
        run(['git', '-C', ROOT, 'commit', '-m', 'Publish ' + args.side + ' feed ' + changes['label']])
        run(['git', '-C', ROOT, 'push', 'origin', args.side])
        feed_pushed = True

        verify_public_release(args, prepared, output(['git', '-C', ROOT, 'rev-parse', 'HEAD']))
    except Exception:
        if created:
            state = '피드는 push되었지만 공개 검증이 완료되지 않았습니다' if feed_pushed else '피드 push가 완료되지 않았을 수 있습니다'
            print(f'[주의] {tag} 릴리스는 생성되었습니다. {state}.', file=sys.stderr, flush=True)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--side', choices=['client', 'server'], required=True)
    parser.add_argument('--changes', type=Path, default=ROOT/'releases/20261108.1.json')
    parser.add_argument('--keys', type=Path, default=Path.home()/'.codex/secrets/better-sunlit')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--java-home', type=Path, default=Path(os.environ.get(
        'JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')))
    args = parser.parse_args()
    if args.verify_only:
        if args.prepare_only:
            parser.error('--verify-only and --prepare-only cannot be combined')
        verify_public_release(args, verification_inputs(args))
        return
    prepared = prepare(args)
    if not args.prepare_only:
        publish(args, prepared)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError, KeyError) as error:
        print(f'[오류] 서명/게시 실패: {error}', file=sys.stderr, flush=True)
        sys.exit(1)
