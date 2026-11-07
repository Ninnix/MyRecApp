# Testing YouRec

Run commands from the repository root. Choose checks based on the behavior that
changed; do not redownload large model files for a UI-only fix.

## Build and model checks

```sh
./tools/build.sh :app:testEditor :app:assembleDebug :app:assembleDebugAndroidTest
```

The pure suite checks editing, undo, independent effect state, transition
source clocks and phases, handle limits, microphone identities and camera
capability selection. It does not need a connected phone.

## Device runner

Use `ANDROID_SERIAL` to choose a device explicitly:

```sh
ANDROID_SERIAL=SERIAL python3 tools/test-device.py
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --parity
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --exports
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --speech
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --studio
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --orientation
ANDROID_SERIAL=SERIAL python3 tools/test-device.py --timeline
```

The runner installs the app and instrumentation APKs without clearing app data,
grants camera/microphone access, and writes logs and captures to `artifacts/`.
Test project metadata is isolated under cache. Recording tests create short
fixture recordings. Keep the phone unlocked and YouRec in the foreground.

* Default: native image/audio processing, decoding, stabilization, composition,
  export, camera switching, pause/resume, actual AF lock state, microphone names
  and portrait recording.
* `--parity`: portrait/landscape panels, drafts, cancellation, rotation,
  placement, volume removal, slider input and timeline workflows. The name
  denotes the UI suite; passing it is not a pixel-equality comparison.
* `--exports`: transition frames, person-isolation ranges, 720p/1080p/4K output
  and a longer multitrack export.
* `--speech`: RNNoise and actual offline Qwen transcription; requires caption
  weights. See [MODELS.md](MODELS.md).
* `--studio`: neural restoration, filter order, ranges, mute, caching,
  cancellation, live changes and audio export; requires Studio weights.
* `--orientation`: asymmetric corner markers through 0/90/180/270-degree changes
  and different source sizes in one encoded file, plus real front/rear switching
  in portrait and landscape with either camera first. Checks include audio/video
  timestamp alignment; playback dimensions alone do not prove upright content.
* `--timeline`: rendered thumbnail pixels move together across split boundaries
  when dragging in either direction, in portrait and landscape. Covers splits
  from one source and adjacent clips from separate sources, and verifies that
  scrolling leaves clip data unchanged.

The latest local verification summary is kept in
[artifacts/validation.json](../artifacts/validation.json). APKs, raw captures,
logs and working references are local build artifacts, not repository content.

## Limits

The implemented effects and UI have not been certified as identical to the
original iOS application. Physical accessories, real alarm/call interruptions,
difficult person-segmentation footage and sustained thermal behavior need
broader testing. Capability-profile tests do not replace checks on other phones.
Caption timing is chunk-level. Short fixture performance is not a universal
quality or latency claim.

Test asset provenance is recorded beside the
[fixtures](../app/src/androidTest/assets/README.md).
