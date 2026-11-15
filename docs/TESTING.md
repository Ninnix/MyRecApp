# Testing MyRec

Test the behavior a change can affect. A camera change needs a real camera;
a layout change does not need a fresh model download. Run commands from the
repository root.

## Build first

```sh
./scripts/build.sh :app:testEditor :app:assembleDebug :app:assembleDebugAndroidTest
```

The pure editor suite checks source clocks, splits, copies, independent effect
state, transitions, restored editing sessions, recording-safe undo, track
movement, microphone identities, and camera capability selection. It needs no
phone. Storage and ZIP tests use isolated cache projects and need a device for
Android filesystem and JSON APIs; they do not need a camera or foreground UI.

## Choose a device suite

```sh
python3 scripts/test-device.py --serial SERIAL
python3 scripts/test-device.py --serial SERIAL --parity
```

The runner installs the app and test APK without clearing app data, grants
camera/microphone access, and stores evidence in `artifacts/`. Test project
metadata uses isolated cache folders. Recording and editor preferences use
separate files for each test session, including the microphone selection used
by voiceover. After the run, the app opens normal project storage and settings.
Recording checks create short fixture recordings.
Keep the test phone unlocked and MyRec in the foreground.

Use `--serial` or `ANDROID_SERIAL` to select a particular connected phone. The runner can
start its isolated activity through ADB when a device delays the instrumentation
launch. It first checks that the display is awake and the keyguard is unlocked.
It does not send wake/unlock commands or change the screen timeout.

The storage UI check deliberately makes its isolated `current` file unreadable
to exercise save Retry. The phone labels this step “Storage test · expected save
failure”. A warning in the MyRec log is expected during that step. The test
restores access, retries the save, and verifies the latest edit was retained.

