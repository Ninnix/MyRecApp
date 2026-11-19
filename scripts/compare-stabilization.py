#!/usr/bin/env python3
"""Compare the Android and reference motion estimators on identical decoded frames."""

import argparse
import ctypes as C
import hashlib
import json
import math
from pathlib import Path
import subprocess
import tempfile
import time

import numpy as np


ROOT = Path(__file__).resolve().parent.parent
REFERENCE = ROOT / 'reference/YouRecApp'
PIXELS = np.ctypeslib.ndpointer(dtype=np.uint8, flags='C_CONTIGUOUS')


class ReferenceMotion(C.Structure):
    _fields_ = [(name, C.c_double) for name in ('x', 'y', 'angle', 'log_scale', 'inlier_ratio')] + [
        ('inliers', C.c_int), ('valid', C.c_int),
    ]


def android_tracker(path):
    library = C.CDLL(str(path))
    function = getattr(library, 'yr_camera_similarity', library.yr_similarity)
    function.argtypes = [PIXELS, PIXELS, C.c_int, C.c_int, C.POINTER(C.c_float)]
    function.restype = C.c_int

    def motion(previous, current):
        height, width = previous.shape
        result = (C.c_float * 5)()
        if not function(previous, current, width, height, result):
            return None
        a, b, x, y, _ = result
        return ((a - 1) * width / 2 - b * height / 2 + x,
                b * width / 2 + (a - 1) * height / 2 + y,
                math.atan2(b, a), math.log(math.hypot(a, b)))

    return motion


def reference_tracker(path):
    function = C.CDLL(str(path)).yr_estimate_motion
    function.argtypes = [PIXELS, PIXELS, C.c_int, C.c_int]
    function.restype = ReferenceMotion

    def motion(previous, current):
        height, width = previous.shape
        result = function(previous, current, width, height)
        return (result.x, result.y, result.angle, result.log_scale) if result.valid else None

    return motion


def decode(path, seconds):
    shape = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries',
        'stream=width,height:stream_side_data=rotation', '-of', 'json', str(path),
    ]))['streams'][0]
    width, height = shape['width'], shape['height']
    rotation = next((side['rotation'] for side in shape.get('side_data_list', []) if 'rotation' in side), 0)
    if round(rotation / 90) % 2:
        width, height = height, width
    ratio = min(1, 640 / max(width, height))
    width, height = (max(64, round(value * ratio)) for value in (width, height))
    raw = subprocess.check_output([
        'ffmpeg', '-v', 'error', '-i', str(path), '-t', str(seconds), '-an', '-vf', f'scale={width}:{height}',
        '-fps_mode', 'passthrough', '-pix_fmt', 'gray', '-f', 'rawvideo', '-',
    ])
    return np.frombuffer(raw, np.uint8).reshape(-1, height, width)


def compare(pairs, android, reference):
    results = []
    android_time = reference_time = 0
    for previous, current in pairs:
        start = time.perf_counter()
        a = android(previous, current)
        middle = time.perf_counter()
        b = reference(previous, current)
        android_time += middle - start
        reference_time += time.perf_counter() - middle
        results.append((a, b))
    difference = np.array([np.subtract(a, b) for a, b in results if a is not None and b is not None])
    row = {
        'pairs': len(results),
        'myrec_tracked': sum(a is not None for a, _ in results),
        'yourec_tracked': sum(b is not None for _, b in results),
        'both_tracked': len(difference),
        'myrec_ms_per_pair': 1000 * android_time / len(results),
        'yourec_ms_per_pair': 1000 * reference_time / len(results),
    }
    if len(difference):
        translation = np.hypot(difference[:, 0], difference[:, 1])
        row.update(translation_px_median=float(np.median(translation)), translation_px_p95=float(np.percentile(translation, 95)),
                   angle_degrees_p95=float(np.percentile(np.abs(np.degrees(difference[:, 2])), 95)),
                   log_scale_p95=float(np.percentile(np.abs(difference[:, 3]), 95)))
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('videos', nargs='+', type=Path)
    parser.add_argument('--seconds', type=float, default=8)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--myrec-library', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as scratch:
        folder = Path(scratch)
        ref = folder / 'yourec.so'
        subprocess.run(['c++', '-std=c++17', '-O3', '-shared', '-fPIC',
                        str(REFERENCE / 'YouRec/StabilizationMotion.cpp'), '-o', str(ref)], check=True)
        lib = args.myrec_library
        if lib is None:
            lib = folder / 'myrec.so'
            subprocess.run(['cc', '-std=c11', '-O3', '-shared', '-fPIC',
                            str(ROOT / 'app/src/main/c/stabilize.c'), '-lm', '-o', str(lib)], check=True)
        android, reference = android_tracker(lib.resolve()), reference_tracker(ref)
        clips = []
        first_scene = None
        for path in args.videos:
            frames = decode(path, args.seconds)
            if len(frames) < 2:
                parser.error(f'{path} decoded fewer than two frames')
            if first_scene is None:
                first_scene = frames[0].copy()
            with path.open('rb') as source:
                identity = hashlib.file_digest(source, 'sha256').hexdigest()
            clips.append({'file': path.name, 'sha256': identity, 'frame_shape': frames.shape[1:],
                          **compare(zip(frames[:-1], frames[1:]), android, reference)})
        height, width = frames[0].shape
        patch = frames[0, height // 3:height // 2, width // 3:width // 2].copy()
        previous = np.full((height, width), 96, np.uint8)
        current = previous.copy()
        h, w = patch.shape
        previous[height // 3:height // 3 + h, width // 3:width // 3 + w] = patch
        current[height // 3 + 4:height // 3 + h + 4, width // 3 + 12:width // 3 + w + 12] = patch
        y = np.arange(height) * first_scene.shape[0] // height
        x = np.arange(width) * first_scene.shape[1] // width
        cut = first_scene[np.ix_(y, x)] if len(clips) > 1 else np.rot90(frames[0], 2).copy()
        report = {'reference_commit': subprocess.check_output(['git', '-C', str(REFERENCE), 'rev-parse', 'HEAD'], text=True).strip(),
                  'clips': clips,
                  'moving_patch_on_flat_background': compare([(previous, current)], android, reference),
                  'hard_cut': compare([(frames[0], cut)], android, reference)}
    result = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.write_text(result)
    print(result, end='')


if __name__ == '__main__':
    main()
