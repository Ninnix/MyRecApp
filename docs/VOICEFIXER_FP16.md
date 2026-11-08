# VoiceFixer precision trial

The production model remains FP32. Two isolated conversions were tested using
the same C11 frontend, ONNX Runtime 1.31.1 C API and two inference threads.
DeepFilterNet3 remains unchanged.

| Variant | Files, decimal MB | Android median inference | Result |
| --- | ---: | ---: | --- |
| Original FP32 | 415.45 | 4.71 s | Reference |
| FP16 graph, FP32 public inputs/outputs | 208.38 | 4.58 s | Rejected: large audio errors, including loud output from silence |
| FP16 stored weights, FP32 computation | 208.42 | 4.81 s | Candidate: close numerical agreement on tested fixtures |

Android timings are one pass over normal speech, speech attenuated by 40 dB,
speech with added noise, and silence. Each input is two seconds long; the
VoiceFixer network still evaluates its fixed analysis window. These are short
tests on the attached OnePlus 15, not universal latency measurements.

The storage candidate saves 207.03 MB, or 49.8%. Its Android output correlation
against FP32 was at least 0.99993. Signal-to-difference ratios were 41.58–46.81 dB
and output RMS changed by less than 0.31%. This measures agreement with our
FP32 pipeline; it does not establish perceptual transparency or iOS parity.
Eight host cases across two source excerpts also stayed finite, with at least
0.99990 correlation and 37.17 dB signal-to-difference ratio. Host timing was
affected by concurrent work and is not used for the latency comparison.

The FP16 graph passed model loading and finite-output checks, but failed the
audio comparison. On Android, silent input produced an output RMS of 0.314,
about 369 times the FP32 result. Passing the instrumentation load/finite check
alone is not acceptance of a precision conversion.

## Conversion modes

`fp16` uses ONNX Runtime's graph converter and keeps the C frontend's public
tensor interface in FP32. Unsupported CPU operations may be promoted by the
runtime; this is not a guarantee of exclusively FP16 hardware computation.

`fp16-storage` converts eligible FP32 initializers to FP16 and inserts casts
back to FP32 for their consumers. Small constants, unsafe values and the C11
mel-filter file retain their original representation. The graph's arithmetic
stays FP32. Runtime optimization may expand the stored weights during loading,
so smaller files do not imply lower working memory or faster inference.

Neither mode changes the application download manifest or installed production
weights. The candidate has only been installed in a separate test directory.
Its 208.42 MB size describes the local converted files, not a published model
download. The iOS estimate of 191 MB could involve different packaging or
precision; the exact iOS format remains unknown.

## Reproduce

Use a Python environment with the existing development packages `numpy`, `onnx`
and `onnxruntime`. The local research environment contains version 1.31.1 of
ONNX Runtime. Prepare the pinned FP32 inputs first:

```sh
python3 tools/prepare-audio.py --models voicefixer --install
python3 tools/voicefixer-fp16.py --precision fp16 --install
python3 tools/voicefixer-fp16.py --precision fp16-storage --install
./tools/build.sh :app:assembleDebug :app:assembleDebugAndroidTest --offline
python3 tools/test-device.py --audio-precision --precision fp32
python3 tools/test-device.py --audio-precision --precision fp16
python3 tools/test-device.py --audio-precision --precision fp16-storage
adb pull /sdcard/Android/data/org.yourec.app/files/voicefixer-precision .build/voicefixer-precision
python3 tools/voicefixer-fp16.py --compare .build/voicefixer-precision
```

The converter verifies input hashes, checks each generated graph and records
output sizes and hashes in `precision.json`. Instrumentation writes float32
waveforms and timing JSON in each trial's directory. The comparison produces
`comparison.json`; inspect its errors even when all finite-output checks pass.
Raw models, audio and working reports remain local artifacts.

Core ML also distinguishes weight compression from computation precision; see
[Apple's weight compression API](https://apple.github.io/coremltools/docs/source/coremltools.optimize.coreml.post_training_quantization.html).