| Option | Checks |
| --- | --- |
| `--responsive-layout` | Actual screen pixels for Projects headings with cards and after scrolling, portrait/landscape/portrait navigation, inset-aware editor controls, five compact/tall screen sizes, unchanged project and recorder visibility |
| `--hdr-graphics` | Missing HDR graphics requirements despite camera/codec support, exact extension names, actual ten-bit EGL probe, and preservation of an existing context/framebuffer |
| `--stabilization` | Actual 60 fps/VFR presentation times, held frames at trims/seeks, rendered shake reduction, bounded crops and rotation, reference reuse through split/trim/speed/freeze/copy/history/ZIP, checked cache reuse/corruption/source replacement, Off and legacy stability, cancellation, decoded export with unchanged audio/duration/source, portrait/landscape inspector comparison/apply/cancel/remove |
| `--hdr-full` | Requires actual native ten-bit HDR decoding and Main10 HLG output; exports a known ramp and checks code levels, headroom and linear-light error |
| `--hdr-pipeline` | Half-float headroom and grading, linear composition, synthetic P010 HLG code steps, consistent display conversion, inactive-HDR isolation, actual decoder precision gate, tagged Main10 export or explicit capability rejection, export controls |
| `--time-effects` | Actual VFR/B-frame index, backward/repeated seeks, linear Step Printing smear, source phase and animated-speed persistence/undo, source clocks and constant-rate audio pieces, stereo pitch, pause detection/padding across speed keys and exact export length, negative inversion |
| `--restoration` | Stereo/mono rational resampling, anti-aliasing, cancellation and file safety, checkpoint validation and actual 256-frame VoiceFixer inference; restoration model required |
| `--scene` | Optional eight-second landscape excerpt: import/frame index, random-seek time effects, half-speed picture/sound export; install the source at external-files `test-inputs/sicily-8s.mp4` first |
| `--audio-editing` | Reference Low-cut/EQ frequency response, neutral/headroom gain, stereo state across buffer sizes, mixer seeks, new-stage persistence/reset/undo and legacy stability; pitch/length/stereo and deterministic stretch/seek checks, cancellation, fade/censor persistence and archives, preview/export timing, MP4 AAC priming, tool apply/remove/undo in both orientations |
| `--movie-exports` | Completed picture/sound reuse across reopening, canonical edit/source/settings identity, same-size/timestamp source/output changes, malformed/future/missing receipts, cancellation/failure/source changes, verified replacement retirement, and real gallery publication/reuse on API 29+ |
| `--covers` | Scoped chronological choices, refresh, immutable full-resolution composed JPEGs through rotation, read-only provider metadata/bytes and traversal rejection, cancelled generation and unchanged edit history |
| `--originals` | Original access with unreadable/newer metadata, bounded path scanning, external symlinks/cycles, verified bulk copies and real Android Files folder permission/write/read/cleanup, duplicate names, failure/cancel cleanup, independent recovered projects and sidecars, failed-index recovery, review Start/End through rotation and append/undo, both orientations and picker cancellation |
| `--preview` | Continuous pinch, pan/reset, inspection without changing edits, Crop Inspect, immediate pause state, cancelled startup, actual draft EOF and replay |
| `--effect-contracts` | Linear RGB sampling, source-time parameters, fixed rendering order, old-project conversion, shared/per-clip drafts, source keys and A/B motion/history/ZIP transfer, channel pads and touch/accessibility, primary grading, film-color/Halation/Noir goldens and grain presentation clock, inspector controls in both orientations, Kaleidoscope wedge math/guide gestures; CUBE/Log, Dolly separation, RNNoise source stereo/tails, pause/subtitle/hold persistence and portrait/landscape workspaces |
| `--storage` | Persistent history/redo, typed capture recipes in originals/ledger/receipts/ZIPs, immutable revisions, bounded decoded history cache/private copies and changed/missing disk checks, read timing for 65 states × 15 clips, legacy upgrade, recovery indexes, future-schema refusal, save failure, independent copies, deletion safety, checksum relinking, staging cleanup, damaged-history repair and Record here/voiceover receipt recovery |
| `--archives` | ZIP checksums, source/history/redo assets, sidecars, missing/damaged media and checked relinking, unsafe paths, future versions, cancellation, no overwrite, OTIO timing |
| `--tool-workspaces` | Start/Split/Tools/Export order, Text duration/tabs/apply/cancel, bundled font glyphs, title motion/outline/background export, LUT previews/comparison/favorites and saved/source-clock grade animation in both orientations |
| `--showcase` | Reproduce the Colosseum landscape timeline and portrait Text/LUT screenshots from the documented clip in an isolated project store |
| `--storage-ui` | Project switching, portrait/landscape originals, actual Retry recovery, undoable unavailable-take removal, real paused capture, Record here insertion and undo protection |
| No option | Native image/audio calls, runtimes, persistence, decode/export, stabilization, composition, camera switching, pause/resume, AF, microphone labels, portrait recording |
| `--parity` | Portrait/landscape tools, drafts, cancellation, rotation, placement, volume, sliders, timeline, Settings, Animate controls |
| `--exports` | Transition pixels, person-isolation ranges, 720p/1080p/4K and longer multitrack export |
| `--speech` | RNNoise, offline Qwen transcription, automatic language detection, completed-section reuse with stable word identities, cancellation and invalid-input rejection; caption model required |
| `--studio` | Neural filters, order, ranges, mute, cache, cancellation, live changes, audio export; models required |
| `--gaps` | DeepFilterNet3/VoiceFixer inference, all neural ranges, multi-model order, camera inventory, Kaleidoscope/Widescreen pixels |
| `--orientation` | Asymmetric markers, four metadata rotations, different input sizes, real camera switches, frame orientation, AV timing |
| `--timeline` | Thumbnails scroll together across split boundaries; clip data stays unchanged |
| `--composition` | Encoded pixels for five dual layouts, round masks, swaps, normal framing, four rotations |
| `--camera-gaps` | Real concurrent capture, layout changes, pause/resume, timing, Main10 HLG metadata, HDR decode/SDR export, live effect ranges |
| `--live-rotation` | Display rotation during one SDR, HDR, or dual take; UI reflow and saved-frame comparison |
| `--capture-controls` | Simplified toolbar, Camera settings and color/setup locks, lower-strip Layout, Mark/Pause positions, saved marks, torch, front-light width/color/opacity/custom picker, rounded border/resize, brightness restoration and saved-edge pixels, PiP movement/resize/swaps |
| `--media-editing` | Portrait/landscape copy/paste, horizontal overlaps and lower-card selection, release-position snapping, free scrubbing until release, snap targets and Off, frame/cut-aligned marks, minimum selection length and Move end, shared Snap to cuts/Tools preference across fresh activities, neighbor modes, long-press selection, stable Move rows, exact boundary picking, Cancel/Done, Placement, audio import and ordered mixed photo/video import/export, persistence, foreground pixels and decoded sound, real voiceover passes/audition/discard and rotation |
| `--playback` | Moving frames in a fifth take after seeking earlier takes |
| `--camera-preview` | All independent selfie preview/save mirror combinations in portrait/landscape, saved-switch lock during a take and Kaleidoscope framing |
| `--capture-compatibility` | Unsupported HDR repeated requests and disabled UI touch on each camera, a request during SDR recording, and a decoded playable result |
| `--capture-endurance` | Eleven-minute SDR take without user input, awake/unlocked checks, pause/resume and marker, bounded memory growth, full track timestamp continuity/AV drift, endpoint decoding and source checksum; removes its own isolated recording after success |
| `--capture-focus` | Reference peaking pixels, per-camera zoom/manual focus recall, actual lens metadata, gestures/hold and preview-only focus aids |
| `--references` | Project delete/restore, shared-media safety, project controls, mirror geometry, RGB offsets |
| `--new-ui` | Live effects sheet, Clear all, Widescreen, 4:5 crop, quarter turns |
| `--hdr-color` | Full/limited P010 patches through GL, C11, decoder, and Main10 encoder; primaries detection and sidecars |
| `--hdr-scene` | SDR/HDR scene comparison and a direct HDR recording bypassing the compositor |

