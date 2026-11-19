#!/usr/bin/env python3
"""Render level-matched LavaSR and VoiceFixer comparisons using the pinned ONNX graphs."""

import argparse
from contextlib import contextmanager
import ctypes as C
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

import numpy as np
import onnxruntime as ort
import soundfile as sf
from scipy import signal


ROOT = Path(__file__).resolve().parent.parent
REFERENCE = ROOT / 'reference/YouRecApp/ThirdParty'
NATIVE = ROOT / 'app/src/main/c'
PCM = np.ctypeslib.ndpointer(dtype=np.float32, flags='C_CONTIGUOUS')
RATE = 48000


def build(output, files, compiler='cc', options=()):
    subprocess.run([compiler, '-O3', '-shared', '-fPIC', *options, *map(str, files), '-lm', '-ldl', '-o', str(output)], check=True)
    return C.CDLL(str(output))


def bind(library, name, types, result=C.c_int):
    fn = getattr(library, name)
    fn.argtypes, fn.restype = types, result
    return fn


@contextmanager
def native_engine(library, name, runtime, models, kind=None):
    prefix = 'yr_' + name
    types = [C.c_char_p, C.c_char_p, C.c_int]
    if kind is not None:
        types.append(C.c_int)
    create = bind(library, prefix + '_open', types + [C.c_char_p, C.c_size_t], C.c_void_p)
    render = bind(library, prefix + '_process', [C.c_void_p, PCM, PCM, C.c_int])
    error = bind(library, prefix + '_error', [C.c_void_p], C.c_char_p)
    close = bind(library, prefix + '_close', [C.c_void_p], None)
    message = C.create_string_buffer(512)
    args = [str(runtime).encode(), str(models).encode()] + ([] if kind is None else [kind])
    engine = create(*args, 2, message, len(message))
    if not engine:
        raise RuntimeError(message.value.decode())

    def process(wave):
        output = np.empty_like(wave)
        if not render(engine, wave, output, len(wave)):
            raise RuntimeError(error(engine).decode())
        return output

    try:
        yield process
    finally:
        close(engine)


def windows(wave, render, context, margin, stride):
    size = len(wave)
    output = np.empty_like(wave)
    overlap = context - 2 * margin - stride
    fade = np.arange(1, overlap + 1, dtype=np.float32) / (overlap + 1)
    for start in range(0, size, stride):
        indices = (start - margin + np.arange(context)) % (2 * (size - 1))
        indices = np.minimum(indices, 2 * (size - 1) - indices)
        block = render(wave[indices])
        count = min(stride, size - start)
        output[start:start + count] = block[margin:margin + count]
        n = min(overlap, count)
        if start and n:
            output[start:start + n] = tail[:n] * (1 - fade[:n]) + output[start:start + n] * fade[:n]
        tail = block[margin + count:margin + count + overlap]
    return output


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])


def reference_lava(library, models):
    features = bind(library, 'yr_lava_features', [PCM, PCM])
    reconstruct = bind(library, 'yr_lava_reconstruct', [PCM, PCM, PCM])
    merge = bind(library, 'yr_lava_merge', [PCM, PCM, PCM])
    backbone, head = (session(models / f'enhancer_{name}.onnx') for name in ('backbone', 'spec_head'))

    def process(wave):
        mel = np.empty((1, 80, 375), np.float32)
        restored, mixed = np.empty_like(wave), np.empty_like(wave)
        if not features(wave, mel):
            raise RuntimeError('Reference LavaSR frontend failed')
        hidden, = backbone.run(None, {'mel': mel})
        real, imaginary = head.run(None, {'hidden': hidden})
        if not reconstruct(real, imaginary, restored) or not merge(wave, restored, mixed):
            raise RuntimeError('Reference LavaSR reconstruction failed')
        return mixed

    return process


def reference_voicefixer(library, models):
    features = bind(library, 'yr_voicefixer_features', [PCM, PCM, PCM])
    bank = np.fromfile(REFERENCE / 'VoiceFixer/VoiceFixer-Mel.bin', dtype='<f4')
    if not np.array_equal(bank, np.load(models / 'mel_filters.npy').ravel()):
        raise RuntimeError('VoiceFixer mel banks differ')
    analysis, vocoder = (session(models / f'{name}.onnx') for name in ('analysis', 'vocoder'))

    def process(wave):
        mel = np.empty((1, 1, 256, 128), np.float32)
        if not features(wave, bank, mel):
            raise RuntimeError('Reference VoiceFixer frontend failed')
        log_mel, = analysis.run(None, {'mel_orig': mel})
        restored, = vocoder.run(None, {'mel': np.power(10, np.minimum(log_mel, 5))})
        start = (restored.size - len(wave)) // 2
        return restored.ravel()[start:start + len(wave)]

    return process


def detail(wave, process):
    return windows(wave, process, 192000, 16384, 159232)


def restore(wave, process):
    down = signal.resample_poly(wave, 147, 160)
    output = windows(down, process, 112455, 28224, 52920)
    return signal.resample_poly(output, 160, 147)[:len(wave)]


def loudness(path):
    result = subprocess.run(['ffmpeg', '-hide_banner', '-nostats', '-i', str(path), '-af', 'ebur128=framelog=verbose',
                             '-f', 'null', '-'], check=True, capture_output=True, text=True).stderr
    return float(re.findall(r'\bI:\s*(-?[\d.]+) LUFS', result)[-1])


