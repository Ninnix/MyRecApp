#!/usr/bin/env python3
"""Generate a lossless Main10 HLG ramp for precision and headroom checks."""
from pathlib import Path
import struct
import subprocess

root = Path(__file__).resolve().parent.parent
work = root/'.build/hdr-ramp'
work.mkdir(parents=True, exist_ok=True)
width, height = 1280, 720
line = struct.pack('<'+'H'*width, *(64+round(x*876/(width-1)) for x in range(width)))
source = work/'ramp.yuv'
source.write_bytes(line*height + struct.pack('<H', 512)*(width*height//2))
subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pixel_format', 'yuv420p10le',
    '-video_size', f'{width}x{height}', '-framerate', '30', '-color_primaries', 'bt2020',
    '-color_trc', 'arib-std-b67', '-colorspace', 'bt2020nc', '-color_range', 'tv', '-i', str(source), '-vf', 'loop=loop=5:size=1:start=0',
    '-frames:v', '6', '-c:v', 'libx265', '-pix_fmt', 'yuv420p10le', '-x265-params',
    'lossless=1:repeat-headers=1:log-level=error:pools=1', '-color_primaries', 'bt2020',
    '-color_trc', 'arib-std-b67', '-colorspace', 'bt2020nc', '-color_range', 'tv',
    str(root/'app/src/androidTest/assets/hdr-ramp.mp4')], check=True)
