"""Build, verify and publish one or both Better Sunlit channels."""
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from publication_guard import check_worktree, check_git_tree
from publish import release_feed_metadata
from downloads import publish_downloads

ROOT = Path(__file__).resolve().parents[1]
INSTANCE = ROOT.parents[1]
SIDES = ('client', 'server')
CHANNEL_FILES = ('feed/update.properties', 'release-channel.properties')
REPOSITORY = 'mihile/better-sunlit'
KOREA_TIME = timezone(timedelta(hours=9))


def korea_today():
    return datetime.now(KOREA_TIME).date()


def run(cmd, *, check=True):
    print('>>', ' '.join(map(str, cmd)), flush=True)
    return subprocess.run(list(map(str, cmd)), cwd=ROOT, check=check)


def out(cmd):
    return subprocess.check_output(list(map(str, cmd)), cwd=ROOT, text=True, encoding='utf8').strip()


def git(*args):
    return out(['git', *args])


def step(name, function, *args):
    print(f'\n=== {name} ===', flush=True)
    start = time.monotonic()
    result = function(*args)
    print(f'=== {name} 완료 ({time.monotonic() - start:.1f}초) ===', flush=True)
    return result


@contextmanager
def release_lock():
    # A byte-range lock is released by the OS even if a window is closed.
    # The lock file intentionally stays in .git so there is no stale PID file.
    lock_path = Path(git('rev-parse', '--git-path', 'better-sunlit-release.lock'))
    if not lock_path.is_absolute():
        lock_path = ROOT / lock_path
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open('a+b') as lock:
        lock.seek(0, os.SEEK_END)
        if lock.tell() == 0:
            lock.write(b'0')
            lock.flush()
        lock.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError('다른 Better Sunlit 빌드/배포가 실행 중입니다. 완료 후 다시 실행하세요.') from error
        try:
            yield
        finally:
            lock.seek(0)
            if os.name == 'nt':
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def latest_release_meta():
    releases = []
    for path in (ROOT / 'releases').glob('*.json'):
        data = json.loads(path.read_text(encoding='utf8'))
        releases.append((int(data['build']), path, data))
    if not releases:
        raise RuntimeError('releases 폴더에 릴리스 설명이 없습니다.')
    _, path, data = max(releases, key=lambda item: item[0])
    return path, data


def known_remote_builds():
    # Include drafts and orphaned tags, too: neither may be overwritten.
    pages = json.loads(out(['gh', 'api', '--paginate', '--slurp',
                            f'repos/{REPOSITORY}/releases?per_page=100']))
    tags = [item.get('tag_name', '') for page in pages for item in page]
    refs = out(['git', 'ls-remote', '--tags', '--refs', 'origin'])
    tags.extend(line.split('refs/tags/', 1)[1] for line in refs.splitlines() if 'refs/tags/' in line)
    builds = {side: 0 for side in SIDES}
    for tag in tags:
        match = re.fullmatch(r'(client|server)-build-(\d{4})\.(\d{2})\.(\d{2})\.(\d+)', tag)
        if match:
            side, year, month, day, sequence = match.groups()
            number = int(year + month + day + sequence.zfill(2))
            builds[side] = max(builds[side], number)
    return builds


def needs_release(side):
    remote = f'origin/{side}'
    feed = 'feed/update.properties'
    if not git('ls-tree', '--name-only', remote, '--', feed):
        return True

    metadata = release_feed_metadata(side, git('show', f'{remote}:{feed}'))
    tag = metadata['tag']
    ref = f'refs/tags/{tag}'
    run(['git', 'fetch', '--no-tags', '--no-write-fetch-head', 'origin', f'{ref}:{ref}'])
    paths = ['src/main', 'build.py', 'boot-agent/src', 'tools/build_boot.py', f'keys/{side}-public.der']
    if side != 'client':
        paths.append(':(exclude,glob)src/main/java/**/client/**')
    if git('diff', '--name-only', f'{ref}^{{commit}}', 'HEAD', '--', *paths):
        return True

    print(f'{side}: 공개 릴리스 {tag} 이후 JAR 빌드 입력 변경 없음. 배포를 건너뜁니다.', flush=True)
    return False


def save_work():
    if git('status', '--porcelain'):
        check_worktree(ROOT)
        branch = git('branch', '--show-current')
        run(['git', 'add', '-A'])
        if git('diff', '--cached', '--name-only'):
            run(['git', 'commit', '-m', f'Save changes on {branch} before release'])


