# Models and inference

Models run locally. Application code uses C APIs for external runtimes; their
source pins are in [runtimes.json](../runtimes.json). Downloaded model URLs,
sizes and checksums are in [data/models.json](../data/models.json).

## Voice isolation and person color

RNNoise is built with its network and works without a separate download.
PPHumanSeg is a bundled FP32 segmentation graph, about 5.9 MB, processed by
ONNX Runtime. Detection reads the source frame before color effects. Color
Isolation can preserve either a selected hue or the detected person.

Boundaries, multiple people, occlusion and temporal stability need broader
footage validation. The model does not identify a person or send frames away.

## Neural audio

Speech detail (previously Studio clarity) uses LavaSR v2 speech restoration and bandwidth extension. Its
four files total about 56 MB. The C11 waveform pipeline includes the mel
frontend, neural backbone and spectral head, inverse transform and crossover.

All 80 learned parameter tensors (13,985,282 parameters) in the pinned ONNX
graphs match the public [LavaSR v2 checkpoint](https://huggingface.co/YatharthS/LavaSR/tree/b98dc8be472da45ab7b6346ad7997e1dfeb5911d/enhancer_v2)
exactly, allowing the linear-weight transposes used by ONNX. The older `v1`
cache names identify our first integration; they do not identify upstream
LavaSR v1. Cache names remain unchanged to preserve installed downloads.

Playback prepares overlapping blocks and caches audio. Clip speed, transition
fades, effect ranges, strength and filter order remain part of processing.

Noise reduction runs the official DeepFilterNet3 encoder, ERB decoder and
complex deep-filter decoder. Its C11 frontend uses a 960-point Vorbis STFT,
32 ERB bands, normalized complex features and the model's two-frame lookahead.
The pinned graphs total 8,587,242 bytes. They match the graphs in the
[author's ONNX archive](https://github.com/Rikorose/DeepFilterNet/tree/978576aa8400552a4ce9730838c635aa30db5e61/models).

Studio restoration runs VoiceFixer's ResUNet analysis and TFGAN vocoder at
44.1 kHz. C11 provides resampling, STFT/mel analysis, log inversion and overlap
handling. The FP32 ONNX files and mel filters total 415,446,261 bytes, from the
[pinned conversion](https://huggingface.co/TigreGotico/audiosronnx-voicefixer/tree/aa259fa578b9d5f4c76fda3cd967f73542b0e15b).
The iOS screenshot estimates 191 MB for VoiceFixer and 4 MB for DeepFilterNet3;
these Android FP32 downloads are larger. The iOS precision and packaging are
unknown. Model identity does not establish identical iOS preprocessing or output.

All three use the existing ONNX Runtime C API. Adding a missing model offers
its download, with verification, cancellation and resume. Downloads run only
when requested. VoiceFixer is substantially slower than noise reduction;
preparation is cached before playback/export. Existing projects retain their
RNNoise isolation and Studio clarity filters.

For development installation:

```sh
python3 tools/prepare-studio.py --install
python3 tools/prepare-audio.py --install
```

The C11 pipelines were compared against independent Python frontends using
the same pinned graphs. Maximum sample error was below 1e-7 for both
DeepFilterNet3 and VoiceFixer on the short verification inputs. Android checks
also load and run both networks. These checks establish numerical agreement
for those inputs, rather than a general quality comparison with iOS.

## Captions

Qwen3-ASR-0.6B uses a separate 1.9 GB weight file plus tokenizer/configuration
files. Tools → Auto captions offers the download. Afterward, transcription
works offline and produces editable text effects with chunk-level timing.
Word-level forced alignment is not implemented.

For development installation:

```sh
python3 tools/prepare-model.py
python3 tools/install-model.py
```

`ANDROID_SERIAL` selects the phone for either installer. Models are stored in
the app's external-files directory; preparation tools keep local copies under
`.build/models/`. Processing caches live in the app cache directory.

## Licenses

RNNoise uses BSD-3-Clause; the Qwen C runtime uses MIT; Qwen model weights,
LavaSR and PPHumanSeg use Apache-2.0; VoiceFixer and its conversion use MIT;
DeepFilterNet is available under MIT or Apache-2.0 (MIT notice bundled). ONNX Runtime uses MIT with additional
third-party notices. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and
[the bundled license texts](../app/src/main/assets/licenses).