def listening_files(folder, variants):
    folder.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        gains = {}
        for name, wave in variants.items():
            path = Path(scratch) / f'{name}.wav'
            sf.write(path, wave, RATE, subtype='FLOAT')
            gains[name] = -23 - loudness(path)
    peak = max(float(np.max(np.abs(wave))) * 10**(gains[name] / 20) for name, wave in variants.items())
    attenuation = min(1, .89 / max(peak, 1e-6))
    for name, wave in variants.items():
        sf.write(folder / f'{name}.wav', wave * (attenuation * 10**(gains[name] / 20)), RATE, subtype='PCM_24')
    return -23 + 20 * np.log10(attenuation)


def error(output, target):
    delta = np.asarray(output - target, np.float64)
    return {'rms': float(np.sqrt(np.mean(delta**2))), 'max': float(np.max(np.abs(delta)))}


def fixtures(name):
    dry, rate = sf.read(name, dtype='float32', always_2d=True)
    if rate != RATE:
        raise ValueError('Use a 48 kHz speech fixture')
    clean = dry[:6 * RATE].mean(axis=1)
    if len(clean) < 2 * RATE or not np.isfinite(clean).all() or np.max(np.abs(clean)) < .001:
        raise ValueError('Use at least two seconds of audible speech')
    clean *= .7 / np.max(np.abs(clean))
    narrow = signal.resample_poly(signal.resample_poly(clean, 1, 6), 6, 1)[:len(clean)]
    clipped = np.clip(clean, -.12, .12) + np.random.default_rng(24).normal(0, .012, len(clean))
    return {'full-band': clean, '8khz-source': narrow, 'clipped-noisy': clipped.astype(np.float32)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('speech', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--models', type=Path, default=ROOT / '.build/models')
    parser.add_argument('--headers', type=Path, default=ROOT / '.build/runtime-sources/onnxruntime-current')
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--lava-before', type=Path)
    args = parser.parse_args()
    clips = fixtures(args.speech)
    device = args.output / 'device-inputs'
    device.mkdir(parents=True, exist_ok=True)
    for name, wave in clips.items():
        path = device / f'{name}.wav'
        sf.write(path, wave, RATE, subtype='PCM_16')
        clips[name] = sf.read(path, dtype='float32')[0]
    lava = args.models / 'studio-lava-v1'
    voicefixer = args.models / 'voicefixer-256'
    data = {name: {'dry': wave} for name, wave in clips.items()}

    with tempfile.TemporaryDirectory() as scratch:
        folder = Path(scratch)
        swift_dsp = build(folder / 'reference.so', [REFERENCE / 'LavaSR/LavaDSP.cpp', REFERENCE / 'VoiceFixer/VoiceFixerDSP.cpp'], 'c++', ['-std=c++17'])
        library = build(folder / 'myrec.so', [NATIVE / file for file in ('lava.c', 'neural.c', 'inference.c', 'fft.c')],
                        options=['-std=c11', '-I' + str(args.headers)])
        if args.lava_before:
            with native_engine(C.CDLL(str(args.lava_before.resolve())), 'lava', args.runtime.resolve(), lava) as engine:
                for name, wave in clips.items():
                    data[name]['lava-before'] = wave + .35 * (detail(wave, engine) - wave)
        with native_engine(library, 'lava', args.runtime.resolve(), lava) as engine:
            for name, wave in clips.items():
                data[name]['lava-myrec'] = wave + .35 * (detail(wave, engine) - wave)
        engine = reference_lava(swift_dsp, lava)
        for name, wave in clips.items():
            data[name]['lava-yourec-dsp'] = wave + .35 * (detail(wave, engine) - wave)
        del engine
        with native_engine(library, 'neural', args.runtime.resolve(), voicefixer, 1) as engine:
            for name, wave in clips.items():
                data[name]['voicefixer-myrec'] = wave + .7 * (restore(wave, engine) - wave)
        engine = reference_voicefixer(swift_dsp, voicefixer)
        for name, wave in clips.items():
            data[name]['voicefixer-yourec-dsp'] = wave + .7 * (restore(wave, engine) - wave)
        del engine

    with args.speech.open('rb') as source:
        report = {'source_sha256': hashlib.file_digest(source, 'sha256').hexdigest(), 'onnxruntime': ort.__version__,
                  'lava_wet': .35, 'voicefixer_wet': .7, 'reference': 'YouRec C++ DSP with the same ONNX graphs', 'cases': {}}
    report['reference_commit'] = subprocess.check_output(['git', '-C', str(REFERENCE), 'rev-parse', 'HEAD'], text=True).strip()
    for name, variants in data.items():
        metrics = {key: error(value, variants['voicefixer-yourec-dsp' if key.startswith('voicefixer') else 'lava-yourec-dsp'])
                   for key, value in variants.items() if key not in ('dry', 'lava-yourec-dsp', 'voicefixer-yourec-dsp')}
        digest = hashlib.sha256((device / f'{name}.wav').read_bytes()).hexdigest()
        report['cases'][name] = {'samples': len(variants['dry']), 'sha256': digest, 'comparison': metrics,
                                 'listening_lufs': listening_files(args.output / name, variants)}
    (args.output / 'audio-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
