#!/usr/bin/env python3
"""Prepare the documented Colosseum clip and an original ambient score."""
import array
import hashlib
import math
import pathlib
import subprocess
import wave

root = pathlib.Path(__file__).resolve().parent.parent
folder = root / '.build/rome-showcase'
source = folder / 'colosseum-original.mp4'
expected = '348c98656593495c283e82301679112b76f817c1a3422b37a7674d0050413b1a'
with source.open('rb') as stream:
    if hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
        raise SystemExit('Unexpected source video; see docs/screenshots/README.md')

rate = 48000
duration = 11.28
samples = array.array('h')
chords = [(130.8128, 155.5635, 195.9977), (103.8262, 130.8128, 155.5635),
          (116.5409, 146.8324, 174.6141)]
for n in range(round(rate * duration)):
    t = n / rate
    notes = chords[min(2, int(t / 4.5))]
    beat = t % .75
    envelope = (1 - math.exp(-beat * 55)) * math.exp(-beat * 3.8)
    fade = min(1, t / .7, (duration - t) / 1.2)
    pad = sum(math.sin(2 * math.pi * f * t) + .18 * math.sin(2 * math.pi * f * 2 * t)
              for f in notes) / 3
    bass = math.sin(2 * math.pi * notes[0] * .5 * t)
    value = fade * (.16 * envelope * pad + .045 * bass)
    samples.extend((int(value * 32767), int(value * (.96 + .03 * math.sin(t)) * 32767)))
with wave.open(str(folder / 'ambient-score.wav'), 'wb') as out:
    out.setnchannels(2)
    out.setsampwidth(2)
    out.setframerate(rate)
    out.writeframes(samples.tobytes())
subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-ss', '3', '-i', str(source),
    '-i', str(folder / 'ambient-score.wav'), '-t', str(duration), '-map', '0:v:0', '-map', '1:a:0',
    '-vf', 'scale=1920:1080', '-c:v', 'libx264', '-preset', 'fast', '-crf', '18',
    '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-b:a', '192k', '-movflags', '+faststart',
    '-y', str(folder / 'colosseum.mp4')], check=True)