Use `--serial SERIAL` to select an attached phone. `--apk-directory DIRECTORY`
selects a pair named `app-debug.apk` and `app-debug-androidTest.apk` signed with
the installed app's certificate. This allows testing a phone with an existing
release installation without uninstalling its projects. The two APKs must share
a certificate. The responsive-layout suite checks hardware screenshots before
any software view draw, so a valid view hierarchy alone cannot pass the heading
visibility check. Tests use isolated projects and recording preferences.

Use `--current-project` only with `--playback`. It opens a read-only copy of the
active project in the isolated store. Other suites use fixtures.

The renamed app and test package are `org.myrec.app` and `org.myrec.app.test`.
Old `org.yourec.app` data is separate. Tests needing model downloads cannot
reuse that package's private model storage automatically.

For orientation and preview checks, leave the phone still in a lit scene with
asymmetric detail visible to both cameras. For HDR scene checks, keep the rear
lens clear. Encoded dimensions alone do not prove that the picture is upright.

## Check HDR independently

```sh
ANDROID_SERIAL=SERIAL python3 scripts/check-hdr.py --device
```

This uses FFmpeg to inspect encoded patch values. Generate the fixture with
`scripts/hdr-fixture.py`. Optional `hdr-color-reference.png` and
`hdr-color-input.png` in the app's external-files directory exercise a known
camera interpretation; personal images are not committed fixtures.

## What a pass means

A passing suite verifies its Android checks. The `--parity` name denotes the UI
suite; it is not a pixel-equality test against iOS. A short clip is not a thermal
or quality benchmark. Physical accessories, calls/alarms, difficult masks, and
long recordings still need broader testing.

Local verification reports live in the ignored `artifacts/` directory. Match
their dates and APK hashes to the build being assessed; historical runs do not
certify a newer build.
Test-media provenance is beside the [fixtures](../app/src/androidTest/assets/README.md).
APKs, raw captures, logs, and the iOS checkout stay out of Git.

