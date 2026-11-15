#!/usr/bin/env python3
"""SDR fixtures evaluated from YouRec's released VideoEffects.metal equations."""
import json, math, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = 'faf1815e82e1f2d5f03034a583363d3c3af464a2'

def clamp(x, low=0., high=1.): return min(high, max(low, x))
def mix(a, b, t): return a + (b - a) * t
def linear(x): return x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4
def srgb(x): return x * 12.92 if x <= .0031308 else 1.055 * x ** (1 / 2.4) - .055
def luma(c): return sum(a * b for a, b in zip(c, [.2126, .7152, .0722]))
def smooth(a, b, x):
    t = clamp((x - a) / (b - a))
    return t * t * (3 - 2 * t)
def hue(c):
    hi, lo = max(c), min(c)
    d = hi - lo
    h = 0
    if d > 1e-6:
        if hi == c[0]: h = ((c[1] - c[2]) / d + 6) % 6
        elif hi == c[1]: h = (c[2] - c[0]) / d + 2
        else: h = (c[0] - c[1]) / d + 4
    return h / 6, d / hi if hi > 1e-6 else 0, hi
def distance(a, b):
    d = abs(a - b)
    return min(d, 1 - d)
def contrast(x, strength):
    x = clamp(x)
    a, b = x ** strength, (1 - x) ** strength
    return a / max(a + b, 1e-6)

def sample(kernel, c, uv, size, p):
    y = luma(c)
    if kernel == 0:
        tint = [[.243137,.435294,.658824],[.85098,.643137,.254902],[.721569,.262745,.184314],
                [.431373,.560784,.305882],[.541176,.415686,.270588]][int(p[0])]
        dye = [linear(x) for x in tint]
        l = linear(clamp((srgb(y) - .5) * p[2] + .5))
        return [l * d if p[1] < .5 else mix(d * .15, 1, smooth(0, 1, l)) for d in dye]
    if kernel == 1:
        h = hue([srgb(x) for x in c])
        skin = smooth(.03,.055,h[0]) * (1 - smooth(.115,.15,h[0])) * smooth(.12,.3,h[1])
        out = [c[i] + (c[i] - (c[(i+1)%3] + c[(i+2)%3]) * .5) * (p[0] - 1) * (1 - .65 * skin) for i in range(3)]
        red = 1 - smooth(15/360,25/360,distance(h[0],0))
        out[1] -= red * p[1] * .15 * (1 - skin)
        out[2] -= red * p[1] * .15 * (1 - skin)
        l = luma(out)
        out = [v + (l*l*(3-2*l)-l)*.14 for v in out]
        out[0] *= 1+p[2]*.06
        out[2] *= 1-p[2]*.06
        high = max(out)
        if high > .9: out = [.9 + (v-.9)/(1+max(0,high-.9)) for v in out]
        return [mix(out[i],c[i],skin*.35) for i in range(3)]
    if kernel == 2:
        overlay = [2*v*y if y < .5 else 1-2*(1-v)*(1-y) for v in c]
        bleached = [mix(y,v,.5) for v in overlay]
        gray = luma(bleached)
        return [mix(c[i],mix(gray,bleached[i],1-.3*p[0]),p[0]) for i in range(3)]
    if kernel == 3:
        h = hue([srgb(x) for x in c])
        sky = (1-smooth(.25,.38,uv[1])) * smooth(.5,.85,y) * (1-smooth(.08,.18,distance(h[0],.61)))
        out = [mix(y,c[i],.4) * 2**(-3*p[0]) * mix(1,[.55,.7,1][i],p[1]) * (1-.5*sky) for i in range(3)]
        if p[2] > .5: out = [mix(v,y*2**(-.7*p[0]),smooth(.94,.99,y)*.7) for v in out]
        return [v*(1-.15*smooth(.2,.75,math.hypot(uv[0]-.5,uv[1]-.5))) for v in out]
    perceptual = srgb(y)
    l = linear(mix(contrast(perceptual,p[0]),smooth(.35,.65,perceptual),.4))
    dx, dy = [(v-.5)*2 for v in uv]
    if p[2] > .5: dx *= size[0] / size[1]
    iris = clamp(1-clamp(math.hypot(dx,dy)/1.25)**2.5)
    l *= mix(1,iris,p[1])
    return [l*(linear(v) if p[3] > .5 else 1) for v in [.82,.84,.52]]

colors = [[0,0,0],[255,255,255],[128,128,128],[255,0,0],[0,255,0],[0,0,255],
          [200,140,100],[40,210,230],[235,210,25],[25,15,45]]
recipes = [(0,[t,m,c]) for t in range(5) for m in range(2) for c in [.8,1.15,1.4]]
recipes += [(1,p) for p in [[1,0,0],[1.4,.6,.2],[1.8,1,-1],[1.8,1,1]]]
recipes += [(2,[s]) for s in [0,.3,.7,1]]
recipes += [(3,p) for p in [[0,0,0],[.6,.7,1],[1,1,0],[1,1,1]]]
recipes += [(4,[c,v,i,t]) for c in [1.5,3] for v in [0,1] for i in range(2) for t in range(2)]
fixtures = []
for index,(kernel,parameters) in enumerate(recipes):
    width,height = (10,7) if index % 2 else (7,10)
    strength = [1,.25,0][index % 3]
    pixels,output = [],[]
    for y in range(height):
        for x in range(width):
            color = colors[(x+y) % len(colors)]
            pixels.append(color)
            c = [linear(v/255) for v in color]
            out = sample(kernel,c,((x+.5)/width,(y+.5)/height),(width,height),parameters)
            output.append([int(clamp(srgb(mix(a,clamp(b),strength)))*255+.5) for a,b in zip(c,out)])
    fixtures.append(dict(kernel=kernel,parameters=parameters,intensity=strength,width=width,height=height,input=pixels,output=output))
path = ROOT/'app/src/androidTest/assets/film-color-reference.json'
path.write_text(json.dumps(dict(source=f'YouRec VideoEffects.metal {SOURCE}; double-precision SDR equations',fixtures=fixtures),separators=(',',':'))+'\n')
print(f'{len(fixtures)} fixtures written to {path.relative_to(ROOT)}')
