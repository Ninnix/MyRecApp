# Models and inference

Models run on the phone. The application calls external runtimes through C APIs;
[runtimes.json](../runtimes.json) pins them. Download URLs, sizes, and hashes are
in [data/models.json](../data/models.json). The Android backend is ONNX Runtime
on the CPU, with the Qwen C runtime for captions.

## Included models

RNNoise is built with its network and needs no download. Existing voice-isolation
filters use it. PPHumanSeg is a bundled FP32 graph of about 5.9 MB. Color Isolation
uses its mask to keep people in color; detection reads the source before color
effects. It can also keep a chosen hue. Auto Background and Chroma Key’s optional
person hint reuse this model, without another download. Their C11 path refines
edges against the source, then uses finite source-bound matte history, motion
gating for keying and scene-cut resets.

Hair, occlusion, movement, and multiple people need more testing. The model
neither identifies people nor sends frames away. It does not reproduce Apple's
Vision segmentation. Recorded live matte journals are not yet replayed; saved
footage is analysed again from its source.

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
complex features, the reference's 0.99 normalization coefficient, and two-frame
lookahead. Its graphs total 8,587,242 bytes and
match those in the [author's ONNX archive](https://github.com/Rikorose/DeepFilterNet/tree/978576aa8400552a4ce9730838c635aa30db5e61/models).
On a 96,000-sample fixture, the C path and an independent libdf/ONNX calculation
measured 1.11e-7 maximum sample error and 1.59e-8 RMS error. This checks the
bounded-window implementation; it does not establish streaming or Core ML parity.

Studio restoration uses VoiceFixer's analysis network and vocoder at 44.1 kHz.
C11 handles band-limited rate conversion, STFT/mel analysis, log inversion, and
overlap. The FP32 graphs and mel filters total 415,446,261 bytes from this
[pinned conversion](https://huggingface.co/TigreGotico/audiosronnx-voicefixer/tree/aa259fa578b9d5f4c76fda3cd967f73542b0e15b).
The installer verifies the original graphs, changes only each fixed input
shape from 501 to 256 frames and checks the derived SHA-256 before publication.
Learned tensor payloads are unchanged. The frontend uses the reference's mel
bank, periodic Hann window and 112,455-sample context. Output is center-trimmed
from 114,660 samples, with 28,224-sample margins, 52,920-sample stride and
3,087-sample overlap. Models live in `voicefixer-256`; an old download can be
adapted locally by the preparation tool after verification.

Each neural stage processes an original source before trims, timeline speed,
gain, fades and censoring. Distinct stereo channels use independent predictions;
exact dual mono can reuse a prediction. Reflected windows, atomic output and
SHA-checked checkpoints allow completed work to be reused. RNNoise compensates
initial latency and flushes partial final hops. DeepFilter currently uses
independent bounded windows; it still needs the reference's continuous per-hop
recurrent state. Core ML and ONNX conversion/precision differences also remain.

The app offers missing models when you add a filter. Downloads can be cancelled
and resumed and are verified before first use. VoiceFixer's original and adapted
hashes and shape offsets are recorded in the model manifests.

For development installation:

```sh
python3 scripts/prepare-studio.py --install
python3 scripts/prepare-audio.py --install
```

VoiceFixer can be slow and can change vocal character. Compare the result on
speech before applying it to a long recording. On a synthetic 112,455-sample
context, the current C path versus an independent NumPy-FFT/ONNX calculation
measured 0.999934 correlation, 0.000041 RMS error and 0.00161 maximum sample
error. This checks frontend, window and center-trim behavior for that input;
it is not an iOS Core ML waveform or listening comparison.

## Captions

Qwen3-ASR-0.6B uses a pinned [Q4_K GGUF pack](https://huggingface.co/cstr/qwen3-asr-0.6b-GGUF):
631 MB of weights, with a Q8_0 audio encoder and Q4_K decoder. The original
Qwen tokenizer adds 4.4 MB, for a 636 MB download. The underlying model remains
Qwen3-ASR-0.6B. Tools → Auto captions offers the download; recognition then works
offline. An existing BF16 download is retained when upgrading and can be removed
from the model directory after the new pack is installed.

The Android backend uses C11 GGUF loading and quantized decoding with the external
Qwen C runtime's audio encoder, tokenizer, normalization and attention kernels.
Float matrix and convolution work shares its persistent workers and uses NEON
on ARM64. Packed decoder rows stay mapped during generation; the current prefill
matrix and audio encoder weights are expanded to float. Model size is not peak RAM.
No C++ runtime or new inference dependency is required.

A band-limited mono conversion feeds 20 ms speech-island planning and recognition
sections of at most twelve seconds. Estimated word anchors support transcript
correction, regrouping and copy. Completed sections are checkpointed by edited-audio
hash, language, range, speech plan and runtime/model identity, separately from BF16
results. Up to eight jobs are retained in the app cache. An active C inference call
must finish before cancellation returns. All three files have pinned sizes and
SHA-256 checks. Failed recognition does not mark a section complete.

The iOS app uses a 4-bit MLX pack. Quantization and numerical backends differ;
identical transcripts are not guaranteed. Neither implementation uses forced
alignment. Small English/Italian comparisons and ARM64 kernel checks are described
in [testing](TESTING.md); these are not a corpus-wide quality or speed guarantee.

```sh
python3 scripts/prepare-model.py
python3 scripts/install-model.py
```

`ANDROID_SERIAL` selects the phone for the installers. Models live in MyRec's
external-files directory; local preparation copies live under `.build/models/`.
Processing uses the app cache. The old Android package has separate model
storage and is not migrated automatically.

## Licenses

RNNoise is BSD-3-Clause. The Qwen C runtime and GGUF quantization reference are MIT. Qwen weights, LavaSR, and
PPHumanSeg are Apache-2.0. DeepFilterNet offers MIT or Apache-2.0; its MIT notice
is bundled. ONNX Runtime is MIT with additional component notices.

VoiceFixer's architecture and conversion code are MIT. Haohe Liu's published
[model checkpoints](https://doi.org/10.5281/zenodo.5600188) are **CC BY 4.0**;
conversion does not replace their license. The bundled weights notice credits
the author and identifies the conversion and processing changes. See
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and the
[bundled texts](../app/src/main/assets/licenses).
