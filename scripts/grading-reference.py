#!/usr/bin/env python3
# Scalar fixtures for YouRec LogColor.swift, evaluated independently of the C renderer.
from pathlib import Path
import math,json,struct
root=Path(__file__).resolve().parent.parent
A=[1.6604910021,-.5876411388,-.0728498633,-.1245504745,1.1328998971,-.0083494226,-.0181507634,-.1005788980,1.1187296614]
B=[.6274038959,.3292830384,.0433130657,.0690972894,.9195403951,.0113623156,.0163914389,.0880133079,.8955952532]
def matrix(m,v):return [sum(m[i*3+k]*v[k] for k in range(3)) for i in range(3)]
def half(v):return struct.unpack('<e',struct.pack('<e',v))[0]
def encode(v):return math.log2(v+.00964052)*.08550479+.69336945 if v>=.01 else 47.28711236*max(0,v+.05641088)**2
def decode(v):return 2**((v-.69336945)/.08550479)-.00964052 if v>=.20855531595 else math.sqrt(max(0,v)/47.28711236)-.05641088
def luma(c):return sum(a*b for a,b in zip(c,[.2126,.7152,.0722]))
def shoulder(v):return v if v<=.6 else .6+.4*(v-.6)/(v-.2)
def display(c):
 y=max(0,luma(c));value=shoulder(y);rgb=[v*(value/y if y>1e-6 else 1) for v in c];chroma=[v-value for v in rgb];fit=1
 for v in chroma:
  if v>1e-6:fit=min(fit,(1-value)/v)
  if v< -1e-6:fit=min(fit,-value/v)
 return [max(0,min(1,value+v*fit)) for v in chroma]
def mean(values,w,h,r):
 out=[]
 for y in range(h):
  for x in range(w):
   total=[0.,0.]
   for v in range(y-r,y+r+1):
    for u in range(x-r,x+r+1):
     p=values[min(h-1,max(0,v))*w+min(w-1,max(0,u))]
     for k in range(2):total[k]+=p[k]
   out.append([v/(2*r+1)**2 for v in total])
 return out
fixtures=[]
for w,h in [(39,27),(108,60)]:
 raw=[];scene=[]
 for y in range(h):
  for x in range(w):
   v=.04+12*(x/(w-1))**3;detail=1+.11*math.sin(x*1.7)*math.cos(y*.8)
   rgb=[v*detail,v*.85*detail,v*.65*detail]
   if x==w//3 and y==h//2:rgb=[18.,12.,5.]
   codes=[half(encode(v)) for v in matrix(B,rgb)];raw.append(codes)
   rgb=matrix(A,[decode(v) for v in codes]);scene.append(rgb)
 r=max(1,int(60*min(w,h)/1080+.5))
 moments=[[math.log2(max(1e-6,luma(c))),math.log2(max(1e-6,luma(c)))**2] for c in scene]
 averages=mean(moments,w,h,r);coefficients=[]
 for m,q in averages:
  variance=max(0,q-m*m);a=variance/(variance+.12);coefficients.append([a,m*(1-a)])
 coefficients=mean(coefficients,w,h,r)
 for amount in [0,.25,1]:
  recovered=[]
  for c,(a,b) in zip(scene,coefficients):
   y=max(1e-6,luma(c));l=math.log2(y);baseLog=a*l+b;base=2**baseLog;adjusted=base if base<=.35 else .35+math.log1p(12*(base-.35))/12
   local=shoulder(adjusted)*2**(l-baseLog)
   if local>.9:local=.9+.1*(local-.9)/(local-.8)
   target=shoulder(y)*(1-amount)+local*amount
   value=target if target<=.6 else .6+.4*(target-.6)/max(1e-6,1-target)
   recovered.append([v*value/y for v in c] if amount else c)
  points=[]
  for y in sorted({0,h//3,h//2,h-1}):
   for x in sorted({0,w//6,w//3,w//2,w*3//4,w-1}):
    index=y*w+x;points.append([x,y,display(recovered[index]),[max(0,v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4) for v in [max(0,min(1,encode(v))) for v in matrix(B,recovered[index])]]])
  fixtures.append(dict(width=w,height=h,amount=amount,points=points))
path=root/'app/src/androidTest/assets/hdr-color-reference.json';path.write_text(json.dumps(dict(source='YouRec LogColor.swift localRecovery kernels; float16 unmanaged Apple Log inputs',fixtures=fixtures),separators=(',',':'))+'\n')
print(path,len(path.read_bytes()))
