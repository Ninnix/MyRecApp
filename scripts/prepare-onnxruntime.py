#!/usr/bin/env python3
import hashlib,io,json,pathlib,time,urllib.request,zipfile
ROOT=pathlib.Path(__file__).resolve().parent.parent
spec=json.loads((ROOT/'runtimes.json').read_text())['onnxruntime']
URL=spec['archive']
class Remote(io.RawIOBase):
 def __init__(self):
  super().__init__();self.position=0
  with urllib.request.urlopen(urllib.request.Request(URL,method='HEAD'),timeout=20) as r:self.size=int(r.headers['Content-Length'])
 def seekable(self):return True
 def readable(self):return True
 def tell(self):return self.position
 def seek(self,offset,whence=0):
  self.position=offset if whence==0 else self.position+offset if whence==1 else self.size+offset
  return self.position
 def read(self,n=-1):
  n=min(self.size-self.position,n if n>=0 else self.size)
  output=bytearray()
  while len(output)<n:
   size=min(1024*1024,n-len(output));a=self.position;b=a+size-1
   for attempt in range(5):
    try:
     with urllib.request.urlopen(urllib.request.Request(URL,headers={'Range':f'bytes={a}-{b}'}),timeout=20) as r:
      if r.status!=206:raise RuntimeError('Range requests unsupported')
      data=r.read()
     if len(data)!=size:raise RuntimeError('Incomplete range')
     break
    except Exception:
     if attempt==4:raise
     time.sleep(1)
   output.extend(data);self.position+=size
   if n>1024*1024:print('Downloaded',len(output),'/',n,flush=True)
  return bytes(output)
with zipfile.ZipFile(Remote()) as z:
 directory=ROOT/'.build/runtime-sources/onnxruntime-current';directory.mkdir(parents=True,exist_ok=True)
 for name in [n for n in z.namelist() if n.startswith('headers/') and n.endswith('.h')]+['jni/arm64-v8a/libonnxruntime.so']:
  if (directory/pathlib.Path(name).name).exists():continue
  data=z.read(name);target=directory/pathlib.Path(name).name;target.write_bytes(data);print(target,len(data),flush=True)

if hashlib.sha256((directory/'libonnxruntime.so').read_bytes()).hexdigest()!=spec['sha256_arm64']:
 raise SystemExit('ONNX Runtime checksum mismatch')
