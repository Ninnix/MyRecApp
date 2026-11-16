#!/usr/bin/env python3
"""Undercranking, Super 8 and Freeze Print equations from the pinned Metal source."""
import functools, importlib.util, json, math, pathlib

ROOT=pathlib.Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('film_math',ROOT/'scripts/sixteen-mm-reference.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def evaluate(w,h,extended,kind,p,frame,time,strength):
    @functools.lru_cache(maxsize=100000)
    def original(x,y):
        color=m.COLORS[(x//7+y//5)%len(m.COLORS)]
        return tuple(m.half(v/255*(4 if (x//11+y//13)%3==0 else 1)) if extended else m.linear(v/255) for v in color)
    def texture(x,y):return [m.half(v) for v in original(x,y)]
    sigma=max(.25,2*min(w,h)/1080);radius=min(48,math.ceil(sigma*3))
    raw=[math.exp(-j*j/(2*sigma*sigma)) for j in range(radius+1)];total=raw[0]+2*sum(raw[1:])
    pairs=[]
    for j in range(1,radius+1,2):
        b=raw[j+1] if j+1<=radius else 0
        pairs.append((j+b/(raw[j]+b),(raw[j]+b)/total))
    @functools.lru_cache(maxsize=100000)
    def horizontal(x,y):
        out=[v*raw[0]/total for v in texture(x,y)]
        for offset,weight in pairs:
            for sign in [-1,1]:out=[v+s*weight for v,s in zip(out,m.bilinear(texture,w,h,x+sign*offset,y))]
        return tuple(m.half(v) for v in out)
    @functools.lru_cache(maxsize=100000)
    def vertical(x,y):
        out=[v*raw[0]/total for v in horizontal(x,y)]
        for offset,weight in pairs:
            for sign in [-1,1]:out=[v+s*weight for v,s in zip(out,m.bilinear(horizontal,w,h,x,y+sign*offset))]
        return tuple(m.half(v) for v in out)
    def smooth(a,b,x):return m.smooth(m.clamp((x-a)/(b-a)))
    scale=min(w,h)/1080;seed=frame&0xffffffff
    weave=p[1] if kind==0 else p[2] if kind==1 else 0
    move=[m.noise(seed*(.18 if kind==0 else .16),3 if kind==0 else 2,7 if kind==0 else 5)*2*weave*scale,
          m.noise(seed*(.18 if kind==0 else .16),8 if kind==0 else 9,11 if kind==0 else 17)*3*weave*scale]
    def sample(x,y):
        c=original(x,y)
        if strength==0:return c
        u,v=(x+.5)/w,(y+.5)/h;zoom=1.01 if kind==0 else 1.012 if kind==1 else 1
        sx,sy=((u-.5)/zoom+.5)*w-.5+move[0],((v-.5)/zoom+.5)*h-.5+move[1]
        out=m.bilinear(texture,w,h,sx,sy)
        if kind==0:
            flicker=1+(m.random01(4,7,seed)*2-1)*.08*p[0];out=[v*flicker for v in out]
        elif kind==1:
            red=m.bilinear(vertical,w,h,sx+(u-.5)*scale,sy+(v-.5)*scale)
            out=m.bilinear(vertical,w,h,sx,sy)
            blue=m.bilinear(vertical,w,h,sx-(u-.5)*scale,sy-(v-.5)*scale);out[0]=red[0];out[2]=blue[2]
            out=[v+(.06+v*.88-v)*p[0] for v in out];out[0]*=1.06;out[2]*=.92;out[0]+=out[1]*.025*p[0]
            l=sum(v*k for v,k in zip(out,[.2126,.7152,.0722]));out=[l+(v-l)*.8 for v in out]
            out=m.grain(out,x/(scale*2.2),y/(scale*2.2),seed,p[1],False)
            cycle=math.floor(time/6);phase=time-cycle*6;begin=m.random01(cycle,12,3)*4.5;length=.5+m.random01(cycle,4,6)
            pulse=math.sin((phase-begin)/length*math.pi) if begin<=phase<begin+length else 0
            edge=u if m.random01(cycle,7,11)>.5 else 1-u;leak=math.exp(-edge*7)*pulse*p[4]
            out=[v+leak*k for v,k in zip(out,[1,.22,.035])]
            if p[5]:
                dx,dy=abs(u-.5)-.445,abs(v-.5)-.445;gate=1-smooth(.051,.059,math.hypot(max(dx,0),max(dy,0))+min(max(dx,dy),0));out=[v*gate for v in out]
        else:
            l=sum(v*k for v,k in zip(out,[.2126,.7152,.0722]));vignette=1-.12*smooth(.25,.7,math.hypot(u-.5,v-.5))
            out=[(l+(v-l)*.95)*vignette for v in out];out=m.grain(out,x/(scale*1.5),y/(scale*1.5),seed,.3,False)
        out=[v+(m.clamp(q)-v)*strength for v,q in zip(c,out)]
        return [m.half(v) for v in out] if extended else [int(m.clamp(m.srgb(v))*255+.5) for v in out]
    points={(0,0),(w-1,h-1),(w//2,h//2)}|{((n*137)%(w*h)%w,(n*137)%(w*h)//w) for n in range(min(45,w*h))}
    return [[x,y,sample(x,y)] for x,y in sorted(points)]

fixtures=[]
for index in range(18):
    kind=index%3;w,h=[(79,53),(53,79),(1,7),(640,360),(7,1),(1280,720)][index//3]
    p=[[.5,.4],[.5,.7,.5,18,.2,1],[1]][kind];frame=[0,1,17,1234,0x100000008,23][index//3]
    time=[0,.2,.8,3.2,5.1,6.3][index//3];extended=index>=9;strength=.4 if index//3%2 else 1
    fixtures.append(dict(width=w,height=h,linear=extended,kind=kind,parameters=p,frame=frame,time=time,intensity=strength,
                         output=evaluate(w,h,extended,kind,p,frame,time,strength)))
path=ROOT/'app/src/androidTest/assets/time-film-reference.json'
path.write_text(json.dumps(dict(source=f'YouRec VideoEffects.metal {m.SOURCE}; independent film cadence equations',colors=m.COLORS,fixtures=fixtures),separators=(',',':'))+'\n')
print(f'{len(fixtures)} fixtures written to {path.relative_to(ROOT)}')
