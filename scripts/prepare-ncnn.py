#!/usr/bin/env python3
"""Restore the pinned external GPU runtime; implementation sources stay outside the app."""
import hashlib, json, pathlib, subprocess, tempfile, zipfile

root=pathlib.Path(__file__).resolve().parent.parent
spec=json.loads((root/'runtimes.json').read_text())['ncnn']
target=root/'.build/runtime-sources'/spec['directory']
library=target/'libncnn.so'
headers=['c_api.h','platform.h','ncnn_export.h']
def valid():
 return library.is_file() and hashlib.sha256(library.read_bytes()).hexdigest()==spec['sha256_arm64'] and all((target/name).is_file() for name in headers)
if not valid():
 target.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='myrec-ncnn-') as directory:
  archive=pathlib.Path(directory)/'ncnn.zip'
  subprocess.run(['curl','-fL','--retry','3','--connect-timeout','15','--max-time','300',spec['archive'],'-o',str(archive)],check=True)
  if hashlib.sha256(archive.read_bytes()).hexdigest()!=spec['sha256_archive']:raise SystemExit('ncnn archive checksum mismatch')
  prefix=f"ncnn-{spec['version']}-android-vulkan-shared/arm64-v8a/"
  with zipfile.ZipFile(archive) as source:
   for name in ['libncnn.so',*headers]:
    member=prefix+('lib/' if name.endswith('.so') else 'include/ncnn/')+name
    (target/name).write_bytes(source.read(member))
if not valid():raise SystemExit('ncnn runtime checksum mismatch')
print('Verified ncnn',spec['version'])