The audio speed stage uses a stereo waveform-similarity overlap-add implementation
based on the [WSOLA method](https://www.isca-archive.org/eurospeech_1993/roelands93_eurospeech.html).
It uses no additional library. Device frequency and duration tests establish the
properties listed above; they do not establish perceptual equivalence to Apple's
time-pitch engine. AAC export measures its encoder's priming with a private prefix
and trims it through an MP4 edit; cross-check generated movies with an independent
decoder as well as Android's decoder.

The last source-decoder frame is retained. MP4 imports and thumbnail timing use
the movie presentation clock: Android 10's generic metadata duration can include
AAC preroll, even when its decoder correctly applies the MP4 edit list.

The effect-contract suite also checks independent RNNoise channels and final
partial-frame flushing, native CUBE interpolation and linear-light
strength, technical adjustments at zero strength, Apple Log viewing ramps,
Halation extraction/blur/flash goldens in SDR and half float, exact bypass and
algorithm identity, source-key persistence and all film-color inspector controls,
deterministic Dolly foreground protection, saved pause playback, source-owned
subtitle words, hold metadata and project asset/history/archive ownership. The
host editor suite tests speech-island bounds, word timing outside silence,
correction/regrouping, pause source clocks and linked tracks, reflected independent
stereo windows, checkpoint reuse and structural hold split/copy/undo.

`--studio` requires the existing verified LavaSR pack on the phone. It checks
real speech inference, filter order, scoped bypass, mute, cancellation, cache
reuse, preview restart and exported duration. Model loading verifies the pinned
SHA-256 values before inference. The window/cache tests use a supplied processor
as well, to isolate channel and boundary behavior from model quality. No full
Qwen recognition or imported Cinematic depth acceptance is claimed by these tests.

A successful capability-rejection test does not prove HDR encoding.
`--hdr-full` requires actual ten-bit decoding and Main10 HLG output. Check
supported sizes, distinct ramp codes, headroom, profile and color tags with an
independent decoder as well as Android. Keep numerical results and device/build
fingerprints in the local verification report.

Codec selection checks source/output sizes and tries compatible encoders.
Android 17 reports the foreground activity as `topResumedActivity`; the runner
recognizes that form so its ADB fallback cannot restart a running test session.
AAC checks include explicit decoder preroll handling and MP4 presented-duration
bounds. Older extractors express AAC edits as decoder delay/padding, while newer
ones can shift packet timestamps. The decoder retains the first path when
timestamps are nonnegative and avoids applying both paths to negative preroll.
The encoder's timing probe deliberately decodes the raw prefix. Check encoded
pitch, fade and censor timing independently with FFmpeg as well. The relevant platform behavior is in
[AOSP's MP4 extractor](https://android.googlesource.com/platform/frameworks/av/+/0da67bb0e7facf146be789e4c67afa4fc5788995/media/extractors/mp4/MPEG4Extractor.cpp)
and [ACodec's skip/cut handling](https://android.googlesource.com/platform/frameworks/av/+/9c318e64f58b11b1eda587a5d741d36d2511d42f/media/libstagefright/ACodec.cpp).

Imported depth needs an original Cinematic movie with its disparity track.
YouTube excerpts contain ordinary video/audio and cannot validate depth decoding.
The reference's real-depth integration tests require `YOUREC_CINEMATIC_FIXTURE`;
they skip without it. Synthetic maps test rendering separately from decoding.
The landscape excerpt used locally came from the user-supplied YouTube link;
it is kept under `.build/` and is not a redistributed test asset.

## Quantized captions

```sh
python3 scripts/test-gguf.py
cmake -S scripts/qwen -B .build/qwen-host -DCMAKE_BUILD_TYPE=Release
cmake --build .build/qwen-host --parallel 2
cc -std=c11 scripts/test-qwen-kernels.c -o .build/test-qwen-kernels -ldl -lm
.build/test-qwen-kernels .build/qwen-host/libqwen_asr.so
```

The GGUF tests exercise F32/F16/Q8_0/Q4_K decoding, packed six-bit scales and
minima, multiple blocks, truncated headers/data, invalid shapes, wrong model
architecture and unsupported types. The kernel probe compares parallel matrix
and convolution outputs against the runtime's scalar implementations, including
bias, odd dimensions and stride. Compile and run the same probe with the NDK/ADB
to exercise ARM64 NEON; maximum observed difference was below 0.000001.

A four-thread ARM64 comparison used the previous BF16 caption library and the
C11 Q4_K backend on the same audio, sequentially, without model-loading time:

| Audio | Duration | BF16 | Q4_K |
| --- | ---: | ---: | ---: |
| Existing speech fixture | 3.64 s | 13.29 s | 6.13 s |
| Existing JFK fixture | 11.00 s | 37.46 s | 11.93 s |
| Existing film dialogue | 6.86 s | 23.47 s | 7.09 s |
| FLEURS Italian test sample | 11.52 s | 50.55 s | 14.87 s |

Peak process RSS was approximately 2.5 GiB with BF16 and 1.3 GiB with Q4_K.
The change includes parallel float/NEON kernels as well as packed weights.
Italian, JFK and film-dialogue words matched BF16 after ignoring punctuation.
Both backends misrecognized the invented system name in the speech fixture,
with different spellings. A deterministic 10 dB white-noise version of the speech fixture also kept the
spoken sentence, with different proper-name errors on both backends (6.78 s Q4_K
versus 17.49 s BF16). These few samples do not establish corpus-wide WER,
speed on every phone, or equivalence to the iOS MLX backend.
The Italian sample is `test/10000151032537989529.wav` from
[google/fleurs, it_it](https://huggingface.co/datasets/google/fleurs/tree/main/data/it_it),
used locally for validation and not bundled in the application.

Run `scripts/prepare-model.py`, `scripts/install-model.py` and the device
`--speech` suite to check the complete mixer/resampler/JNI/checkpoint path.
The installed caption runtime requires only libc, libdl and libm, and keeps
16 KB ELF alignment. The model download is 635,475,022 bytes including the
tokenizer. Previous BF16 files are retained rather than deleted during migration.
