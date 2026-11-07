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

## Studio clarity

Studio clarity uses LavaSR V1 speech restoration and bandwidth extension. Its
four files total about 56 MB. The C11 waveform pipeline includes the mel
frontend, neural backbone and spectral head, inverse transform and crossover.

Playback prepares overlapping blocks and caches audio. Clip speed, transition
fades, effect ranges, strength and filter order remain part of processing. The
model is independent of the original iOS app's microphone model; it is not a
verified response match for a particular studio microphone.

Adding Studio clarity offers the download. For development installation:

```sh
python3 tools/prepare-studio.py --install
```

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
LavaSR and PPHumanSeg use Apache-2.0. ONNX Runtime uses MIT with additional
third-party notices. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and
[the bundled license texts](../app/src/main/assets/licenses).
