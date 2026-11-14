<p align="center">
  <img src="docs/branding/myrec-icon.png" alt="MyRec hippopotamus" width="112">
</p>

# MyRec

MyRec is a video recorder and editor for Android. Record a take, pause, change
cameras, cut the parts you do not want, fix the sound, and export. The recordings
and the work stay on your phone.

The project follows [YouRec for iPhone](https://github.com/antirez/YouRecApp),
by Salvatore Sanfilippo (antirez). This is an independent Android implementation.
There are still substantial differences in features and processing; this is
not an official YouRec release or a claim of identical output.

## Recording and editing

You can switch cameras during a take, use a timed zoom, lock focus, and choose a
microphone. Phones with the required camera support can record front and rear
views together or capture 10-bit HLG video.

The editor has separate video and audio tracks. Split, trim, copy, paste, move,
and arrange clips; add text, images, transitions, color effects, and stabilization.
Audio filters run in order. Captions and neural audio restoration work offline
after their models have been downloaded.

Edits leave the source files alone. Undo and redo work during the editing
session; their history does not yet survive reopening a project. HDR recordings
keep their original files, but editing and export currently produce SDR.
Export writes H.264 video and AAC audio to `Movies/MyRec`.

Read the [recording guide](docs/RECORDING.md) and
[editing guide](docs/EDITING.md) for the controls and their limits.

## Try it

You need Android 9 or later and an ARM64 device. Build the APK, install it, and
allow camera and microphone access when you start recording. Open Projects to
start a project, import video, or continue an existing recording.

The app is now named **MyRec**, with package `org.myrec.app`. It installs beside
the earlier `org.yourec.app` build. Android keeps their private projects, settings,
and downloaded models separate. There is no automatic migration yet; keep the
old app installed if it holds recordings or edits you need.

This is development software. Physical checks have covered a OnePlus 15;
other cameras, microphones, and phones need testing. A passing Android check
does not establish the same behavior or output as the iOS app.

## Build

Use JDK 17+, Android SDK 37, NDK 28.2.13676358, CMake 3.31.6, and Python 3.12.

```sh
python3 tools/prepare-runtimes.py
python3 tools/prepare-person.py
./tools/build.sh :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.
See [Building](docs/BUILD.md) for SDK paths and
[Releasing](docs/RELEASING.md) for signing.

The interface uses Kotlin and Android Canvas. Camera2 and Android media APIs
handle capture and export. C11 handles image processing, stabilization, and
calls to the external inference runtimes through their C APIs.

## Models

Ordinary recording and editing need no model download. The app offers downloads
when you choose captions or a neural audio filter.

| Model | Use | Download |
| --- | --- | --- |
| RNNoise | Legacy voice isolation | Included |
| PPHumanSeg | Keep people in color | Included, 5.9 MB |
| DeepFilterNet3 | Noise reduction | 8.6 MB |
| VoiceFixer | Studio restoration | 415 MB |
| LavaSR v2 | Speech detail | 56 MB |
| Qwen3-ASR-0.6B | Captions | 1.9 GB |

Inference runs on the CPU. [Models](docs/MODELS.md) explains storage,
processing, and licenses. Downloads are pinned in [data/models.json](data/models.json).

## Privacy

The app has no advertising or analytics SDK. Recording, editing, audio
processing, and transcription run locally. Model downloads contact GitHub,
Hugging Face, and their delivery providers; they do not send recordings or
transcripts. Import and sharing use Android's picker and the app you choose.
Read the [privacy notes](docs/PRIVACY.md).

## Acknowledgements

Thanks to **Salvatore Sanfilippo (antirez)** for
[YouRec](https://github.com/antirez/YouRecApp): the original camera and video
editor for iPhone, the design that started this project, and now the published
source that makes a careful Android port possible. MyRec is independently
maintained and is not an official YouRec release.

Thanks also to the authors of [RNNoise](https://github.com/xiph/rnnoise),
[Qwen ASR](https://github.com/antirez/qwen-asr),
[ONNX Runtime](https://github.com/microsoft/onnxruntime),
[DeepFilterNet](https://github.com/Rikorose/DeepFilterNet),
[LavaSR](https://github.com/ysharma3501/LavaSR),
[VoiceFixer](https://github.com/haoheliu/voicefixer), and
[PPHumanSeg](https://github.com/opencv/opencv_zoo/tree/main/models/human_segmentation_pphumanseg).
Runtime revisions are recorded in [runtimes.json](runtimes.json).

## Licenses and artwork

MyRec's application source code and documentation are released under the
[MIT License](LICENCE). It retains the copyright notices for Ninnix and the
MyRec contributors, and for Salvatore Sanfilippo's upstream work. The same
license is [bundled in the APK](app/src/main/assets/licenses/MyRec.txt).

YouRec's original code and documentation use the
[MIT License](https://github.com/antirez/YouRecApp/blob/faf1815e82e1f2d5f03034a583363d3c3af464a2/LICENSE).
That grant reserves the **YouRec name and icon** as branding. MyRec uses its own
name and newly generated hippopotamus artwork: a left-facing camera-like
silhouette, circular lens, and red recording eye. The MyRec name and icon are
also excluded from the source-code license. The complete upstream notice is
retained [in the app assets](app/src/main/assets/licenses/YouRec.txt).

Models and libraries keep their own licenses. VoiceFixer's code is MIT; its
published model checkpoints are CC BY 4.0. See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the
[bundled license texts](app/src/main/assets/licenses).

## Development

Read [CONTRIBUTING.md](CONTRIBUTING.md) and [Testing](docs/TESTING.md).
An optional local iOS checkout can live in `reference/YouRecApp/`; it is
excluded from Git. See [Building](docs/BUILD.md) for the reference commands.
