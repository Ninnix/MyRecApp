#!/usr/bin/env python3
"""16mm source equations, evaluated lazily at samples without a production renderer."""
import functools, json, math, pathlib, struct

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = 'faf1815e82e1f2d5f03034a583363d3c3af464a2'
COLORS = [[0,0,0],[255,255,255],[128,128,128],[255,0,0],[0,255,0],[0,0,255],
          [200,140,100],[40,210,230],[235,210,25],[25,15,45]]
def clamp(x, low=0, high=1): return max(low,min(high,x))
def half(x): return struct.unpack('<e',struct.pack('<e',x))[0]
def linear(x): return x/12.92 if x <= .04045 else ((x+.055)/1.055)**2.4
def srgb(x): return x*12.92 if x <= .0031308 else 1.055*max(0,x)**(1/2.4)-.055
def smooth(x): return x*x*(3-2*x)
def hash32(n):
    n &= 0xffffffff
    n ^= n >> 16; n = n*0x7feb352d & 0xffffffff
    n ^= n >> 15; n = n*0x846ca68b & 0xffffffff
    return n ^ (n >> 16)
def random01(x,y,z): return (hash32(x ^ hash32(y+0x9e3779b9) ^ hash32(z+0x85ebca6b)) >> 8)/16777216
def interpolate(read,x,y,half_output=False):
    ix,iy = math.floor(x),math.floor(y); dx,dy = smooth(x-ix),smooth(y-iy)
    a,b,c,d = read(ix,iy),read(ix+1,iy),read(ix,iy+1),read(ix+1,iy+1)
    value = (1-dy)*(a+(b-a)*dx)+dy*(c+(d-c)*dx)
    return half(value) if half_output else value
def noise(x,y,seed): return interpolate(lambda xx,yy: random01(xx,yy,seed),x,y)*2-1
def grain(c,x,y,frame,strength,mono):
    value = clamp(sum(v*w for v,w in zip(c,[.2126,.7152,.0722])))
    amplitude = strength*.24*max(0,4*value*(1-value))**.7
    def lattice(xx,yy,k):
        base = random01(xx,yy,frame+13)*2-1
        if not mono: base = base*.7+(random01(xx,yy,frame+[29,59,101][k])*2-1)*.3
        return half(base)
    return [clamp(v*math.exp(interpolate(lambda xx,yy: lattice(xx,yy,k),x,y,True)*amplitude)) for k,v in enumerate(c)] if strength else [clamp(v) for v in c]
def bilinear(read,width,height,x,y):
    x,y = clamp(x,0,width-1),clamp(y,0,height-1)
    ix,iy = math.floor(x),math.floor(y); dx,dy = x-ix,y-iy
    a,b,c,d = read(ix,iy),read(min(ix+1,width-1),iy),read(ix,min(iy+1,height-1)),read(min(ix+1,width-1),min(iy+1,height-1))
    return [half((1-dy)*(v+(w-v)*dx)+dy*(r+(s-r)*dx)) for v,w,r,s in zip(a,b,c,d)]
