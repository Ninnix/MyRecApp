# Testing MyRec

Test the behavior a change can affect. A camera change needs a real camera;
a layout change does not need a fresh model download. Run commands from the
repository root.

## Build first

```sh
./tools/build.sh :app:testEditor :app:assembleDebug :app:assembleDebugAndroidTest
```

The pure editor suite checks source clocks, splits, copies, independent effect
state, transitions, undo, track movement, microphone identities, and camera
capability selection. It needs no phone.

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

| Option | Checks |
| --- | --- |
| No option | Native image/audio calls, runtimes, persistence, decode/export, stabilization, composition, camera switching, pause/resume, AF, microphone labels, portrait recording |
| `--parity` | Portrait/landscape tools, drafts, cancellation, rotation, placement, volume, sliders, timeline, Settings, Animate controls |
| `--exports` | Transition pixels, person-isolation ranges, 720p/1080p/4K and longer multitrack export |
| `--speech` | RNNoise and actual offline Qwen transcription; caption model required |
| `--studio` | Neural filters, order, ranges, mute, cache, cancellation, live changes, audio export; models required |
| `--gaps` | DeepFilterNet3/VoiceFixer inference, all neural ranges, multi-model order, camera inventory, Kaleidoscope/Widescreen pixels |
| `--orientation` | Asymmetric markers, four metadata rotations, different input sizes, real camera switches, frame orientation, AV timing |
| `--timeline` | Thumbnails scroll together across split boundaries; clip data stays unchanged |
| `--composition` | Encoded pixels for five dual layouts, round masks, swaps, normal framing, four rotations |
| `--camera-gaps` | Real concurrent capture, layout changes, pause/resume, timing, Main10 HLG metadata, HDR decode/SDR export, live effect ranges |
| `--live-rotation` | Display rotation during one SDR, HDR, or dual take; UI reflow and saved-frame comparison |
| `--capture-controls` | Mark/Pause positions, saved marks, torch, border transparency/resize, brightness restoration, PiP movement/resize/swaps |
| `--media-editing` | Copy/paste, multitrack drag, Cancel/Done, overlapping Placement, audio picker import, persistence and decoded sound |
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

[artifacts/validation.json](../artifacts/validation.json) contains earlier local
verification. Match its dates and APK hashes to the build being assessed;
historical runs do not certify a newer build.
Test-media provenance is beside the [fixtures](../app/src/androidTest/assets/README.md).
APKs, raw captures, logs, and the iOS checkout stay out of Git.
