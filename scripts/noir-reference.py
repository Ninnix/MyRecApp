#!/usr/bin/env python3
"""Independent fixtures for the pinned Noir shader, including its half-float lattice."""
import json, math, pathlib, struct

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = 'faf1815e82e1f2d5f03034a583363d3c3af464a2'
def clamp(x): return max(0, min(1, x))
def half(x): return struct.unpack('<e', struct.pack('<e', x))[0]
def linear(x): return x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4
def srgb(x): return x * 12.92 if x <= .0031308 else 1.055 * max(0, x) ** (1 / 2.4) - .055
def hash32(n):
    n &= 0xffffffff
    n ^= n >> 16; n = n * 0x7feb352d & 0xffffffff
    n ^= n >> 15; n = n * 0x846ca68b & 0xffffffff
    return n ^ (n >> 16)
def lattice(x, y, frame):
    n = hash32(x ^ hash32(y + 0x9e3779b9) ^ hash32(frame + 13 + 0x85ebca6b))
    return half((n >> 8) / 16777216 * 2 - 1)
def noise(x, y, frame):
    ix, iy = math.floor(x), math.floor(y)
    dx, dy = x - ix, y - iy
    dx, dy = dx * dx * (3 - 2 * dx), dy * dy * (3 - 2 * dy)
    a, b = lattice(ix, iy, frame), lattice(ix+1, iy, frame)
    c, d = lattice(ix, iy+1, frame), lattice(ix+1, iy+1, frame)
    return half((1-dy) * (a+(b-a)*dx) + dy * (c+(d-c)*dx))
def sample(c, x, y, width, height, p, frame, strength):
    if strength == 0: return c
    l = clamp(srgb(sum(a*b for a,b in zip(c,[.4,.5,.1]))))
    a, b = l ** p[0], (1-l) ** p[0]
    l = a / max(a+b, 1e-6)
    l = linear(l * (1-2*p[1]*(1-l)))
    out = [l,l,l]
    if p[3]: out = [v*(t+(1-t)*l) for v,t in zip(out,[.94,.97,1])]
    if p[2]:
        value = clamp(sum(a*b for a,b in zip(out,[.2126,.7152,.0722])))
        gain = math.exp(noise(x*1080/min(width,height), y*1080/min(width,height), frame)
                        * .06 * max(0,4*value*(1-value)) ** .7)
        out = [v*gain for v in out]
    return [a+(clamp(b)-a)*strength for a,b in zip(c,out)]

colors = [[0,0,0],[255,255,255],[128,128,128],[255,0,0],[0,255,0],[0,0,255],
          [200,140,100],[40,210,230],[235,210,25],[25,15,45]]
recipes = [[1.5,.12,1,0],[1,0,0,0],[2,.3,0,0],[1.5,.12,0,1],[2,.3,1,1],[1,0,1,1]]
fixtures = []
for index in range(24):
    width,height = [(17,13),(13,17),(1280,720),(720,1280)][index % 4]
    extended = index >= 12
    p = recipes[index % len(recipes)]
    frame = [0,1,17,1234,0xffffffff,0x100000001][index % 6] + index//6*7
    strength = [1, .3, .5, 1][index//6]
    points = []
    for j in range(110):
        pixel = j*137 % (width*height); x, y = pixel % width, pixel // width
        color = colors[j % len(colors)]
        c = [half(v/255 * (4 if j % 3 == 0 else 1)) if extended else linear(v/255) for v in color]
        out = sample(c,x,y,width,height,p,frame,strength)
        points.append([x,y, c if extended else color,
                       [half(v) for v in out] if extended else [int(clamp(srgb(v))*255+.5) for v in out]])
    fixtures.append(dict(width=width,height=height,linear=extended,parameters=p,frame=frame,intensity=strength,points=points))
path = ROOT / 'app/src/androidTest/assets/noir-reference.json'
path.write_text(json.dumps(dict(source=f'YouRec VideoEffects.metal {SOURCE}; independent Noir equations and grain lattice',fixtures=fixtures),separators=(',',':'))+'\n')
print(f'{len(fixtures)} fixtures written to {path.relative_to(ROOT)}')