def evaluate(width,height,extended,p,frame,strength):
    @functools.lru_cache(maxsize=200000)
    def original(x,y):
        color = COLORS[(x//7+y//5)%len(COLORS)]
        return tuple(half(v/255*(4 if (x//11+y//13)%3 == 0 else 1)) if extended else linear(v/255) for v in color)
    def texture(x,y): return [half(v) for v in original(x,y)]
    sw,sh = max(2,width),max(2,height)
    sigma = max(.25,1.5*min(width,height)/1080*sw/width)
    radius = min(48,math.ceil(sigma*3))
    raw = [math.exp(-j*j/(2*sigma*sigma)) for j in range(radius+1)]
    total = raw[0]+2*sum(raw[1:]); pairs = []
    for j in range(1,radius+1,2):
        b = raw[j+1] if j+1 <= radius else 0
        pairs.append((j+b/(raw[j]+b),(raw[j]+b)/total))
    @functools.lru_cache(maxsize=100000)
    def horizontal(x,y):
        sx,sy = (x+.5)*width/sw-.5,(y+.5)*height/sh-.5
        c = bilinear(texture,width,height,sx,sy)
        out = [v*raw[0]/total for v in c]
        for offset,weight in pairs:
            for sign in [-1,1]:
                c = bilinear(texture,width,height,sx+sign*offset*width/sw,sy)
                out = [v+w*weight for v,w in zip(out,c)]
        return tuple(half(v) for v in out)
    @functools.lru_cache(maxsize=100000)
    def vertical(x,y):
        c = horizontal(x,y); out = [v*raw[0]/total for v in c]
        for offset,weight in pairs:
            for sign in [-1,1]:
                c = bilinear(horizontal,sw,sh,x,y+sign*offset)
                out = [v+w*weight for v,w in zip(out,c)]
        return tuple(half(v) for v in out)
    scale = min(width,height)/1080
    move = [noise(frame*.13,2,7)*p[3]*scale*2,noise(frame*.13,9,3)*p[3]*scale*2]
    def sample(x,y):
        c = original(x,y)
        if strength == 0: return c
        u,v = (x+.5)/width,(y+.5)/height
        px,py = (u-.5)/1.006+.5+move[0]/width,(v-.5)/1.006+.5+move[1]/height
        out = bilinear(texture,width,height,px*width-.5,py*height-.5)
        if p[2]:
            soft = bilinear(vertical,sw,sh,px*sw-.5,py*sh-.5)
            out = [v+(w-v)*p[2] for v,w in zip(out,soft)]
        out = [v/(1+max(0,v-.85)*.15) for v in out]
        if p[5]: out = [sum(v*w for v,w in zip(out,[.2126,.7152,.0722]))]*3
        size = [1,1.5,2.2][p[1]]
        out = grain(out,x/(scale*size),y/(scale*size),frame,p[0],p[5])
        if p[4]:
            dx,dy = abs(u-.5)-.465,abs(v-.5)-.465
            distance = math.hypot(max(0,dx),max(0,dy))+min(max(dx,dy),0)
            gate = 1-smooth(clamp((distance-.031)/.008))
            out = [v*gate for v in out]
        return [v+(clamp(w)-v)*strength for v,w in zip(c,out)]
    points = set([(0,0),(width-1,0),(0,height-1),(width-1,height-1),(width//2,height//2)])
    points.update(((j*137)%(width*height)%width,(j*137)%(width*height)//width) for j in range(min(90,width*height)))
    return [[x,y,[half(v) for v in sample(x,y)] if extended else [int(clamp(srgb(v))*255+.5) for v in sample(x,y)]] for x,y in sorted(points)]

if __name__ == '__main__':
    recipes = [[.5,1,.3,.2,0,0],[0,0,0,0,0,0],[1,0,1,1,1,1],[.8,2,.6,.5,1,0],[.5,1,0,0,1,1],[0,2,1,1,0,0]]
    fixtures = []
    for index in range(24):
        width,height = [(17,13),(13,17),(640,360),(360,640),(1,7),(7,1)][index%6]
        p = recipes[(index//6+index)%6]; extended = index >= 12
        frame = [0,1,17,1234][index//6]; strength = [1,.3,.5,1][index//6]
        if index == 23: width,height,p = 1920,1080,recipes[0]
        output = evaluate(width,height,extended,p,frame,strength)
        fixtures.append(dict(width=width,height=height,linear=extended,parameters=p,frame=frame,intensity=strength,output=output))
    path = ROOT/'app/src/androidTest/assets/sixteen-mm-reference.json'
    path.write_text(json.dumps(dict(source=f'YouRec VideoEffects.metal {SOURCE}; independent 16mm equations with paired Gaussian and half-float grain',colors=COLORS,fixtures=fixtures),separators=(',',':'))+'\n')
    print(f'{len(fixtures)} fixtures written to {path.relative_to(ROOT)}')
