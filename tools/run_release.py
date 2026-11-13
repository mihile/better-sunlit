"""Keep a complete console log and preserve the release process exit status."""
from pathlib import Path
import datetime
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    directory = ROOT/'build/release-logs'
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    log = directory/f'{stamp}-{os.getpid()}.log'
    print(f'실행 전체 로그: {log}', flush=True)
    command = [sys.executable, '-X', 'utf8', '-u', ROOT/'tools/release.py', *sys.argv[1:]]
    with log.open('w', encoding='utf8') as output:
        try:
            with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, encoding='utf8', errors='replace', bufsize=1) as process:
                for line in process.stdout:
                    output.write(line)
                    output.flush()
                    print(line, end='', flush=True)
                code = process.wait()
        except OSError as error:
            message = f'[오류] 실행하지 못했습니다: {error}\n'
            output.write(message)
            print(message, end='', flush=True)
            code = 1
    print(f'실행 {"완료" if code == 0 else "실패"} (종료 코드 {code}). 로그: {log}', flush=True)
    return code


if __name__ == '__main__':
    sys.exit(main())
