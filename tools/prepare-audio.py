#!/usr/bin/env python3
"""Verify neural audio downloads and optionally install them for device checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--install', action='store_true')
parser.add_argument('--models', nargs='+', choices=['deepfilter', 'voicefixer'],
                    default=['deepfilter', 'voicefixer'])
args = parser.parse_args()
manifest = json.loads((ROOT/'data/models.json').read_text())
for name in args.models:
    spec = manifest[name]
    directory = ROOT/'.build/models'/spec['directory']
    directory.mkdir(parents=True, exist_ok=True)
    for item in spec['files']:
        target = directory/item['name']
        if not target.exists():
            partial = target.with_suffix(target.suffix+'.partial')
            with urllib.request.urlopen(item['url'], timeout=60) as source, partial.open('wb') as output:
                shutil.copyfileobj(source, output)
            partial.rename(target)
        if target.stat().st_size != item['bytes'] or hashlib.sha256(target.read_bytes()).hexdigest() != item['sha256']:
            raise SystemExit('Checksum mismatch: '+str(target))
        print('Verified', name, item['name'])
    (directory/'ready').write_text(spec['version'])
    if args.install:
        adb = os.environ.get('ADB', str(Path(os.environ.get('ANDROID_HOME', Path.home()/'Android/Sdk'))/'platform-tools/adb'))
        destination = '/sdcard/Android/data/org.myrec.app/files/models/'+spec['directory']
        subprocess.run([adb, 'shell', 'mkdir', '-p', destination], check=True)
        subprocess.run([adb, 'push', str(directory)+'/.', destination+'/'], check=True)
