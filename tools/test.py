"""Test the already-built client and server JARs without changing them."""
from pathlib import Path
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


def run(name, command):
    start = time.monotonic()
    print(f'--- {name} ---', flush=True)
    subprocess.run(command, check=True)
    print(f'--- {name}: 완료 ({time.monotonic() - start:.1f}초) ---', flush=True)


def main():
    before = artifact_snapshot()
    for side in ('client', 'server'):
        jar = root / 'build' / side / 'better-sunlit.jar'
        out = root / 'build' / side / 'tests'
        out.mkdir(exist_ok=True)
        run(f'{side} 업데이트 검사 컴파일', [jdk/'javac.exe', '--release', '17', '-encoding', 'UTF-8',
            '-cp', jar, '-d', out, *sorted((root/'tests').glob('*.java'))])
        for test in ('UpdaterTests', 'ServerRecoveryTests'):
            run(f'{side} {test}', [jdk/'java.exe', '-cp', str(out) + os.pathsep + str(jar),
                test, root/'build'/side/'update-helper.jar'])

    for name in ('test_font_compat', 'check_localization', 'test_inline_localization',
                 'test_world_entry', 'test_perf_opts'):
        run(name, [sys.executable, '-X', 'utf8', root/'tools'/(name + '.py')])

    if artifact_snapshot() != before:
        raise RuntimeError('검사 중 빌드 산출물이 변경되었습니다. 게시를 중단합니다.')
    print('PASS: 검사 전후 양쪽 배포 산출물이 동일합니다.', flush=True)


if __name__ == '__main__':
    main()
