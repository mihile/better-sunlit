"""Test the already-built client and server JARs without changing them."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
jdk = Path(os.environ.get('JAVA_HOME', 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot')) / 'bin'


def artifact_snapshot():
    snapshot = {}
    for side in ('client', 'server'):
        build = root / 'build' / side
        report_path = build / 'build-report.json'
        report_bytes = report_path.read_bytes()
        report = json.loads(report_bytes)
        jar_hash = hashlib.sha256((build / 'better-sunlit.jar').read_bytes()).hexdigest()
        helper_hash = hashlib.sha256((build / 'update-helper.jar').read_bytes()).hexdigest()
        if report['side'] != side or report['sha256'] != jar_hash or report['helperSha256'] != helper_hash:
            raise RuntimeError(f'{side} 빌드 보고서와 산출물이 일치하지 않습니다. 다시 빌드하세요.')
        snapshot[side] = (report_bytes, jar_hash, helper_hash)
    return snapshot


def run_tests(tasks):
    # These checks have separate output folders and only read the built JARs.
    # Keeping a complete log per task prevents simultaneous output from mixing.
    logs = root / 'build' / 'test-logs'
    logs.mkdir(parents=True, exist_ok=True)

    def execute(name, commands):
        start = time.monotonic()
        log = logs / (name + '.log')
        with log.open('wb') as output:
            for command in commands:
                result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT)
                if result.returncode:
                    return name, log, result.returncode, time.monotonic() - start
        return name, log, 0, time.monotonic() - start

    workers = min(3, os.cpu_count() or 1, len(tasks))
    print(f'--- 독립 검사 {len(tasks)}개 실행 (동시 {workers}개) ---', flush=True)
    failures = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(execute, name, commands) for name, commands in tasks]
        for future in as_completed(futures):
            name, log, code, duration = future.result()
            text = log.read_text(encoding='utf8', errors='replace')
            print(f'--- {name}: {"실패" if code else "완료"} ({duration:.1f}초) ---', flush=True)
            if code:
                print(text, file=sys.stderr, flush=True)
                failures.append(f'{name} (종료 코드 {code}, 로그: {log})')
            else:
                for line in text.splitlines():
                    if line.startswith(('PASS', 'PerfOptTests:')):
                        print(line, flush=True)
                warnings = sum('warning:' in line for line in text.splitlines())
                if warnings:
                    print(f'컴파일 경고 {warnings}건 (자세한 내용: {log})', flush=True)
    if failures:
        raise RuntimeError('검사 실패: ' + ', '.join(failures))


def main():
    before = artifact_snapshot()
    tasks = []
    for side in ('client', 'server'):
        jar = root / 'build' / side / 'better-sunlit.jar'
        out = root / 'build' / side / 'tests'
        out.mkdir(exist_ok=True)
        commands = [[jdk/'javac.exe', '--release', '17', '-encoding', 'UTF-8',
                     '-cp', jar, '-d', out, *sorted((root/'tests').glob('*.java'))]]
        for test in ('UpdaterTests', 'ServerRecoveryTests'):
            commands.append([jdk/'java.exe', '-cp', str(out) + os.pathsep + str(jar),
                             test, root/'build'/side/'update-helper.jar'])
        tasks.append((side + '-updater', commands))

    for name in ('test_font_compat', 'check_localization', 'test_inline_localization',
                 'test_world_entry', 'test_perf_opts'):
        tasks.append((name, [[sys.executable, '-X', 'utf8', '-u', root/'tools'/(name + '.py')]]))
    order = {'test_inline_localization': 0, 'test_font_compat': 1, 'test_world_entry': 2,
             'test_perf_opts': 3, 'client-updater': 4, 'server-updater': 5, 'check_localization': 6}
    tasks.sort(key=lambda task: order[task[0]])
    run_tests(tasks)

    if artifact_snapshot() != before:
        raise RuntimeError('검사 중 빌드 산출물이 변경되었습니다. 게시를 중단합니다.')
    print('PASS: 검사 전후 양쪽 배포 산출물이 동일합니다.', flush=True)


if __name__ == '__main__':
    main()
