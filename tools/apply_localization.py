"""Merge reviewed Korean assets and external book files into a stopped instance."""
from pathlib import Path
from datetime import datetime
import argparse
import hashlib
import json
import os
import shutil

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser()
p.add_argument('--instance', type=Path, default=ROOT.parent.parent)
args = p.parse_args()
instance = args.instance.resolve()
game = instance/'minecraft'
assert (game/'kubejs').is_dir(), game
backup = ROOT/'local-backups'/('apply-localization-'+datetime.now().strftime('%Y%m%d-%H%M%S'))
planned = []
for source in sorted((ROOT/'src/main/resources/assets').glob('*/lang/ko_kr.json')):
    dest = game/'kubejs/assets'/source.relative_to(ROOT/'src/main/resources/assets')
    old = json.loads(dest.read_text(encoding='utf-8-sig')) if dest.exists() else {}
    patch = json.loads(source.read_text(encoding='utf8'))
    if dest.exists() and all(old.get(k) == v for k, v in patch.items()):
        continue
    old.update(patch)
    planned.append((dest, (json.dumps(old, ensure_ascii=False, indent=2)+'\n').encode('utf8')))
for source in sorted((ROOT/'src/main/resources/assets/better_sunlit/lang').glob('en_us.json')):
    dest = game/'kubejs/assets/better_sunlit/lang'/source.name
    old = json.loads(dest.read_text(encoding='utf-8-sig')) if dest.exists() else {}
    patch = json.loads(source.read_text(encoding='utf8'))
    if dest.exists() and all(old.get(k) == v for k, v in patch.items()):
        continue
    old.update(patch)
    planned.append((dest, (json.dumps(old, ensure_ascii=False, indent=2)+'\n').encode('utf8')))
for source in sorted((ROOT/'localization/pack-files').rglob('*.json')):
    dest = game/source.relative_to(ROOT/'localization/pack-files')
    # This external entry already has Korean prose. Only correct the reviewed name.
    assert source.name == 'warped_wooly_cow.json'
    if not dest.exists():
        continue
    book = json.loads(dest.read_text(encoding='utf-8-sig'))
    desired = json.loads(source.read_text(encoding='utf8'))['pages'][1]['name']
    if book['pages'][1]['name'] == 'Warped Wooly Cow':
        book['pages'][1]['name'] = desired
        planned.append((dest, (json.dumps(book, ensure_ascii=False, indent=2)+'\n').encode('utf8')))
manifest = []
for dest, raw in planned:
    assert dest.resolve().is_relative_to(game.resolve()), dest
    if dest.exists() and dest.read_bytes() == raw:
        continue
    rel = dest.relative_to(game)
    old_hash = None
    if dest.exists():
        old_hash = hashlib.sha256(dest.read_bytes()).hexdigest()
        saved = backup/rel
        saved.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(dest, saved)
        assert saved.read_bytes() == dest.read_bytes()
    dest.parent.mkdir(parents=True, exist_ok=True)
    stage = dest.with_name(dest.name+'.better-sunlit.tmp')
    assert not stage.exists(), stage
    stage.write_bytes(raw)
    os.replace(stage, dest)
    manifest.append({'path': str(rel), 'beforeSha256': old_hash,
                     'afterSha256': hashlib.sha256(raw).hexdigest()})
if manifest:
    backup.mkdir(parents=True, exist_ok=True)
    (backup/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf8')
print(f'Applied {len(manifest)} changed files. Independent backups: {backup}')
