#!/usr/bin/env python3
"""Download the pinned Qwen caption model with resumable range requests."""
import concurrent.futures, hashlib, json, os, pathlib, shutil, subprocess, time, urllib.request
ROOT=pathlib.Path(__file__).resolve().parent.parent
DEST=ROOT/'.build/models/qwen3-asr-0.6b'
spec=json.loads((ROOT/'data/models.json').read_text())['captions']
BASE=spec['url']
SIZE=spec['bytes']
SHA256=spec['sha256']
DEST.mkdir(parents=True,exist_ok=True)
for name in ['config.json','generation_config.json','vocab.json','merges.txt']:
 path=DEST/name
 if not path.exists():subprocess.run(['curl','-fL','--retry','3',BASE+name,'-o',str(path)],check=True)
target=DEST/'model.safetensors'
if target.exists() and target.stat().st_size==SIZE:
 with target.open('rb') as source:assert hashlib.file_digest(source,'sha256').hexdigest()==SHA256
 raise SystemExit('Model is ready (SHA-256 verified)')
parts=DEST/'parts';parts.mkdir(exist_ok=True)
chunk=32*1024*1024
prefix=pathlib.Path('/tmp/myrec-qwen-model.safetensors')
if prefix.exists():
 with prefix.open('rb') as src:
  for i in range(prefix.stat().st_size//chunk):
   part=parts/str(i)
   if not part.exists():part.write_bytes(src.read(chunk))
   else:src.seek(chunk,1)
def get(i):
 path=parts/str(i);a=i*chunk;b=min(SIZE,a+chunk)-1;length=b-a+1
 if path.exists() and path.stat().st_size==length:return
 temp=path.with_suffix('.partial')
 for attempt in range(12):
  try:
   offset=temp.stat().st_size if temp.exists() else 0
   if offset==length:temp.rename(path);return
   request=urllib.request.Request(BASE+'model.safetensors',headers={'Range':f'bytes={a+offset}-{b}'})
   with urllib.request.urlopen(request,timeout=30) as response:
    if response.status!=206:raise RuntimeError(f'Expected partial response, got {response.status}')
    if not response.headers.get('Content-Range','').startswith(f'bytes {a+offset}-'):raise RuntimeError('Wrong range')
    with temp.open('ab') as stream:
     while data:=response.read(1024*1024):stream.write(data)
   if temp.stat().st_size!=length:raise RuntimeError('Incomplete range')
   temp.rename(path);return
  except Exception:
   if attempt==11:raise
   time.sleep(1+attempt)
count=(SIZE+chunk-1)//chunk
with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
 jobs=[pool.submit(get,i) for i in range(count)]
 while not all(job.done() for job in jobs):
  done=sum(p.stat().st_size for p in parts.iterdir() if p.name.isdigit())
  print(f'Model: {done/SIZE:.0%}',flush=True);time.sleep(10)
 for job in jobs:job.result()
temp=target.with_suffix('.partial')
with temp.open('wb') as output:
 for i in range(count):
  with (parts/str(i)).open('rb') as source:
   while data:=source.read(1024*1024):output.write(data)
assert temp.stat().st_size==SIZE
with temp.open('rb') as source:assert hashlib.file_digest(source,'sha256').hexdigest()==SHA256
temp.rename(target)
shutil.rmtree(parts)
print('Ready:',target)
