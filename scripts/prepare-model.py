#!/usr/bin/env python3
"""Download and verify the pinned offline caption model; retain partial downloads."""
import hashlib, json, pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
DEST = ROOT / '.build/models/qwen3-asr-0.6b'
spec = json.loads((ROOT / 'data/models.json').read_text())['captions']
DEST.mkdir(parents=True, exist_ok=True)

def valid(path, item):
    if not path.is_file() or path.stat().st_size != item['bytes']: return False
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest() == item['sha256']

for item in spec['files']:
    target = DEST / item['name']
    if valid(target, item): continue
    partial = DEST / (item['name'] + '.partial')
    if partial.exists() and partial.stat().st_size >= item['bytes']:
        if valid(partial, item):
            partial.replace(target)
            continue
        partial.unlink()
    subprocess.run(['curl', '-fL', '--retry', '3', '-C', '-',
                    item.get('url', spec['url'] + item['name']), '-o', str(partial)], check=True)
    if not valid(partial, item):
        partial.unlink(missing_ok=True)
        raise SystemExit('Caption model checksum mismatch')
    partial.replace(target)
print('Caption model is ready (SHA-256 verified)')
