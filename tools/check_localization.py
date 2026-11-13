"""Validate supplemental locales against installed source strings and built artifacts."""
from pathlib import Path
from zipfile import ZipFile
from collections import Counter
import argparse
import json
import re

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--instance', type=Path, default=ROOT.parent.parent)
args = p.parse_args()

def source_json(raw):
    text = raw.decode('utf-8-sig')
    try:
        return json.loads(text, strict=False)
    except ValueError:
        # Installed source assets include comments, raw newlines and one missing brace.
        text = re.sub(r'"(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/',
                      lambda m: m[0] if m[0].startswith('"') else '', text)
        text = re.sub(r',\s*([}\]])', r'\1', text)
        if text.lstrip().startswith('"'):
            text = '{' + text
            if not text.rstrip().endswith('}'):
                text += '}'
        return json.loads(text, strict=False)

def unique_pairs(pairs):
    data = {}
    for key, value in pairs:
        if key in data:
            raise AssertionError('Duplicate language key: ' + key)
        data[key] = value
    return data

english = {}
for jar in sorted((args.instance / 'minecraft/mods').glob('*.jar')):
    with ZipFile(jar) as z:
        for name in z.namelist():
            if name.endswith('/lang/en_us.json'):
                english.update(source_json(z.read(name)))
for locale in (ROOT / 'src/main/resources/assets').glob('*/lang/en_us.json'):
    english.update(source_json(locale.read_bytes()))

# Capture complete printf fields, including optional date/time suffixes, named
# substitutions and Patchouli tags. Whitespace after a literal % is not a field.
fields = re.compile(r'%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?(?:[tT][A-Za-z]|[bBhHsScCdoxXeEfgGaAn%])'
                    r'|\$\([^)]+\)|\$?\{[\w]+(?:,[^}]+)?\}')
locales = sorted((ROOT / 'src/main/resources/assets').glob('*/lang/ko_kr.json'))
count = checked = 0
for locale in locales:
    raw = locale.read_bytes()
    assert not raw.startswith(b'\xef\xbb\xbf'), locale
    data = json.loads(raw, object_pairs_hook=unique_pairs)
    assert isinstance(data, dict), locale
    for key, value in data.items():
        assert isinstance(key, str) and isinstance(value, str), (locale, key)
        assert not re.search(r'ZX[QB]|QXZ|BXZ|\ufffd|\x00', value), (locale, key)
        if key in english:
            assert Counter(fields.findall(english[key])) == Counter(fields.findall(value)), key
            checked += 1
        count += 1
    for side in ['client', 'server']:
        with ZipFile(ROOT / 'build' / side / 'better-sunlit.jar') as z:
            assert z.read(locale.relative_to(ROOT / 'src/main/resources').as_posix()) == raw, (side, locale)

print(f'PASS: {count} locale entries in {len(locales)} files; {checked} format checks; both JARs match source. No game launched.')
