"""Build, verify and publish one or both Better Sunlit channels."""
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
INSTANCE = ROOT.parents[1]
SIDES = ('client', 'server')
CHANNEL_FILES = ('feed/update.properties', 'release-channel.properties')
REPOSITORY = 'mihile/better-sunlit'


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


def save_work():
    if git('status', '--porcelain'):
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


def choose_release(sides, remote_builds):
    path, data = latest_release_meta()
    published = max(remote_builds[side] for side in sides)
    if int(data['build']) <= published:
        today = int(datetime.date.today().strftime('%Y%m%d')) * 100 + 1
        number = max(today, max(remote_builds.values()) + 1, int(data['build']) + 1)
        value = str(number)
        date, sequence = value[:8], int(value[8:])
        label = f'{date[:4]}.{date[4:6]}.{date[6:8]}.{sequence}'
        data = dict(data, build=number, label=label)
        path = ROOT / 'releases' / f'{date}.{sequence}.json'
        if path.exists():
            raise RuntimeError(f'릴리스 파일이 이미 존재합니다: {path}')
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
        print(f'새 릴리스 설명: {path.name}', flush=True)
    return path, data


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


def assert_shared_equal(first, second):
    result = subprocess.run(['git', 'diff', '--quiet', first, second, '--', '.',
                             ':(exclude)feed', ':(exclude)release-channel.properties'], cwd=ROOT)
    if result.returncode != 0:
        raise RuntimeError('양쪽 브랜치의 공통 소스가 달라 검증한 산출물을 재사용할 수 없습니다.')


def publish_side(side, changes):
    if git('branch', '--show-current') != side:
        run(['git', 'checkout', side])
    # Even an apparently up-to-date local tracking ref can be stale while the
    # tests run. A normal push detects a concurrent remote change safely.
    run(['git', 'push', 'origin', side])
    step(f'{side} 서명 및 공개 검증', run,
         [sys.executable, '-X', 'utf8', 'tools/publish.py', '--side', side, '--changes', changes])


def local_build(side):
    if side != 'client':
        raise RuntimeError('로컬 적용은 클라이언트만 지원합니다.')
    _, data = latest_release_meta()
    build_both(data)
    step('전체 검사', run, [sys.executable, '-X', 'utf8', 'tools/test.py'])
    verify_artifacts(data)
    built_jar = ROOT / 'build' / side / 'better-sunlit.jar'
    target_jar = INSTANCE / 'minecraft' / 'mods' / 'better-sunlit.jar'
    target_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(built_jar, target_jar)
    print(f'로컬 클라이언트 모드 적용 완료: {target_jar}', flush=True)


def release(requested, prepare_only=False):
    initial = git('branch', '--show-current')
    if initial not in SIDES:
        raise RuntimeError('client 또는 server 브랜치에서 실행하세요.')
    # Do all read-only network checks before making local commits or changing branches.
    step('원격 브랜치 확인', run, ['git', 'fetch', '--no-tags', 'origin'])
    remote_builds = step('공개 릴리스 및 태그 확인', known_remote_builds)
    save_work()
    update_local_branches(initial)
    other = 'server' if initial == 'client' else 'client'
    step('공통 변경 동기화', sync_from, other)

    sides = [initial, other] if requested == 'both' else [requested]
    if sides[0] != initial:
        run(['git', 'checkout', sides[0]])
        step('대상 브랜치 동기화', sync_from, initial)
    changes, data = choose_release(sides, remote_builds)
    print(f"릴리스: {data['label']} (build {data['build']}) / {', '.join(sides)}", flush=True)
    save_work()

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
        for index, side in enumerate(sides):
            if index:
                run(['git', 'checkout', side])
                step('두 번째 브랜치 동기화', sync_from, sides[0])
                assert_shared_equal(sides[0], side)
                verify_artifacts(data)
            publish_side(side, changes)
            completed.append(side)
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
    parser.add_argument('--prepare-only', action='store_true', help='build, test and sign without pushing or publishing')
    args = parser.parse_args()
    if args.local_only and args.prepare_only:
        parser.error('--local-only and --prepare-only cannot be combined')
    with release_lock():
        if args.local_only:
            local_build(args.side)
        else:
            release(args.side, args.prepare_only)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError, KeyError) as error:
        print(f'\n[오류] 배포가 완료되지 않았습니다: {error}', file=sys.stderr, flush=True)
        sys.exit(1)
