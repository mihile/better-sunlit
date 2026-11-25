"""Keep a complete console log and preserve the release process exit status."""
from pathlib import Path
import datetime
import os
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def resolve_java_home():
    javac_name = 'javac.exe' if os.name == 'nt' else 'javac'
    current = os.environ.get('JAVA_HOME', '').strip()
    if current and (Path(current) / 'bin' / javac_name).is_file():
        return current
    candidates = []
    for base, pattern in (
        (Path('C:/Program Files/Eclipse Adoptium'), 'jdk-17*'),
        (Path('C:/Java'), '*17*'),
        (Path('C:/Program Files/Java'), 'jdk-17*'),
    ):
        if base.is_dir():
            candidates.extend(sorted(base.glob(pattern), reverse=True))
    which_javac = shutil.which(javac_name)
    if which_javac:
        candidates.append(Path(which_javac).resolve().parents[1])
    for candidate in candidates:
        if (candidate / 'bin' / javac_name).is_file():
            return str(candidate)
    return current


def main():
    directory = ROOT/'build/release-logs'
    directory.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    log = directory/f'{stamp}-{os.getpid()}.log'
    print(f'실행 전체 로그: {log}', flush=True)
    env = os.environ.copy()
    java_home = resolve_java_home()
    if java_home and java_home != env.get('JAVA_HOME'):
        env['JAVA_HOME'] = java_home
        print(f'JDK 자동 감지: {java_home}', flush=True)
    command = [sys.executable, '-X', 'utf8', '-u', ROOT/'tools/release.py', *sys.argv[1:]]
    with log.open('w', encoding='utf8') as output:
        try:
            with subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
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
