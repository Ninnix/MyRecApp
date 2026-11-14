# Models and inference

Models run on the phone. The application calls external runtimes through C APIs;
[runtimes.json](../runtimes.json) pins them. Download URLs, sizes, and hashes are
in [data/models.json](../data/models.json). The Android backend is ONNX Runtime
on the CPU, with the Qwen C runtime for captions.

## Included models

RNNoise is built with its network and needs no download. Existing voice-isolation
filters use it. PPHumanSeg is a bundled FP32 graph of about 5.9 MB. Color Isolation
uses its mask to keep people in color; detection reads the source before color
effects. It can also keep a chosen hue.

Hair, occlusion, movement, and multiple people need more testing. The model
neither identifies people nor sends frames away. It does not reproduce Apple's
Vision segmentation or the iOS temporal matte processing.

## Audio downloads

Speech detail uses LavaSR v2 for restoration and bandwidth extension. Its four
files total about 56 MB. C11 implements the mel frontend, neural graph calls,
inverse transform, and spectral crossover. All 80 learned tensors, containing
13,985,282 parameters, match the public
[LavaSR v2 checkpoint](https://huggingface.co/YatharthS/LavaSR/tree/b98dc8be472da45ab7b6346ad7997e1dfeb5911d/enhancer_v2),
allowing ONNX's linear-weight transposes. The older `v1` directory names refer
to our first integration, not LavaSR v1; keeping them preserves downloads.

Noise reduction uses DeepFilterNet3's encoder, ERB decoder, and complex deep-filter
decoder. The C11 frontend has a 960-point Vorbis STFT, 32 ERB bands, normalized
complex features, and two-frame lookahead. Its graphs total 8,587,242 bytes and
match those in the [author's ONNX archive](https://github.com/Rikorose/DeepFilterNet/tree/978576aa8400552a4ce9730838c635aa30db5e61/models).

Studio restoration uses VoiceFixer's analysis network and vocoder at 44.1 kHz.
C11 handles resampling, STFT/mel analysis, log inversion, and overlap. The FP32
graphs and mel filters total 415,446,261 bytes from this
[pinned conversion](https://huggingface.co/TigreGotico/audiosronnx-voicefixer/tree/aa259fa578b9d5f4c76fda3cd967f73542b0e15b).
The iOS implementation uses a different conversion, precision, and windowing.
Matching the network name does not establish matching output.

The app offers missing models when you add a filter. Downloads can be cancelled
and resumed and are verified before use. Playback prepares overlapping blocks
and caches the result. Filter order, ranges, gain, speed, and transition fades
remain part of processing. Restoration currently enhances the stereo mid signal
and retains the side signal; iOS processes distinct channels separately. Cuts
also affect our available context. Source-wide context, resumable processing
windows, and equivalent DSP behavior still need work.

For development installation:

```sh
python3 tools/prepare-studio.py --install
python3 tools/prepare-audio.py --install
```

VoiceFixer can be slow and can change vocal character. Compare the result on
speech before applying it to a long recording. The C11 DeepFilterNet3 and
VoiceFixer frontends matched independent Python calculations below 1e-7 maximum
sample error on short verification inputs. That is numerical evidence for
those inputs, not a listening comparison with iOS.

## Captions

Qwen3-ASR-0.6B downloads a 1.9 GB weight file plus tokenizer and configuration
files. Tools → Auto captions offers the download. Transcription then works
offline and produces editable text effects in six-second chunks. Word timing,
word-limit regrouping, and a full transcript workspace are still missing.
The iOS app uses a smaller 4-bit MLX pack and pause-based estimated word timing;
neither implementation uses forced alignment.

```sh
python3 tools/prepare-model.py
python3 tools/install-model.py
```

`ANDROID_SERIAL` selects the phone for the installers. Models live in MyRec's
external-files directory; local preparation copies live under `.build/models/`.
Processing uses the app cache. The old Android package has separate model
storage and is not migrated automatically.

## Licenses

RNNoise is BSD-3-Clause. The Qwen C runtime is MIT. Qwen weights, LavaSR, and
PPHumanSeg are Apache-2.0. DeepFilterNet offers MIT or Apache-2.0; its MIT notice
is bundled. ONNX Runtime is MIT with additional component notices.

VoiceFixer's architecture and conversion code are MIT. Haohe Liu's published
[model checkpoints](https://doi.org/10.5281/zenodo.5600188) are **CC BY 4.0**;
conversion does not replace their license. The bundled weights notice credits
the author and identifies the conversion and processing changes. See
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and the
[bundled texts](../app/src/main/assets/licenses).
