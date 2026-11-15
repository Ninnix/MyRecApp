#!/usr/bin/env python3
"""Generate a repeatable 60 fps shake fixture without external footage."""
import pathlib, random, struct, subprocess, zlib

root = pathlib.Path(__file__).resolve().parent.parent
work = root / '.build' / 'stabilization-fixture'
work.mkdir(parents=True, exist_ok=True)
width, height, block = 720, 420, 4
rng = random.Random(1729)
cells = [[rng.randrange(32, 224) for _ in range(width//block)] for _ in range(height//block)]
rows = bytearray()
for y in range(height):
    rows.append(0)
    for x in range(width):
        value = cells[y//block][x//block]
        rows.extend((value, value, value))

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind+data))

image = work / 'texture.png'
image.write_bytes(b'\x89PNG\r\n\x1a\n' +
    chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) +
    chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))
output = root / 'app/src/androidTest/assets/stabilization-shake.mp4'
subprocess.run(['ffmpeg', '-y', '-v', 'error', '-loop', '1', '-framerate', '60', '-i', str(image),
    '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000:duration=2',
    '-vf', "crop=640:360:x='40+8*sin(2*PI*4.1*t)':y='30+6*sin(2*PI*3.3*t)'",
    '-t', '2', '-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p', '-g', '60',
    '-c:a', 'aac', '-b:a', '128k', '-video_track_timescale', '60000', str(output)], check=True)
print(output.relative_to(root))
