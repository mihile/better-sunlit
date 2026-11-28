"""Sign and verify the two update feeds for a single Better Sunlit release."""
from pathlib import Path
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request

from publication_guard import check_release_jar


ROOT = Path(__file__).resolve().parents[1]
REPO = 'mihile/better-sunlit'
SIDES = ('client', 'server')
FILENAME = 'better-sunlit.jar'
ASSETS = {side: f'better-sunlit-{side}.jar' for side in ('server', 'client', 'client-only')}
STATE = 'feed/update.properties'
DEFAULT_JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'


def run(command, **kwargs):
    return subprocess.run(list(map(str, command)), cwd=ROOT, check=True, **kwargs)


def output(command):
    return subprocess.check_output(list(map(str, command)), cwd=ROOT, text=True, encoding='utf8').strip()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def release_feed_metadata(side, envelope):
    try:
        encoded = next(line.removeprefix('payload=') for line in envelope.splitlines()
                       if line.startswith('payload='))
        payload = base64.b64decode(encoded, validate=True).decode('utf8')
        metadata = dict(line.split('=', 1) for line in payload.splitlines() if '=' in line)
        label = metadata['label']
        if (metadata['channel'] != side or not re.fullmatch(r'\d{4}\.\d{2}\.\d{2}\.[1-9]\d?', label)
                or metadata['tag'] not in (f'build-{label}', f'{side}-build-{label}')):
            raise ValueError('invalid release identity')
        return metadata
    except (StopIteration, ValueError, KeyError) as error:
        raise RuntimeError(f'{side} 피드에서 릴리스 태그를 확인하지 못했습니다.') from error


def origin_feed(side, ref=None):
    return subprocess.check_output(['gh', 'api', '-H', 'Accept: application/vnd.github.raw+json',
        f'repos/{REPO}/contents/{STATE}?ref={ref or side}'], cwd=ROOT)


def verifier(folder, side):
    bin_dir = Path(os.environ.get('JAVA_HOME', DEFAULT_JAVA_HOME)) / 'bin'
    classes = folder / 'verifier' / side
    resources = classes / 'assets/better_sunlit'
    resources.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT/'keys'/f'{side}-public.der', resources/'update-public-key.der')
    (resources/'update-build.properties').write_text(f'build=0\nlabel=verify\nchannel={side}\n', encoding='utf8')
    updater = ROOT/'src/main/java/dev/sunlit/bettersunlit/update'
    run([bin_dir/'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-d', classes,
         ROOT/'tools/SignRelease.java', ROOT/'tools/VerifyRelease.java',
         *(updater/f'{name}.java' for name in ('Build', 'SignedRelease', 'UpdateFiles'))])
    return bin_dir/'java.exe', classes


def notes_for_feed(changes):
    entries = changes['changes']
    require(0 < len(entries) <= 40, '릴리스 항목은 1~40개여야 합니다.')
    result = {}
    for i, entry in enumerate(entries):
        title, detail = entry['title'], entry['detail']
        note = f'{title} :: {detail}'
        require(0 < len(title) <= 80 and ' :: ' not in title and 0 < len(detail)
                and len(note) <= 1200 and all(ord(char) >= 32 for char in note), '릴리스 설명 형식이 올바르지 않습니다.')
        result[f'notes.{i}'] = note
    return result


def sign_channel(folder, side, changes):
    jar = folder / ASSETS[side]
    check_release_jar(jar)
    report = json.loads((ROOT/'build'/side/'build-report.json').read_text(encoding='utf8'))
    require((report['side'], report['build'], report['label'], report['sha256'], report['size']) ==
            (side, changes['build'], changes['label'], sha256(jar), jar.stat().st_size),
            f'{side} 빌드 보고서와 릴리스 JAR이 일치하지 않습니다.')
    tag = f"build-{changes['label']}"
    props = {'schema': '1', 'mod': 'better_sunlit', 'channel': side, 'pack': 'sunlit-valley',
             'version': '1.0.0', 'filename': ASSETS[side], 'build': str(report['build']),
             'label': report['label'], 'title': changes['title'], 'tag': tag,
             'url': f'https://github.com/{REPO}/releases/download/{tag}/{ASSETS[side]}',
             'size': str(report['size']), 'sha256': report['sha256'], **notes_for_feed(changes)}
    payload = folder / f'{side}-payload.properties'
    def escape(value):
        return str(value).replace('\\', '\\\\').replace('\r', '\\r').replace('\n', '\\n')

    payload.write_text(''.join(f'{key}={escape(value)}\n' for key, value in props.items()), encoding='utf8')
    key = Path.home()/'.codex/secrets/better-sunlit'/f'{side}.pk8'
    require(key.is_file() and not key.resolve().is_relative_to(ROOT.resolve()), f'{side} 원래 서명 키를 확인하세요.')
    java, classes = verifier(folder, side)
    feed = folder / f'{side}.properties'
    run([java, '-cp', classes, 'SignRelease', 'sign', key, payload, feed])
    run([java, '-cp', classes, 'VerifyRelease', feed, jar])


def verify_channel(folder, side, jar, feed):
    java, classes = verifier(folder, side)
    run([java, '-cp', classes, 'VerifyRelease', feed, jar])


def verify_public_feed(side, expected, destination, commit=None):
    require(origin_feed(side) == expected, f'{side} GitHub 원본 브랜치의 피드가 게시한 피드와 다릅니다.')
    if commit is None:
        ref = output(['git', 'ls-remote', '--heads', 'origin', side])
        require(bool(ref), f'{side} 공개 브랜치를 찾지 못했습니다.')
        commit = ref.split()[0]
    url = f'https://raw.githubusercontent.com/{REPO}/{commit}/{STATE}'
    last_error = None
    for attempt in range(3):
        try:
            request = urllib.request.Request(url, headers={'Cache-Control': 'no-cache',
                                                            'User-Agent': 'Better-Sunlit-Release'})
            with urllib.request.urlopen(request, timeout=15) as response:
                content = response.read()
            require(content == expected, f'{side} 게시 커밋의 공개 피드가 서명한 피드와 다릅니다.')
            destination.write_bytes(content)
            try:
                branch_url = f'https://raw.githubusercontent.com/{REPO}/{side}/{STATE}'
                with urllib.request.urlopen(branch_url, timeout=5) as response:
                    branch_content = response.read()
                if branch_content != expected:
                    print(f'[안내] {side} 브랜치 raw 주소의 CDN 캐시가 아직 이전 버전입니다.', flush=True)
            except (urllib.error.URLError, TimeoutError):
                print(f'[안내] {side} 브랜치 raw 캐시 확인 대기 중입니다.', flush=True)
            return
        except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
            last_error = error
            if attempt < 2:
                time.sleep(2)
    raise RuntimeError(f'{side} 공개 피드를 검증하지 못했습니다: {last_error}') from last_error