def update_local_branches(initial):
    for side in SIDES:
        if git('branch', '--show-current') != side:
            run(['git', 'checkout', side])
        # This accepts local commits, advances a behind-only branch, and stops
        # on divergence instead of force-pushing or overwriting someone else's work.
        run(['git', 'merge', '--ff-only', f'origin/{side}'])
    if git('branch', '--show-current') != initial:
        run(['git', 'checkout', initial])


def sync_from(other):
    """Merge common history, keeping the current channel's own feed and selector."""
    current = git('branch', '--show-current')
    ancestor = subprocess.run(['git', 'merge-base', '--is-ancestor', other, 'HEAD'], cwd=ROOT)
    if ancestor.returncode == 0:
        return
    if ancestor.returncode != 1:
        raise RuntimeError(f'{other} 브랜치 이력을 확인하지 못했습니다.')

    result = run(['git', 'merge', '--no-ff', '--no-commit', '--no-edit', other], check=False)
    merge_head = subprocess.run(['git', 'rev-parse', '-q', '--verify', 'MERGE_HEAD'],
                                cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if merge_head.returncode != 0:
        if result.returncode:
            raise RuntimeError(f'{other} 브랜치 병합을 시작하지 못했습니다.')
        return
    try:
        conflicts = set(git('diff', '--name-only', '--diff-filter=U', '-z').split('\0')) - {''}
        unexpected = sorted(conflicts - set(CHANNEL_FILES))
        if unexpected:
            raise RuntimeError('공통 파일 병합 충돌: ' + ', '.join(unexpected) +
                               '. 자동 병합을 취소했습니다. 두 브랜치의 변경을 확인하세요.')
        # Always restore these files, even when Git merged them without a conflict.
        run(['git', 'restore', '--source=HEAD', '--staged', '--worktree', '--', *CHANNEL_FILES])
        if result.returncode and not conflicts:
            raise RuntimeError('브랜치 병합이 실패했습니다.')
        run(['git', 'commit', '-m', f'Sync shared changes from {other} into {current}'])
    except Exception:
        run(['git', 'merge', '--abort'], check=False)
        raise


def next_release_identity(day, used):
    stamp = day.strftime('%Y%m%d')
    base = int(stamp) * 100
    sequence = max(1, used - base + 1)
    if sequence > 99:
        raise RuntimeError(f'한국 날짜 {day}로 기존 원격 빌드 {used}보다 높은 번호를 만들 수 없습니다. '
                           '시스템 시각과 공개 릴리스 번호를 확인하세요.')
    label = f'{day:%Y.%m.%d}.{sequence}'
    path = ROOT / 'releases' / f'{stamp}.{sequence}.json'
    return path, base + sequence, label


def choose_release(sides, remote_builds, release_day):
    path, data = latest_release_meta()
    draft_build = int(data['build'])
    used = max(remote_builds.values())
    published = max(remote_builds[side] for side in sides)
    if draft_build <= published:
        existing = ', '.join(f'{side}={remote_builds[side]}' for side in sides)
        raise RuntimeError(
            f'미게시 소스 변경은 있지만, 가장 최근 설명 releases/{path.name}의 build={data["build"]}은 '
            f'원격에 이미 등록된 번호 이하입니다 ({existing}).\n'
            'tools/add_release_change.py에 이번 작업의 제목과 설명을 전달해 기록한 뒤 같은 BAT를 다시 실행하세요.')
    entries = data.get('changes')
    if not entries:
        raise RuntimeError(f'{path.name}의 changes에 이번 버전의 변경 내역을 작성하세요.')
    for previous_path in (ROOT / 'releases').glob('*.json'):
        previous = json.loads(previous_path.read_text(encoding='utf8'))
        if (int(previous['build']) in {remote_builds[side] for side in sides} and
                entries == previous.get('changes', previous.get('features'))):
            raise RuntimeError(f'{path.name}의 변경 내역이 {previous_path.name}과 같습니다. 이번 변경 사항을 작성하세요.')

    if draft_build >= used and draft_build // 100 == int(release_day.strftime('%Y%m%d')):
        return path, data

    dated_path, build, label = next_release_identity(release_day, used)
    if dated_path.exists():
        raise RuntimeError(f'배포일 릴리스 설명 releases/{dated_path.name}이 이미 있습니다. 변경 내역을 확인하세요.')
    dated = {**data, 'build': build, 'label': label}
    write_release_meta(dated_path, dated)
    # If the other channel already used this build, its metadata remains part of
    # the published history. Carry the same notes forward for the pending channel.
    if draft_build > used:
        path.unlink()
    print(f'배포일 버전 확정: {path.name} → {dated_path.name}', flush=True)
    return dated_path, dated


def write_release_meta(path, data):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf8', newline='\n', dir=path.parent,
                                         prefix='.release-', suffix='.tmp', delete=False) as output:
            temporary = Path(output.name)
            output.write(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def build_both(data):
    logs = ROOT / 'build' / 'build-logs'
    logs.mkdir(parents=True, exist_ok=True)

    def build(side):
        start = time.monotonic()
        log = logs / (side + '.log')
        command = [sys.executable, '-X', 'utf8', 'build.py', '--instance', INSTANCE,
                   '--side', side, '--build', str(data['build']), '--label', data['label']]
        with log.open('wb') as output:
            result = subprocess.run(list(map(str, command)), cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        return side, log, result.returncode, time.monotonic() - start

    workers = min(2, os.cpu_count() or 1)
    print(f'\n=== 클라이언트·서버 빌드 (동시 {workers}개) ===', flush=True)
    failures = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for future in as_completed([pool.submit(build, side) for side in SIDES]):
            side, log, code, duration = future.result()
            text = log.read_text(encoding='utf8', errors='replace')
            print(f'=== {side} 빌드 {"실패" if code else "완료"} ({duration:.1f}초) ===', flush=True)
            if code:
                print(text, file=sys.stderr, flush=True)
                failures.append(f'{side} (종료 코드 {code}, 로그: {log})')
            else:
                warnings = sum('warning:' in line for line in text.splitlines())
                if warnings:
                    print(f'{side} 컴파일 경고 {warnings}건 (자세한 내용: {log})', flush=True)
    if failures:
        raise RuntimeError('빌드 실패: ' + ', '.join(failures))


def verify_artifacts(data):
    for side in SIDES:
        directory = ROOT / 'build' / side
        report = json.loads((directory / 'build-report.json').read_text(encoding='utf8'))
        digest = hashlib.sha256((directory / 'better-sunlit.jar').read_bytes()).hexdigest()
        if (report['side'] != side or report['build'] != data['build'] or
                report['label'] != data['label'] or report['sha256'] != digest):
            raise RuntimeError(f'{side} 산출물의 빌드 번호, 채널 또는 해시가 일치하지 않습니다.')


def shared_changes(first, second):
    return git('diff', '--name-only', first, second, '--', '.',
               ':(exclude)feed', ':(exclude)release-channel.properties')


def assert_shared_equal(first, second):
    if shared_changes(first, second):
        raise RuntimeError('양쪽 브랜치의 공통 소스가 달라 검증한 산출물을 재사용할 수 없습니다.')


def publish_side(side, changes):
    if git('branch', '--show-current') != side:
        run(['git', 'checkout', side])
    # Even an apparently up-to-date local tracking ref can be stale while the
    # tests run. A normal push detects a concurrent remote change safely.
    check_git_tree(ROOT, remote=f'origin/{side}')
    run(['git', 'push', 'origin', side])
    step(f'{side} 서명 및 공개 검증', run,
         [sys.executable, '-X', 'utf8', 'tools/publish.py', '--side', side, '--changes', changes])


def local_build_inputs(release_path):
    inputs = [
        ROOT / 'build.py',
        ROOT / 'tools' / 'build_boot.py',
        ROOT / 'keys' / 'client-public.der',
        release_path,
    ]
    for directory in (ROOT / 'src' / 'main', ROOT / 'boot-agent' / 'src'):
        if directory.is_dir():
            inputs.append(directory)
            inputs.extend(directory.rglob('*'))
    return inputs


def is_client_build_current(release_path, data):
    directory = ROOT / 'build' / 'client'
    built_jar = directory / 'better-sunlit.jar'
    report_path = directory / 'build-report.json'
    if not built_jar.is_file() or not report_path.is_file():
        return False
    try:
        report = json.loads(report_path.read_text(encoding='utf8'))
    except (OSError, ValueError):
        return False
    digest = hashlib.sha256(built_jar.read_bytes()).hexdigest()
    if (report.get('side') != 'client' or report.get('build') != data['build']
            or report.get('label') != data['label'] or report.get('sha256') != digest):
        return False
    built_mtime = built_jar.stat().st_mtime_ns
    for item in local_build_inputs(release_path):
        if item.exists() and item.stat().st_mtime_ns > built_mtime:
            return False
    return True


def local_build(side, force=False):
    if side != 'client':
        raise RuntimeError('로컬 적용은 클라이언트만 지원합니다.')
    release_path, data = latest_release_meta()
    built_jar = ROOT / 'build' / side / 'better-sunlit.jar'
    if not force and is_client_build_current(release_path, data):
        print(f'이미 최신 빌드가 있어 재빌드 없이 바로 적용합니다: {built_jar}', flush=True)
    else:
        build_both(data)
        step('전체 검사', run, [sys.executable, '-X', 'utf8', 'tools/test.py'])
        verify_artifacts(data)
    target_jar = INSTANCE / 'minecraft' / 'mods' / 'better-sunlit.jar'
    target_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built_jar, target_jar)
    print(f'로컬 클라이언트 모드 적용 완료: {target_jar}', flush=True)


def release(requested, prepare_only=False):
    release_day = korea_today()
    initial = git('branch', '--show-current')
    if initial not in SIDES:
        raise RuntimeError('client 또는 server 브랜치에서 실행하세요.')
    step('원격 브랜치 확인', run, ['git', 'fetch', '--no-tags', 'origin'])
    save_work()
    update_local_branches(initial)
    other = 'server' if initial == 'client' else 'client'
    if shared_changes(initial, other):
        step('공통 변경 동기화', sync_from, other)

    candidates = [initial, other] if requested == 'both' else [requested]
    sides = [side for side in candidates if needs_release(side)]
    if not sides:
        if not prepare_only:
            step('세 가지 용도의 다운로드 묶음', publish_downloads)
        return

    remote_builds = step('공개 릴리스 및 태그 확인', known_remote_builds)
    if sides[0] != initial:
        run(['git', 'checkout', sides[0]])
        step('대상 브랜치 동기화', sync_from, initial)
    changes, data = choose_release(sides, remote_builds, release_day)
    print(f"릴리스: {data['label']} (build {data['build']}) / {', '.join(sides)}", flush=True)
    save_work()
    if len(sides) == 2:
        run(['git', 'checkout', sides[1]])
        step('두 번째 브랜치 동기화', sync_from, sides[0])
        assert_shared_equal(sides[0], sides[1])
        run(['git', 'checkout', sides[0]])

    # Both JARs and the full cross-channel tests run exactly once per invocation.
    build_both(data)
    step('전체 검사', run, [sys.executable, '-X', 'utf8', 'tools/test.py'])
    verify_artifacts(data)
    for side in sides:
        step(f'{side} 서명 사전 검증', run, [sys.executable, '-X', 'utf8', 'tools/publish.py',
                                             '--side', side, '--changes', changes, '--prepare-only'])
    if prepare_only:
        print(f"\n=== 게시 전 준비 및 검증 완료: {', '.join(sides)} / {data['label']} ===", flush=True)
        return

    completed = []
    try:
        for side in sides:
            if side != sides[0]:
                run(['git', 'checkout', side])
            verify_artifacts(data)
            publish_side(side, changes)
            completed.append(side)
        step('세 가지 용도의 다운로드 묶음', publish_downloads)
    except Exception:
        if completed:
            print('이미 공개 완료된 채널: ' + ', '.join(completed) +
                  '. 나머지는 오류 해결 후 다시 배포하세요.', file=sys.stderr, flush=True)
        raise
    print(f"\n=== 배포 완료: {', '.join(completed)} / {data['label']} ===", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--side', choices=[*SIDES, 'both'], required=True)
    parser.add_argument('--local-only', action='store_true')
    parser.add_argument('--force', action='store_true', help='force rebuild even when the local build is already up to date')
    parser.add_argument('--prepare-only', action='store_true', help='build, test and sign without pushing or publishing')
    parser.add_argument('--verify-only', action='store_true', help='recheck existing published artifacts without rebuilding or publishing')
    args = parser.parse_args()
    if sum((args.local_only, args.prepare_only, args.verify_only)) > 1:
        parser.error('--local-only, --prepare-only and --verify-only cannot be combined')
    with release_lock():
        if args.verify_only:
            sides = SIDES if args.side == 'both' else (args.side,)
            for side in sides:
                step(f'{side} 기존 공개 빌드 재검증', run, [sys.executable, '-X', 'utf8', 'tools/publish.py',
                    '--side', side, '--verify-only'])
        elif args.local_only:
            local_build(args.side, force=args.force)
        else:
            release(args.side, args.prepare_only)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError, KeyError) as error:
        print(f'\n[오류] 배포가 완료되지 않았습니다: {error}', file=sys.stderr, flush=True)
        sys.exit(1)
