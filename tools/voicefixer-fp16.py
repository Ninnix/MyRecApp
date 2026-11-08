#!/usr/bin/env python3
"""Build isolated VoiceFixer precision trials; production downloads stay unchanged."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.transformers.float16 import convert_float_to_float16
from onnxruntime.transformers.onnx_model import OnnxModel

ROOT = Path(__file__).resolve().parent.parent


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def storage(model):
    casts = []
    for tensor in model.graph.initializer:
        if tensor.data_type != TensorProto.FLOAT or math.prod(tensor.dims) < 16:
            continue
        data = numpy_helper.to_array(tensor)
        if not np.isfinite(data).all() or np.max(np.abs(data)) > 65504:
            continue
        name = tensor.name
        stored = name + '__yourec_fp16'
        tensor.CopyFrom(numpy_helper.from_array(data.astype(np.float16), stored))
        casts.append(helper.make_node('Cast', [stored], [name],
                                      to=TensorProto.FLOAT, name=stored + '__decode'))
    nodes = list(model.graph.node)
    del model.graph.node[:]
    model.graph.node.extend(casts + nodes)
    return model


def compare(directory):
    runs = {name: json.loads((directory / name / 'result.json').read_text())
            for name in ['fp32', 'fp16', 'fp16-storage']}
    report = {'runs': runs, 'comparisons': []}
    for run in runs.values():
        run['median_seconds'] = float(np.median([case['seconds'] for case in run['cases']]))
    for case in runs['fp32']['cases']:
        name = case['case']
        reference = np.fromfile(directory / 'fp32' / (name + '.f32'), dtype='<f4').astype(float)
        for precision in ['fp16', 'fp16-storage']:
            output = np.fromfile(directory / precision / (name + '.f32'), dtype='<f4').astype(float)
            if reference.shape != output.shape or not np.isfinite(output).all():
                raise ValueError('Invalid output: ' + precision + '/' + name)
            delta = output - reference
            energy = np.mean(reference ** 2)
            error = np.mean(delta ** 2)
            report['comparisons'].append({
                'case': name, 'precision': precision,
                'correlation': float(np.corrcoef(reference, output)[0, 1]),
                'maximum_error': float(np.max(np.abs(delta))),
                'rms_error': float(np.sqrt(error)),
                'signal_to_difference_db': float(10 * np.log10((energy + 1e-30) / (error + 1e-30))),
                'peak_ratio': float(np.max(np.abs(output)) / max(1e-12, np.max(np.abs(reference)))),
                'rms_ratio': float(np.sqrt(np.mean(output ** 2) / max(1e-30, energy))),
            })
    (directory / 'comparison.json').write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--precision', choices=['fp16', 'fp16-storage'], default='fp16-storage')
    parser.add_argument('--install', action='store_true')
    parser.add_argument('--compare', type=Path, metavar='DIRECTORY')
    args = parser.parse_args()
    if args.compare:
        if args.install:
            parser.error('--compare and --install cannot be combined')
        print(json.dumps(compare(args.compare), indent=2, allow_nan=False))
        return
    spec = json.loads((ROOT / 'data/models.json').read_text())['voicefixer']
    source = ROOT / '.build/models' / spec['directory']
    destination = ROOT / ('.build/models-' + args.precision) / spec['directory']
    for item in spec['files']:
        path = source / item['name']
        if not path.is_file() or path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise SystemExit('Prepare the verified FP32 models first: tools/prepare-audio.py --models voicefixer')
    destination.mkdir(parents=True, exist_ok=True)
    result = {'precision': args.precision, 'float32_io': True, 'files': []}
    for item in spec['files']:
        path = source / item['name']
        target = destination / path.name
        if path.suffix == '.onnx':
            model = onnx.load(path)
            if args.precision == 'fp16':
                model = convert_float_to_float16(model, keep_io_types=True)
                OnnxModel(model).topological_sort()
            else:
                model = storage(model)
            onnx.checker.check_model(model)
            onnx.save(model, target)
        else:
            shutil.copy2(path, target)
        result['files'].append({'name': path.name, 'fp32_bytes': item['bytes'],
                                'bytes': target.stat().st_size, 'sha256': digest(target)})
    result['bytes'] = sum(item['bytes'] for item in result['files'])
    (destination / 'precision.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if args.install:
        adb = os.environ.get('ADB', str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb'))
        remote = '/sdcard/Android/data/org.yourec.app/files/models/voicefixer-' + args.precision
        subprocess.run([adb, 'shell', 'mkdir', '-p', remote], check=True)
        for item in result['files']:
            subprocess.run([adb, 'push', str(destination / item['name']), remote + '/'], check=True)


if __name__ == '__main__':
    main()
