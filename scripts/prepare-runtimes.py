#!/usr/bin/env python3
"""Fetch pinned external C runtimes; their implementation stays outside application sources."""
import hashlib, json, pathlib, subprocess, tarfile, sys
ROOT=pathlib.Path(__file__).resolve().parent.parent
DEST=ROOT/'.build/runtime-sources'
RUNTIMES=json.loads((ROOT/'runtimes.json').read_text())
SOURCES={item['directory']:(item['archive'],item['sha256'])
         for item in RUNTIMES.values() if 'sha256' in item}
DEST.mkdir(parents=True,exist_ok=True)
for name,(url,checksum) in SOURCES.items():
 if (DEST/name).exists():continue
 archive=DEST/(name+'.tar.gz')
 subprocess.run(['curl','-fL','--retry','3',url,'-o',str(archive)],check=True)
 if checksum and hashlib.sha256(archive.read_bytes()).hexdigest()!=checksum:raise SystemExit('Checksum mismatch: '+name)
 with tarfile.open(archive) as tar:
  original=tar.getmembers()[0].name.split('/')[0]
  tar.extractall(DEST,filter='data')
  if original!=name:(DEST/original).rename(DEST/name)
 archive.unlink()
 print('Prepared',name)

ort=DEST/'onnxruntime-current/libonnxruntime.so'
if not ort.exists():
 subprocess.run([sys.executable,str(ROOT/'scripts/prepare-onnxruntime.py')],check=True)
if hashlib.sha256(ort.read_bytes()).hexdigest()!=RUNTIMES['onnxruntime']['sha256_arm64']:
 raise SystemExit('ONNX Runtime 1.31.1 checksum mismatch')
subprocess.run([sys.executable,str(ROOT/'scripts/prepare-ncnn.py')],check=True)
