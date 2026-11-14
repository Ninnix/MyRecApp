# Testing MyRec

Test the behavior a change can affect. A camera change needs a real camera;
a layout change does not need a fresh model download. Run commands from the
repository root.

## Build first

```sh
./tools/build.sh :app:testEditor :app:assembleDebug :app:assembleDebugAndroidTest
```

The pure editor suite checks source clocks, splits, copies, independent effect
state, transitions, restored editing sessions, recording-safe undo, track
movement, microphone identities, and camera capability selection. It needs no
phone. Storage and ZIP tests use isolated cache projects and need a device for
Android filesystem and JSON APIs; they do not need a camera or foreground UI.

## Choose a device suite

```sh
ANDROID_SERIAL=SERIAL python3 tools/test-device.py
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --parity
```

The runner installs the app and test APK without clearing app data, grants
camera/microphone access, and stores evidence in `artifacts/`. Test project
metadata uses isolated cache folders. After the run, the app opens normal
project storage. Recording checks create short fixture recordings.
Keep the test phone unlocked and MyRec in the foreground.

Set `ANDROID_SERIAL` to select a particular connected phone. The runner can
start its isolated activity through ADB when a device delays the instrumentation
launch. It first checks that the display is awake and the keyguard is unlocked.
It does not send wake/unlock commands or change the screen timeout.

The storage UI check deliberately makes its isolated `current` file unreadable
to exercise save Retry. The phone labels this step “Storage test · expected save
failure”. A warning in the MyRec log is expected during that step. The test
restores access, retries the save, and verifies the latest edit was retained.

