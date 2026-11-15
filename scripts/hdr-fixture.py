#!/usr/bin/env python3
"""Generate the small, synthetic BT.2020 HLG colour regression fixture."""
import json
from pathlib import Path
import struct
import subprocess

root = Path(__file__).resolve().parent.parent
work = root/'.build/hdr-color'
work.mkdir(parents=True, exist_ok=True)
colors = [(0.45,0.45,0.45),(0.7,0.7,0.7),(0.65,0.5,0.35),(0.65,0.6,0.25),
          (0.6,0.3,0.25),(0.3,0.6,0.3),(0.3,0.4,0.6),(0.8,0.75,0.7)]
planes = [[],[],[]]
for width,height in [(320,180),(160,90),(160,90)]:
    channel = len([p for p in planes if p])
    for y in range(height):
        for x in range(width):
            r,g,b = colors[x*8//width]
            luma = .2627*r+.678*g+.0593*b
            values = [64+876*luma,512+896*(b-luma)/1.8814,512+896*(r-luma)/1.4746]
            planes[channel].append(round(values[channel]))
raw = work/'patches.yuv'
raw.write_bytes(b''.join(struct.pack('<'+str(len(p))+'H',*p) for p in planes))
assets = root/'app/src/androidTest/assets'
(assets/'hdr-colors.yuv').write_bytes(raw.read_bytes())
subprocess.run(['ffmpeg','-v','error','-f','rawvideo','-pixel_format','yuv420p10le',
    '-video_size','320x180','-framerate','30','-color_primaries','bt2020',
    '-color_trc','arib-std-b67','-colorspace','bt2020nc','-color_range','tv','-i',str(raw),'-vf','loop=loop=29:size=1:start=0',
    '-frames:v','30','-c:v','libx265','-pix_fmt','yuv420p10le','-x265-params',
    'lossless=1:repeat-headers=1:log-level=error:pools=1',
    '-color_primaries','bt2020','-color_trc','arib-std-b67','-colorspace','bt2020nc',
    '-color_range','tv','-y',str(assets/'hdr-colors.mp4')],check=True)
(assets/'hdr-colors.json').write_text(json.dumps(colors))
