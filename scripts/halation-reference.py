#!/usr/bin/env python3
"""Evaluate the pinned YouRec highlight/blur/composite equations, without Android."""
import json, math, pathlib, struct

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = 'faf1815e82e1f2d5f03034a583363d3c3af464a2'

def clamp(x, lo=0, hi=1): return min(hi, max(lo, x))
def half(x): return struct.unpack('e', struct.pack('e', x))[0]
def linear(x): return x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4
def srgb(x): return 12.92 * x if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055
def luma(c): return sum(a * b for a, b in zip(c, [.2126, .7152, .0722]))
def smooth(x):
    t = clamp((x - .65) / .2)
    return t * t * (3 - 2 * t)

def sample(image, x, y):
    h, w = len(image), len(image[0])
    x, y = clamp(x, 0, w - 1), clamp(y, 0, h - 1)
    ix, iy = math.floor(x), math.floor(y)
    dx, dy = x - ix, y - iy
    return [sum(image[min(h-1, iy+j)][min(w-1, ix+i)][k] * wx * wy
                for j, wy in [(0, 1-dy), (1, dy)] for i, wx in [(0, 1-dx), (1, dx)]) for k in range(3)]

def area(image, w, h, threshold=False):
    iw, ih = len(image[0]), len(image)
    sx, sy = math.ceil(iw / w), math.ceil(ih / h)
    out = []
    for y in range(h):
        row = []
        for x in range(w):
            total = [0., 0., 0.]
            for j in range(sy):
                for i in range(sx):
                    c = sample(image, (x + (i + .5)/sx) * iw/w - .5, (y + (j + .5)/sy) * ih/h - .5)
                    weight = smooth(luma(c)) if threshold else 1
                    total = [a + b * weight for a, b in zip(total, c)]
            row.append([half(a / (sx * sy)) for a in total])
        out.append(row)
    return out

def blur(image, sigma):
    iw, ih = len(image[0]), len(image)
    scale = min(1, 14 / max(.25, sigma))
    w, h = max(2, int(iw * scale)), max(2, int(ih * scale))
    if w < iw or h < ih: image = area(image, w, h)
    sigma = max(.25, sigma * w/iw)
    radius = min(48, math.ceil(sigma * 3))
    weights = [math.exp(-i*i/(2*sigma*sigma)) for i in range(-radius, radius+1)]
    weights = [v/sum(weights) for v in weights]
    for dx, dy in [(1, 0), (0, 1)]:
        image = [[[half(sum(image[clamp(y+i*dy, 0, h-1)][clamp(x+i*dx, 0, w-1)][k] * weight
                           for i, weight in zip(range(-radius, radius+1), weights))) for k in range(3)]
                   for x in range(w)] for y in range(h)]
    return image

def evaluate(f):
    w, h, p = f['width'], f['height'], f['parameters']
    image = [[list(f['background']) for _ in range(w)] for _ in range(h)]
    for x0, y0, x1, y1, r, g, b in f['patches']:
        for y in range(y0, y1):
            for x in range(x0, x1): image[y][x] = [r, g, b]
    image = [[[half(c) if f['linear'] else linear(c / 255) for c in pixel] for pixel in row] for row in image]
    small = area(image, max(2, w//4), max(2, h//4), True)
    glow, halo, extra = [blur(small, w * n/4) for n in [.01, .006, .04]]
    flash = [[1,1,1], [1,.8,.6], [.65,.8,1]][int(p[3])]
    points = {(round(x*(w-1)/16), round(y*(h-1)/8)) for x in range(17) for y in range(9)}
    for x0, y0, x1, y1, *_ in f['patches']:
        for x in range(max(0, x0-8), min(w, x1+8)):
            for y in [max(0, y0-2), y0, min(h-1, y1), min(h-1, y1+2)]: points.add((x,y))
    result = []
    for x,y in sorted(points):
        c = image[y][x]
        g, halo_c, e = [sample(a, (x+.5)*len(a[0])/w-.5, (y+.5)*len(a)/h-.5) for a in [glow,halo,extra]]
        out = [c[k] + (g[k]*.65+e[k]*.35)*p[0] + halo_c[0]*[1,.25,.1][k]*p[1] + flash[k]*p[2]*.08*(1-luma(c)) for k in range(3)]
        value = [a + (clamp(b)-a)*f['intensity'] for a,b in zip(c,out)]
        value = [half(v) for v in value] if f['linear'] else [math.floor(clamp(srgb(v))*255+.5) for v in value]
        result.append([x,y,*value])
    return result

fixtures = []
def add(name, w, h, parameters, background=(12,17,23), patches=None, intensity=1, hdr=False):
    f = dict(name=name,width=w,height=h,parameters=parameters,background=background,
             patches=patches or [],intensity=intensity,linear=hdr)
    f['output'] = evaluate(f); fixtures.append(f)

for color in range(3):
    add('shadow-'+str(color), 16, 8, [0,0,.7,color])
for phase in range(4):
    add('subcell-'+str(phase), 128, 64, [.4,.5,.2,1], patches=[[60+phase,28+phase,61+phase,29+phase,255,255,255]])
for w,h in [(133,67),(67,133),(768,64),(2048,16),(3840,16)]:
    add('spatial-'+str(w), w,h,[.8,.9,.3,2], patches=[
        [w//4,h//4,w//4+3,h//4+3,255,245,235],
        [w//2,h//2,w//2+2,h//2+2,255,50,20],
        [w-6,0,w,3,220,255,255]])
add('strength', 96,48,[.7,.8,.4,0],intensity=.35,patches=[[30,10,40,25,255,255,255]])
for w,h in [(1,1),(1,7),(7,1)]: add('tiny-'+str(w)+'x'+str(h),w,h,[.5,.6,.4,1],background=(245,250,255))
add('linear-hdr',96,48,[.6,.5,.2,1],background=(.125,.3,1.5),patches=[[33,14,40,24,4,2,1]],hdr=True,intensity=.4)
add('linear-subwhite',133,67,[.8,.5,.3,2],background=(.03,.08,.1),patches=[[35,16,40,21,1,.9,.8]],hdr=True)
target=ROOT/'app/src/androidTest/assets/halation-reference.json'
target.write_text(json.dumps(dict(reference=SOURCE,description='CPU source-equation fixtures; not captured Apple GPU output',fixtures=fixtures),separators=(',',':'))+'\n')
print(f'{len(fixtures)} fixtures, {sum(len(f["output"]) for f in fixtures)} sampled pixels; {target.stat().st_size} bytes')
