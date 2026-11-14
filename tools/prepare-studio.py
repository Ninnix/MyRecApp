#!/usr/bin/env python3
"""Prepare the pinned Studio clarity model; optionally install it on the attached phone."""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--install', action='store_true')
args = parser.parse_args()
directory = ROOT/'.build/models/studio-lava-v1'
directory.mkdir(parents=True, exist_ok=True)
manifest = json.loads((ROOT/'data/models.json').read_text())['studio']
entries = manifest['files']
for item in entries:
    name = item['name']
    if not name.startswith('enhancer_'):
        continue
    target = directory/name
    if not target.exists():
        with urllib.request.urlopen(item['url'], timeout=60) as response:
            with target.open('wb') as output:
                shutil.copyfileobj(response, output)
    if target.stat().st_size != item['bytes'] or hashlib.sha256(target.read_bytes()).hexdigest() != item['sha256']:
        target.unlink()
        raise SystemExit('Checksum mismatch: '+name)
    print('Verified', name)
(directory/'ready').write_text(manifest['version'])
if args.install:
    sdk = pathlib.Path(os.environ.get('ANDROID_HOME', pathlib.Path.home()/'Android/Sdk'))
    adb = os.environ.get('ADB', str(sdk/'platform-tools/adb'))
    destination = '/sdcard/Android/data/org.myrec.app/files/models/studio-lava-v1'
    subprocess.run([adb, 'shell', 'mkdir', '-p', destination], check=True)
    subprocess.run([adb, 'push', str(directory)+'/.', destination+'/'], check=True)
