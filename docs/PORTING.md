# YouRec porting status

Reviewed 25 November 2026 against MyRec 0.1.9 (Android version code 10, `89d7c54`). The upstream default branch still points to `dadc258`. Its Xcode project declares **1.0.1, build 202**. This audit covers the released Android source and the ten upstream commits since the previous audit at `faf1815`. [Pinned YouRec source](https://github.com/antirez/YouRecApp/tree/dadc2585fdf7a0ef4e51e9465888296eae4ebf19) · [Upstream changes](https://github.com/antirez/YouRecApp/compare/faf1815...dadc258) · [MyRec 0.1.9](https://github.com/Ninnix/MyRecApp/releases/tag/v0.1.9)

**Bidirectional project verification is the first blocker.** A grade applied to part of a clip could push an iOS project beyond the MyRec 0.1.9 reader. The working build handles newer layouts, and a round trip through both apps still needs verification. The other major blockers are matching the rendered picture and sound and demonstrating recovery on the devices for which MyRec claims support. MyRec is an independently branded port. Upstream reserves the YouRec name and icon. [Project formats](https://github.com/antirez/YouRecApp/blob/dadc258/YouRec/Project.swift) · [Android archive reader and writer](../app/src/main/java/org/myrec/app/YouRecArchive.kt) · [Upstream README](https://github.com/antirez/YouRecApp/tree/dadc258#readme)

## Release blocker checklist

For a release described as having **YouRec mobile parity**, these are the open decisions in priority order. MyRec can continue shipping Android updates with narrower, documented support.

- [ ] **First, preserve the edit.** Transfer a current native project in both directions with ranged edits, styled titles, independent tracks and non-default export settings. Reopen the result on both platforms and verify undo/redo and originals. Rows 1–3.
- [ ] **Protect recordings and saved projects.** The OnePlus passed active-take recovery, partial-import rollback and a physical Bluetooth disconnect/reconnect. Exercise cancellation on real providers, near-full storage, USB loss and long HDR and dual-camera takes. Row 6 and the interrupted-work checks below.
- [ ] **Match what the creator sees and hears.** Compare live preview, editor and decoded export against YouRec with identical fixtures, including HDR highlights, transition edges, masks, restored speech and clipping. Rows 4 and 9–13.
- [ ] **Complete the supported workflows.** Check the recorder, timeline, inspector, project library and sharing through their ordinary touch and accessibility controls on phones and tablets. Rows 3–8.
- [ ] **Publish the verified device scope.** Record sustained frame timing and tested camera, microphone, Android-version and codec combinations. Identify hardware-dependent features such as aperture and native Log individually. Rows 7–8 and 10–13.

## Android implementation since 0.1.9

The tables below describe the 0.1.9 starting point. The working build adds the
following changes.

- **Archives.** Preserve codec, resolution and frame rate. Import and export
  ranged grades, grouped titles, scoped framing and disjoint framing variants.
  Import source windows on supported picture effects, text and images, and read
  inline track-based edit history. Resolve automatic canvas size from the
  selected source's displayed dimensions. On
  Android, each disjoint fragment is editable as its own range. A native
  round trip of arbitrary shared effect identities, saved analysis and
  pre-track history still needs work. [Archive bridge](../app/src/main/java/org/myrec/app/YouRecDocument.kt)
- **Recording and imports.** Recover active takes from checksummed encoded
  samples. The OnePlus force-stop check decodes the picture and sound after
  restart and verifies a second restart leaves exactly one recovered take.
  Media imports stage and validate the batch before publishing. An injected
  failure on the second file preserved the saved edit and the last movie
  byte-for-byte. [Recording](RECORDING.md) · [Acceptance tests](TESTING.md#reliability-acceptance)
- **Editor.** Grading, crop, mirror and stabilization can use part of a clip
  without cutting its linked tracks. Text supports a label and headline on
  one card and includes all 24 upstream single-title looks. Visible filmstrip tiles can render edits in the background, and
  detailed source waveforms show signed one-millisecond peaks. The preview can
  copy its rendered frame, and cover detail supports adjacent-frame navigation.
- **Sound and controls.** Export records the peak of an edited mix before
  clipping and retains the warning through resume. The OnePlus test verified a
  local gain boost and an unchanged-audio control. The recorder exposes its
  core actions to accessibility services and supports half-second zoom-duration
  gestures and a one-second double tap.

The OnePlus 15 (CPH2747, API 37) completed a 661.6-second 1080p SDR take across
pause and resume. Both tracks had monotonic timestamps, both picture endpoints
decoded, and the largest video sample interval was 63.3 ms. The final audio
sample was 154 ms after the final video sample. The device reported thermal
status 0 on all 22 readings and exposed one aperture per camera.

The Nothing Ear (a) microphone passed a physical disconnect and reconnect
during a take. The built-in microphone took over, stayed active after the
earbuds reconnected, and the recorded picture decoded. The headset initially
reported a backward timestamp, which now falls back to the PCM sample clock
without reopening the microphone.

On the host, two natural stabilization clips measured p95 translation
differences of 0.07 and 0.32 pixels against the pinned YouRec tracker. With the
same ONNX graphs on both sides, three speech cases measured maximum RMS residual
of 0.000021 for LavaSR and 0.000182 for VoiceFixer. These are host DSP results.
[Stabilization runner](../scripts/compare-stabilization.py) · [Restoration runner](../scripts/compare-restoration.py)

Actual iOS rendering and listening, supported variable-aperture hardware,
and the broader tablet and accessibility device matrix remain open. Android's
standard Camera2 aperture request is overridden under automatic exposure, so
the iOS aperture-priority behavior needs a verified device-specific path or an
Android exposure controller validated on variable-aperture hardware.
[Camera2 aperture contract](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#LENS_APERTURE)

## Project compatibility and editor behavior

| Open work | What is different in 0.1.9 | What would close it |
| --- | --- | --- |
| 1. Native project ZIPs | MyRec's reader gates on schemas 1–12 and its writer emits 12. Even within that range, the reader expects snapshot files or inline snapshots and rejects older inline edit history. YouRec uses **13** for ranged grades, **14** for label/headline titles, **15** for ranged crop/mirror/stabilization and **16** for disjoint effect windows, framing variants, title playback clocks and certain shared audio/grade fragments. MyRec's own on-disk format 15 is a separate format. | Exercise an iOS-authored ZIP for each feature revision, inspect and edit it on Android, then reopen the Android ZIP in YouRec. Compare source hashes, fonts, history/redo, ranges, sampled frames and sound in both directions. Cover legacy history and preserve rejection of unsupported data. |
| 2. Effects that span cuts | MyRec supports ranges, shared effect IDs and animation. The latest editor also distinguishes editing the whole application from changing or removing only a selected piece. A timed LUT or framing change can leave the clip, linked audio and their IDs intact. A title fade or film cadence continues through a hole. | Exercise both handles across two cuts, offscreen edges, overlapping rows, gaps, conflicting newer effects and retimed footage. Reopen from either badge, apply/cancel, split, move, paste, undo and export. Compare the first frame inside and outside every edge and the corresponding audio samples. |
| 3. Titles and overlays | The Android gallery has eight single-title looks. YouRec has 24 across Editorial, Cinema, Print and Display, plus six Label + headline looks. Android lacks the grouped title model and the bundled Bodoni Moda and Unbounded families. | Support one movable, timed card with independently selectable and movable label/headline, per-line appearance, card fill/border/spacing, reset and reusable channel styles. Verify narrow canvas wrapping, label-only cards, missing/imported fonts, SDR/HDR draft and export, copy and ZIP. Check oversized image drag/pinch and independent subtitle X/Y center snapping. Ship the corresponding font notices. |
| 4. Filmstrip and playback | MyRec samples up to twelve pictures per video path for the filmstrip. A changed grade, title, caption or placement can therefore appear in the player while the strip shows the original. Holding the editor picture to copy the current composed frame is also missing. | Match source-time sample reuse across split/delete/undo, invalidate affected rendered tiles and preserve the visible frame during a cut rebuild. Exercise repeated cuts, immediate pause/resume with a selection, frame copy during playback, the last included source or composited frame, and a black audio-only tail. |
| 5. Range controls, selection and large screens | Transform poses, move groups, preview inspection and timeline accessibility actions exist. The current upstream editor adds an independent Start/End ruler, editable effect edges on the strip, selection-specific menus, linked-group outlines and a tablet workspace with sidebar, palette and transport. | Navigate from Tools to Range and back, keep the opposite edge fixed, seek its correct picture, preview only the selected footage and open a title on a readable frame. Verify selection actions, linked/detached lanes and overlaps, physical keyboard/pointer where supported, tablet resizing, large text and TalkBack. |
| 6. Media import, library and covers | The editor can order mixed media. The Projects importer uses a single document. Editor batch copying offers no in-flight Cancel action. Recently deleted has individual restore/erase. The cover grid can refresh and share a rendered JPEG. | Match ordered gallery creation and Files ordering, original-byte fallback on providers with multiple representations, cancellation of a stalled transfer, disk-full handling and atomic publication. Verify multi-select/reorder of unused takes, history-only versus active missing media, confirmed Delete All and the 30-day review prompt. In cover detail add/check random Update, one-frame swipes, failure/retry and sharing only the displayed frame. |

Reference checks for these rows are in [VideoToolsTests](https://github.com/antirez/YouRecApp/blob/dadc258/Tests/VideoToolsTests.swift), [TrackTimelineTests](https://github.com/antirez/YouRecApp/blob/dadc258/Tests/TrackTimelineTests.swift), [TitleDesignTests](https://github.com/antirez/YouRecApp/blob/dadc258/Tests/TitleDesignTests.swift), [EditorUITests](https://github.com/antirez/YouRecApp/blob/dadc258/Tests/EditorUITests.swift) and [PlaybackEndUITests](https://github.com/antirez/YouRecApp/blob/dadc258/Tests/PlaybackEndUITests.swift). Android entry points include [Project](../app/src/main/java/org/myrec/app/Project.kt), [TitleStyle](../app/src/main/java/org/myrec/app/TitleStyle.kt), [MediaIndex](../app/src/main/java/org/myrec/app/MediaIndex.kt), [MainActivity](../app/src/main/java/org/myrec/app/MainActivity.kt) and [CoverPicker](../app/src/main/java/org/myrec/app/CoverPicker.kt).

The working build preserves non-default export settings in the native header. Reopen a native-only HEVC, 720p and 24 fps project in YouRec to verify the result. Source dates and project timestamps still need preservation. [Native archive header](../app/src/main/java/org/myrec/app/YouRecArchive.kt) · [Native document import](../app/src/main/java/org/myrec/app/YouRecDocument.kt)

## Camera, audio and output

| Open work | What is different or still needs measurement | What would close it |
| --- | --- | --- |
| 7. Camera controls | Single-camera tap-to-focus, per-camera zoom/manual focus, labeled recorder destinations and the project caption under the timer shipped. Variable physical aperture is missing. The recorder still uses a separate More settings menu. | On hardware exposing several usable apertures, offer Auto and supported stops beside AF, recall per physical lens, keep shutter/ISO/focus automatic as appropriate and read back the actual stop before, during and after a take. Verify fixed lenses and dual mode. Match the expandable settings/effects sheets, project title and captured duration, full-card effect taps, front-light controls, full-frame/fill preference, half-second zoom-time gesture and one-second double-tap ramp. Check color and framing locks in idle, pause and record. |
| 8. Microphone and interruptions | Per-input gain, clipping indication and route priority exist. The current iOS settings expose background music playback, interruption protection/guidance, inline routing information and a zero-dB drag detent. | Verify the selected microphone alone is captured with external playback on/off, gain stays at the selected value through meter updates, route selection lasts until Stop and accessible half-dB steps can cross zero. Exercise real USB/Bluetooth loss, refusal, reconnect, call banners, answered calls, alarms, permission loss and return to the app. Document the observed Android behavior. |
| 9. Editor sound and export warnings | MyRec shows an original-source 10 ms waveform and has volume/fade controls. Upstream also draws signed 1 ms high-zoom detail and processed waveforms. Its quick gain control accounts for the whole audible mix. Export receipt v2 measures only intervals the edit can boost. | Test mixed gains, opposing-phase overlapping audio, edge stepping, sideways cancel, zero detent, one undo, continuous playback and selected-gain ramp. Verify waveform timing through cuts, speed and silence. Test no warning on untouched loud audio and a warning at **+0.5 dB** on an edited interval, including background sound, old receipts and a resumed export. |
| 10. HDR and creative effects | Android already decodes and exports Main10 HLG on qualified hardware. Upstream changed the headroom entering creative effects in live preview, editing and export. Geometry-only work retains HDR. Earlier Android mixed-layer results establish a different test case. | Run the upstream neutral-effect ramp and a geometry-only control through all three paths. Compare shadows, midtones and highlights against the same iOS render and decoded HLG file, including transfer, primaries, range, alpha and output tags. Check real Log inputs, scoped effects and mixed SDR/HDR layers. |
| 11. Camera timing and framing | The compositor pairs timestamped pixels. Android buffers two pending frames per camera and accepts up to 20 ms skew. YouRec buffers eight and accepts about 17.7 ms. Three OnePlus SDR zoom reruns produced zero additional encoder intervals over 50 ms beyond those measured at the sensor. | Measure skew, dropped pairs, cadence, memory and latency on uneven feeds across a lens switch, pause/resume, HDR/dual capture and higher rates. Verify the preview and saved mirror/rotation, horizon, face and effect guides against an asymmetric target. Test circle edges, alternative camera pairs and upright wide selfies on capable hardware. |
| 12. Processing fidelity and speed | Android has all 24 upstream effect kinds and nine transition styles. The Android quantizer still interpolates five anchors for each Pop palette. Person isolation, optical flow, bokeh and motion blur use different backends. Standalone Auto Background preview approached 30 fps on the measured phone, while prior recording-preview runs were around 7–8 fps. | Match the authored palettes, Oklab clustering, temporal update/cut reset, dither and lock/resample behavior. Compare exported natural scenes for moving hair, occlusion, keying, Night fix, film textures, stabilized motion and imported Cinematic disparity. Exercise geometry, step-printing, live stacks, transition holds/edge audio and sliced joins together. Measure end-to-end preview and recording cadence on representative hardware. |
| 13. Audio models and captions | LavaSR v2 and VoiceFixer run on Android. Source-DSP comparisons exist with the same ONNX graphs and matched audition files. Core ML output and listening equivalence remain open. Estimated word timing is shared by both products. | Compare the same clean, clipped, narrow-band, noisy and musical material against actual iOS output with matched loudness. Include DeepFilterNet, source-window boundaries, stereo, filter order, pitch, bypass loudness, long-source resume and pauses. Evaluate transcript errors and timing on noisy multilingual speech. Record first-use availability with optional model packs offline. |

The working build adds half-second zoom-duration gestures and uses a one-second ramp on a fast second preset tap. Exercise those gestures while recording and compare sensor and movie timestamps across the ramp. [Android capture](../app/src/main/java/org/myrec/app/Capture.kt) · [YouRec zoom controls](https://github.com/antirez/YouRecApp/blob/dadc258/YouRec/RecorderView.swift)

Capture color also differs before the first take. MyRec starts in SDR and requires Stop to change HDR during a recording session. YouRec defaults to HDR on supported cameras and permits a color change while paused in single-camera mode. On a capable phone, check the first-run choice, session continuity, color metadata at a paused change and the SDR fallback. Android also offers dual HLG on supported pairs, while YouRec's dual output is SDR. [Android capture](../app/src/main/java/org/myrec/app/Capture.kt) · [YouRec recording behavior](https://github.com/antirez/YouRecApp/blob/dadc258/README.md#current-build)

Two processing differences affect everyday editing. Android's pause finder uses source RMS and thresholding. Speech probabilities, a steady-noise adjustment and protection of the onset of a word still need comparison on speech with room noise. The Kaleidoscope guide follows the fitted canvas, so a transformed layer or mirrored camera needs a guide-to-source coordinate check. [Android pause detector](../app/src/main/java/org/myrec/app/PauseReview.kt) · [Editing guide](EDITING.md)

Reference contracts and Android procedures for rows 7–13 are available in the following sources. [Camera QA](https://github.com/antirez/YouRecApp/blob/dadc258/docs/CAMERA-QA.md) · [Dual-camera timing](https://github.com/antirez/YouRecApp/blob/dadc258/YouRec/DualCameraLayout.swift) · [Audio gain](https://github.com/antirez/YouRecApp/blob/dadc258/YouRec/AudioGain.swift) · [HDR brightness change and tests](https://github.com/antirez/YouRecApp/commit/4c8cd33) · [Android recording](RECORDING.md) · [Android models](MODELS.md) · [Android test suites](TESTING.md)

## Release acceptance

- **Preservation and interchange.** Exercise both applications with the same real project, including unused originals, redo-only assets, imported fonts/LUTs, separate captions, held footage, placement, native transition fragments and analysis data. Independently open the OTIO conform in an external editor. Cover nested overlaps, trailing gaps, offline files, ambiguous relinks, and cancellation on a real cloud provider. Exercise migration from the old Android package with user-granted source access.
- **Interrupted work.** The OnePlus evidence includes eleven-minute SDR recording, 256-clip and 2,048-second project coverage, process termination during an active take and during save/export, injected low storage, and injected thermal pressure. Older unindexed MP4 salvage, long HDR and dual takes, actual thermal throttling, near-full storage and additional physical interruptions remain open. Check original hashes, one-time attachment of recovered takes, last committed undo state and last good export after each fault. [Reliability procedure](TESTING.md#reliability-acceptance)
- **Device coverage and accessibility.** Repeat the affected workflows on the minimum supported API 28 and intermediate API 30/31/33, a constrained phone, a tablet and a capable HDR phone. Measure the reported MediaTek P010 path on the actual device, native Log only where the output curve can be verified, and the Android 10 SDR matrix with a decoded chart. Check TalkBack traversal/actions, touch targets and text scaling in recorder, library, timeline, inspectors and export. Keep the phone's current project recoverable through an in-place signed upgrade.
- **Regression coverage.** Map outstanding upstream cases to an Android host check, a device test or an explicit platform exclusion. Include real frame/sample comparisons for scoped effects and HDR, large-text and tablet gestures, old archives and offline recovery. This mapping and an executed cross-platform run are still open. [Upstream test inventory](https://github.com/antirez/YouRecApp/tree/dadc258/Tests) · [Android device suites](TESTING.md)
- **Evidence for a quality claim.** Record the app revisions, device/OS, fixture hash, settings and exact frame times or sample ranges. For each open row keep the Android result, corresponding iOS output where required, and any hardware or platform limit. A real upstream endorsement would be a separate decision by the YouRec maintainer. The macOS Finder project library, display/window recorder, desktop PiP and Mac-only shortcut tests have separate platform scope. The iPad touch editor remains relevant for Android tablets.

## Changes already in the Android baseline

On 25 November, `./scripts/build.sh :app:testEditor --offline` passed all 232 editor checks on `89d7c54`. ADB listed no attached device. Physical observations below come from earlier development runs. A same-fixture comparison on an iPhone remains outstanding.

| Area | Evidence to retain when closing remaining gaps |
| --- | --- |
| Editor and capture | Bounded dual-frame pairing, editable crop/placement/image poses, higher-resolution preview inspection, accessible timeline actions, transition ripple fixes and persistent export size/rate/codec with resume. [Implementation and regressions](https://github.com/Ninnix/MyRecApp/commit/0a77f2a) |
| Processing | Natural-footage comparisons with the upstream stabilization tracker, a corrected LavaSR crossover, Android mixer comparisons and decoded mixed HLG/SDR exports. [Implementation and comparison tools](https://github.com/Ninnix/MyRecApp/commit/54bd497) |
| Reliability and zoom | Capture finalization reserve, saved-edit and export recovery, and sensor-timestamp-driven encoding during zoom. [Recovery work](https://github.com/Ninnix/MyRecApp/commit/b034027) · [Zoom cadence regression](https://github.com/Ninnix/MyRecApp/commit/52a6d3b) |
| Recorder and transitions | Tap-to-focus, project/time labeling, unclipped style tiles, nine transition render paths, fitted audio/picture and Flash Cut timing at the export rate. [Release changes](https://github.com/Ninnix/MyRecApp/commit/89d7c54) |
