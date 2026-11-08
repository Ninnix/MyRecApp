#!/usr/bin/env python3
"""Check encoded HLG colour patches from the Android HDR instrumentation suite."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--device', action='store_true')
parser.add_argument('file', nargs='?', type=Path, default=root/'artifacts/hdr-color-roundtrip.mp4')
args = parser.parse_args()
args.file.parent.mkdir(parents=True, exist_ok=True)
if args.device:
    sdk = Path(os.environ.get('ANDROID_HOME', Path.home()/'Android/Sdk'))
    adb = os.environ.get('ADB', str(sdk/'platform-tools/adb'))
    subprocess.run([adb, 'pull', '/sdcard/Android/data/org.yourec.app/files/hdr-color-roundtrip.mp4', str(args.file)], check=True)
probe = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-show_streams', '-of', 'json', str(args.file)]))
video = next(s for s in probe['streams'] if s['codec_type'] == 'video')
assert video['profile'] == 'Main 10' and video['pix_fmt'] == 'yuv420p10le'
assert video['color_space'] == 'bt2020nc' and video['color_transfer'] == 'arib-std-b67'
assert video['width'] == 320 and video['height'] == 180
raw = subprocess.check_output(['ffmpeg', '-v', 'error', '-ss', '0.2', '-i', str(args.file),
    '-frames:v', '1', '-pix_fmt', 'yuv420p10le', '-f', 'rawvideo', '-'])
expected = json.loads((root/'app/src/androidTest/assets/hdr-colors.json').read_text())
maximum = 0
for i, rgb in enumerate(expected):
    y = struct.unpack_from('<H', raw, (90*320+i*40+20)*2)[0]
    u = struct.unpack_from('<H', raw, 320*180*2+(45*160+i*20+10)*2)[0]
    v = struct.unpack_from('<H', raw, 320*180*2+160*90*2+(45*160+i*20+10)*2)[0]
    luma, cb, cr = (y-64)/876, (u-512)/896, (v-512)/896
    r, b = luma+1.4746*cr, luma+1.8814*cb
    g = (luma-.2627*r-.0593*b)/.678
    maximum = max(maximum, *(abs(a-b) for a,b in zip([r,g,b], rgb)))
result = {'patches':len(expected), 'maximum_hlg_channel_error':maximum,
          'profile':video['profile'], 'pixel_format':video['pix_fmt'],
          'color_space':video['color_space'], 'transfer':video['color_transfer']}
assert maximum < 2/255, result
(root/'artifacts/hdr-color-roundtrip.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