| Option | Checks |
| --- | --- |
| `--hdr-full` | Requires actual native ten-bit HDR decoding and Main10 HLG output; exports a known ramp and checks code levels, headroom and linear-light error |
| `--hdr-pipeline` | Half-float headroom and grading, linear composition, synthetic P010 HLG code steps, consistent display conversion, inactive-HDR isolation, actual decoder precision gate, tagged Main10 export or explicit capability rejection, export controls |
| `--time-effects` | Actual VFR/B-frame index, backward/repeated seeks, linear Step Printing smear, source phase and animated-speed persistence/undo, source clocks and constant-rate audio pieces, stereo pitch, pause detection/padding across speed keys and exact export length, negative inversion |
| `--restoration` | Stereo/mono rational resampling, anti-aliasing, cancellation and file safety, checkpoint validation and actual 256-frame VoiceFixer inference; restoration model required |
| `--scene` | Optional eight-second landscape excerpt: import/frame index, random-seek time effects, half-speed picture/sound export; install the source at external-files `test-inputs/sicily-8s.mp4` first |
| `--audio-editing` | Pitch/length/stereo and deterministic stretch/seek checks, cancellation, fade/censor persistence and archives, preview/export timing, MP4 AAC priming, tool apply/remove/undo in both orientations |
| `--preview` | Continuous pinch, pan/reset, inspection without changing edits, Crop Inspect, immediate pause state, cancelled startup, actual draft EOF and replay |
| `--effect-contracts` | Linear RGB sampling, source-time parameters, fixed rendering order, old-project conversion, shared/per-clip drafts, source keys and A/B motion/history/ZIP transfer, channel pads and touch/accessibility, primary grading and five film-color goldens, inspector controls in both orientations, Kaleidoscope wedge math/guide gestures; CUBE/Log, Dolly separation, RNNoise source stereo/tails, pause/subtitle/hold persistence and portrait/landscape workspaces |
| `--storage` | Persistent history/redo, capture ledger, immutable revisions, legacy upgrade, recovery indexes, future-schema refusal, save failure, independent copies, take receipts, deletion safety, checksum relinking/concurrent saves, staging cleanup, live damaged-history repair, strict schema versions |
| `--archives` | ZIP checksums, source/history/redo assets, sidecars, missing/damaged media and checked relinking, unsafe paths, future versions, cancellation, no overwrite, OTIO timing |
| `--storage-ui` | Project switching, portrait/landscape originals, actual Retry recovery, undoable unavailable-take removal, real paused capture and undo protection |
| No option | Native image/audio calls, runtimes, persistence, decode/export, stabilization, composition, camera switching, pause/resume, AF, microphone labels, portrait recording |
| `--parity` | Portrait/landscape tools, drafts, cancellation, rotation, placement, volume, sliders, timeline, Settings, Animate controls |
| `--exports` | Transition pixels, person-isolation ranges, 720p/1080p/4K and longer multitrack export |
| `--speech` | RNNoise, actual offline Qwen transcription and completed-section reuse with stable word identities; caption model required |
| `--studio` | Neural filters, order, ranges, mute, cache, cancellation, live changes, audio export; models required |
| `--gaps` | DeepFilterNet3/VoiceFixer inference, all neural ranges, multi-model order, camera inventory, Kaleidoscope/Widescreen pixels |
| `--orientation` | Asymmetric markers, four metadata rotations, different input sizes, real camera switches, frame orientation, AV timing |
| `--timeline` | Thumbnails scroll together across split boundaries; clip data stays unchanged |
| `--composition` | Encoded pixels for five dual layouts, round masks, swaps, normal framing, four rotations |
| `--camera-gaps` | Real concurrent capture, layout changes, pause/resume, timing, Main10 HLG metadata, HDR decode/SDR export, live effect ranges |
| `--live-rotation` | Display rotation during one SDR, HDR, or dual take; UI reflow and saved-frame comparison |
| `--capture-controls` | Mark/Pause positions, saved marks, torch, border transparency/resize, brightness restoration, PiP movement/resize/swaps |
| `--media-editing` | Portrait/landscape copy/paste, horizontal overlaps and lower-card selection, release-position snapping, free scrubbing until release, snap targets and Off, frame/cut-aligned marks, minimum selection length and Move end, shared Snap to cuts/Tools preference across fresh activities, neighbor modes, long-press selection, stable Move rows, exact boundary picking, Cancel/Done, Placement, audio import, persistence, foreground pixels in preview/export and decoded sound |
| `--playback` | Moving frames in a fifth take after seeking earlier takes |
| `--camera-preview` | Mirrored front preview/recording in portrait/landscape and Kaleidoscope orientation |
| `--references` | Project delete/restore, shared-media safety, project controls, mirror geometry, RGB offsets |
| `--new-ui` | Live effects sheet, Clear all, Widescreen, 4:5 crop, quarter turns |
| `--hdr-color` | Full/limited P010 patches through GL, C11, decoder, and Main10 encoder; primaries detection and sidecars |
| `--hdr-scene` | SDR/HDR scene comparison and a direct HDR recording bypassing the compositor |

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
ANDROID_SERIAL=SERIAL python3 tools/check-hdr.py --device
```

This uses FFmpeg to inspect encoded patch values. Generate the fixture with
`tools/hdr-fixture.py`. Optional `hdr-color-reference.png` and
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

A successful capability-rejection test does not prove HDR encoding. The Mi 8
rejected native ten-bit HDR images; its earlier tiny-resolution encoder check
cannot establish the supported sizes of its Main10 encoder. The OnePlus 15
passes actual 720p, 1080p and 4K Main10 HLG export: 741/855/874 distinct ramp
levels, about 4.89 times SDR-white headroom and 0.0038/0.0034/0.0024 mean linear
error. `--hdr-full` requires this
successful path. An independent FFmpeg probe verifies the output profile and
tags. Independent FFmpeg pixel decoding also confirms the distinct-code counts
and headroom; `artifacts/oneplus15/hdr-independent-decode.json` records those
measurements beside the movies and metadata.

The OnePlus 15 checkpoint passes 97 host editor checks and 257 device checks
across 27 suites. `artifacts/oneplus15/acceptance.json` records the complete
matrix and matching build, installed and copied-APK fingerprints. The copied
APK is `artifacts/myrec-debug.apk`. Project/media integrity against the pre-test
backup is recorded separately in `project-integrity.json`.

Codec selection checks source/output sizes and tries compatible encoders.
Android 17 reports the foreground activity as `topResumedActivity`; the runner
recognizes that form so its ADB fallback cannot restart a running test session.
AAC checks include explicit decoder preroll handling and MP4 presented-duration
bounds, avoiding double gapless trimming on newer Android releases.

Imported depth needs an original Cinematic movie with its disparity track.
YouTube excerpts contain ordinary video/audio and cannot validate depth decoding.
The reference's real-depth integration tests require `YOUREC_CINEMATIC_FIXTURE`;
they skip without it. Synthetic maps test rendering separately from decoding.
The landscape excerpt used locally came from the user-supplied YouTube link;
it is kept under `.build/` and is not a redistributed test asset.
