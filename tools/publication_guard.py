"""Reject local data and credentials before automatic commits and pushes."""
from pathlib import Path, PurePosixPath
import base64
import io
import re
import subprocess
import zipfile

ROOT_FILES = {
    '.gitattributes', '.gitignore', 'AGENTS.md', 'MAINTAINER.md', 'README.md',
    'LICENSE', 'THIRD_PARTY_NOTICES.txt', 'build.py', 'release-channel.properties',
    'localization-sources.json', 'localization-inline-sources.json',
    'CLIENT.BAT', 'GITCLIENT.BAT', 'GITSERVER.BAT', 'GITBOTH.BAT',
}
PREFIX_TYPES = {
    'src/main/java/': {'.java'},
    'src/main/resources/': {'.json', '.mcmeta', '.toml', '.properties', '.png'},
    'tests/': {'.java', '.py'}, 'tools/': {'.java', '.py'},
    'scripts/': {'.ps1'}, 'releases/': {'.json'},
    'localization/pack-files/': {'.json'},
}
PATTERNS = {
    'private key header': rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----',
    'GitHub token': rb'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})',
    'AWS key': rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
    'Google API key': rb'AIza[0-9A-Za-z_-]{35}',
    'Slack token': rb'xox[baprs]-[0-9A-Za-z-]{20,}',
    'Discord webhook': rb'https://discord(?:app)?\.com/api/webhooks/\d+/[A-Za-z0-9_-]{30,}',
    'JWT': rb'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{16,}',
    'credential URL': rb'https?://[^\s/:\x00]{1,80}:[^\s/@\x00]{3,100}@',
    'literal credential': rb'''(?i)(?:password|passwd|api[_-]?key|client[_-]?secret|access[_-]?token|auth[_-]?token)\s*[=:]\s*["']([^"'\r\n]{8,200})["']''',
}
COMPILED = {name: re.compile(pattern) for name, pattern in PATTERNS.items()}
PRIVATE_DER_PREFIX = bytes.fromhex('302e020100300506032b657004220420')
PUBLIC_DER_PREFIX = bytes.fromhex('302a300506032b6570032100')


def private_values():
    values = []
    for path in (Path.home()/'.codex/secrets/better-sunlit').glob('*.pk8'):
        data = path.read_bytes()
        values.extend((data, base64.b64encode(data), data.hex().encode()))
    return values


def check_file(name, data, secrets=()):
    path = PurePosixPath(name)
    allowed = name in ROOT_FILES or name == 'feed/update.properties'
    allowed = allowed or name in ('keys/client-public.der', 'keys/server-public.der')
    allowed = allowed or any(name.startswith(prefix) and path.suffix in suffixes
                             for prefix, suffixes in PREFIX_TYPES.items())
    if not allowed or '..' in path.parts or path.is_absolute():
        raise RuntimeError(f'게시 허용 목록에 없는 파일: {name}. 로컬 자료는 build/ 또는 local-*/에 보관하세요.')
    if name.startswith('keys/') and not (len(data) == 44 and data.startswith(PUBLIC_DER_PREFIX)):
        raise RuntimeError(f'공개 검증 키 형식이 아닌 파일: {name}')
    check_content(name, data, secrets)


def check_content(name, data, secrets=()):
    if PRIVATE_DER_PREFIX in data or any(value and value in data for value in secrets):
        raise RuntimeError(f'개인 키가 포함된 파일의 게시를 차단했습니다: {name}')
    for rule, pattern in COMPILED.items():
        if pattern.search(data):
            raise RuntimeError(f'인증 정보 의심 내용의 게시를 차단했습니다: {name} ({rule}). 값은 출력하지 않습니다.')


def check_release_jar(path):
    secrets = private_values()
    def inspect(archive, helper=False):
        names = set()
        for entry in archive.infolist():
            name = entry.filename
            if entry.is_dir():
                continue
            member = PurePosixPath(name)
            allowed = name.startswith('dev/sunlit/') and name.endswith('.class')
            allowed = allowed or name in ('assets/better_sunlit/update-build.properties',
                                          'assets/better_sunlit/update-public-key.der')
            if not helper:
                allowed = allowed or name in ('META-INF/MANIFEST.MF', 'META-INF/mods.toml', 'pack.mcmeta',
                    'sunlit_fish_visuals.mixins.json', 'better_sunlit_inline.mixins.json',
                    'better_sunlit_entry.mixins.json', 'assets/better_sunlit/update-helper.jar')
                allowed = allowed or (name.startswith('assets/') and member.suffix in ('.json', '.png'))
            if (not allowed or name in names or '..' in member.parts or member.is_absolute()
                    or entry.file_size > 64*1024*1024 or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                raise RuntimeError(f'배포 JAR에 허용되지 않은 항목이 있습니다: {name}')
            names.add(name)
            data = archive.read(entry)
            check_content(name, data, secrets)
            if name.endswith('update-public-key.der') and not (len(data)==44 and data.startswith(PUBLIC_DER_PREFIX)):
                raise RuntimeError(f'배포 JAR의 공개 검증 키 형식이 올바르지 않습니다: {name}')
            if name == 'assets/better_sunlit/update-helper.jar':
                with zipfile.ZipFile(io.BytesIO(data)) as nested:
                    inspect(nested, helper=True)
    with zipfile.ZipFile(path) as archive:
        inspect(archive)


def git_bytes(root, *args):
    return subprocess.check_output(['git', *map(str, args)], cwd=root)


def check_worktree(root):
    root = Path(root).resolve()
    names = git_bytes(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z')
    secrets = private_values()
    for name in set(names.decode('utf8').split('\0')) - {''}:
        path = root / name
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise RuntimeError(f'외부 경로를 가리키는 파일의 게시를 차단했습니다: {name}')
        if path.exists():
            check_file(name, path.read_bytes(), secrets)


def check_git_tree(root, revision='HEAD', remote=None):
    """Check HEAD plus new history, including files deleted before HEAD."""
    secrets = private_values()
    listing = git_bytes(root, 'ls-tree', '-rz', revision)
    blobs = {}
    for entry in listing.split(b'\0'):
        if not entry:
            continue
        metadata, name = entry.split(b'\t', 1)
        mode, kind, oid = metadata.decode().split()
        name = name.decode('utf8')
        if mode not in ('100644', '100755') or kind != 'blob':
            raise RuntimeError(f'일반 파일이 아닌 항목의 게시를 차단했습니다: {name}')
        blobs.setdefault(oid, set()).add(name)
    if remote:
        objects = git_bytes(root, 'rev-list', '--objects', revision, '^' + remote)
        for entry in objects.decode('utf8').splitlines():
            oid, _, name = entry.partition(' ')
            if name:
                blobs.setdefault(oid, set()).add(name)
    raw = subprocess.check_output(['git', 'cat-file', '--batch'], cwd=root,
                                  input=''.join(oid + '\n' for oid in blobs).encode())
    stream = io.BytesIO(raw)
    for oid, names in blobs.items():
        header = stream.readline().split()
        data = stream.read(int(header[2]))
        stream.read(1)
        if header[1] == b'blob':
            for name in names:
                check_file(name, data, secrets)
