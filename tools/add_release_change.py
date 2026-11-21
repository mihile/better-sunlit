"""Append an authored change to the next unpublished Better Sunlit release."""
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import argparse
import json
import subprocess
import sys
import tempfile

import release


def add_change(title: str, detail: str, remote_builds: dict[str, int], today: date) -> tuple[Path, bool]:
    title, detail = title.strip(), detail.strip()
    if (not title or len(title) > 80 or ' :: ' in title or not detail
            or len(title + ' :: ' + detail) > 1200
            or any(ord(char) < 32 for char in title + detail)):
        raise RuntimeError('제목과 설명은 한 줄로 작성하세요. 제목은 80자, 제목과 설명은 합쳐서 1200자까지입니다.')

    directory = release.ROOT / 'releases'
    directory.mkdir(exist_ok=True)
    used = max(remote_builds.values(), default=0)
    if any(directory.glob('*.json')):
        path, data = release.latest_release_meta()
    else:
        path, data = None, {}

    if path is None or int(data['build']) <= used:
        stamp = today.strftime('%Y%m%d')
        # Keep the version above any published or reserved build, even across dates.
        if used >= int(stamp + '01'):
            digits = str(used)
            stamp = digits[:8]
            sequence = int(digits[8:]) + 1
        else:
            sequence = 1
        path = directory / f'{stamp}.{sequence}.json'
        data = {
            'build': int(f'{stamp}{sequence:02d}'),
            'label': f'{stamp[:4]}.{stamp[4:6]}.{stamp[6:8]}.{sequence}',
            'title': '기능 개선 및 오류 수정',
            'changes': [],
        }
        if path.exists():
            raise RuntimeError(f'릴리스 이름이 이미 사용 중입니다: {path.name}')

    if 'changes' not in data:
        raise RuntimeError(f'미배포 파일 {path.name}의 변경 내역은 changes 형식으로 작성하세요.')
    entry = {'title': title, 'detail': detail}
    entries = data['changes']
    if entry in entries:
        return path, False
    if len(entries) >= 40:
        raise RuntimeError(f'{path.name}에 변경 내역이 40개 있습니다. 관련 항목을 합친 뒤 다시 실행하세요.')
    entries.append(entry)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='\n', dir=directory,
                                         prefix='.release-', suffix='.tmp', delete=False) as output:
            temporary = Path(output.name)
            output.write(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return path, True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--title', required=True)
    parser.add_argument('--detail', required=True)
    args = parser.parse_args()
    with release.release_lock():
        builds = release.known_remote_builds()
        today = datetime.now(timezone(timedelta(hours=9))).date()
        path, added = add_change(args.title, args.detail, builds, today)
    relative = path.relative_to(release.ROOT)
    print(f'{relative}: {"변경 내역 추가 완료" if added else "이미 같은 변경 내역이 있습니다"}. 작업 파일과 함께 커밋하세요.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError, ValueError, KeyError) as error:
        print(f'[오류] 미배포 변경 내역을 기록하지 못했습니다: {error}', file=sys.stderr)
        sys.exit(1)
