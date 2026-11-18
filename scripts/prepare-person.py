#!/usr/bin/env python3
"""Verify or restore the pinned, bundled person-segmentation model."""
import hashlib
import json
import pathlib
import urllib.request

root = pathlib.Path(__file__).resolve().parent.parent
spec = json.loads((root / 'data/models.json').read_text())['person']
target = root / 'app/src/main/assets/models/person.onnx'

def valid(path):
    return (path.is_file() and path.stat().st_size == spec['bytes'] and
            hashlib.sha256(path.read_bytes()).hexdigest() == spec['sha256'])

if not valid(target):
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix('.partial')
    try:
        with urllib.request.urlopen(spec['url'], timeout=90) as source, temporary.open('wb') as output:
            while block := source.read(128 * 1024):
                output.write(block)
                if output.tell() > spec['bytes']:
                    raise SystemExit('Person model exceeds expected size')
        if not valid(temporary):
            raise SystemExit('Person model checksum mismatch')
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
print('Verified PPHumanSeg:', spec['sha256'])
for item in json.loads((root/'data/models.json').read_text())['person_preview']['files']:
    path=target.parent/item['name']
    if not path.is_file() or path.stat().st_size!=item['bytes'] or hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:
        raise SystemExit('Regenerate the FP32 preview graph with scripts/prepare-person-preview.py')
print('Verified FP32 person preview graph')
