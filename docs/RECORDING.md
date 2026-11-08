# Recording

Open Projects, then Record or Continue recording. A new recording appends to the
current project unless you choose Start new project. The red button starts and
stops capture. Pause keeps the recording session and project together.

In portrait, scissors sit to the right of Record. They open the editor, stopping
an active recording first. The stacked icon opens Projects while idle; that
position to the left holds Pause during recording.

## Cameras and focus

The camera-with-arrows button switches front/rear. The lens selector and its
chevron open the available camera choices. Zoom presets use the chosen duration;
a quick second tap changes zoom immediately.

Tap AF to lock or release focus. AF L and the yellow indicator show a requested
lock. The app uses a supported autofocus mode and checks Camera2's lock state
in device tests. Fixed-focus cameras are labelled Fixed. Lenses, zoom ranges
and focus behavior come from camera capabilities, not manufacturer checks.

Switching cameras keeps the same recording. Camera frames are rotated into the
encoder's initial orientation before encoding, so different front/rear sensor
orientations do not turn part of the file upside down. Source resolution can
change while the encoded resolution stays fixed. Frame timestamps use the
recording clock to preserve audio/video timing.

The renderer removes the sensor rotation already carried by a camera texture
before applying the recording correction. Canvas inputs do not carry that
camera transform. This distinction follows Android's
[camera texture transform handling](https://android.googlesource.com/platform/frameworks/support/+/6353c5d0cbdb0a766f9640dde3ffcb0f9e4cfcf1/camera/camera-core/src/main/java/androidx/camera/core/processing/SurfaceOutputImpl.java).

## Split view and HDR

The overlapping-camera button opens Picture in picture, side-by-side,
top/bottom and diagonal layouts. Two cameras run simultaneously when Android reports a
supported concurrent front/rear pair. Layout changes are recorded in the same
video. Tap the divider to swap views; use two fingers to pan or zoom either
composition region. Drag the inset in Picture in picture. The circular arrow
restores normal single-camera framing. Turning two-camera capture on requires
stopping recording; returning to normal framing works during a dual recording.
Concurrent streams use up to
1080p per camera, negotiating both output configurations together and falling
back to smaller sizes when needed. Output keeps the selected recording size. Support follows camera capabilities, without a phone-model list.

Tap SDR/HDR before recording to change dynamic range. HDR requires Android 13,
HLG camera output, HEVC Main10 encoding and a compatible 10-bit EGL surface.
The renderer preserves HLG for the recorded BT.2020 video and tone maps the live
preview. The renderer reads the buffer colour range and uses an explicit
BT.2020 conversion for 10-bit camera textures. Unsupported cameras retain SDR.
The editor converts HLG/PQ and P010 buffers through C11 to SDR for editing,
using the same HLG preview curve. H.264 exports are SDR;
the original HDR recording remains in the project media.

When enabling HDR, the app compares registered SDR and HLG frames to check the
camera's RGB primaries. A strong match can select BT.709 or Display P3 instead
of the declared BT.2020 interpretation. Ambiguous, flat or unrelated scenes
retain the existing profile. Profiles are stored per camera and Android build,
without a manufacturer list. Keep the camera still briefly when enabling HDR.
The selected interpretation applies to both preview and encoding; recorded
pixels are converted to BT.2020 before HEVC encoding. This preserves 10-bit HLG
and avoids relying on another player's handling of incorrect source metadata.
An optional `.primaries` sidecar (0: declared, 1: BT.709, 2: Display P3) lets the
editor interpret confirmed older mislabelled files without rewriting them.

On the attached camera, BT.709 interpretation matched SDR colours substantially
better in three registered scene comparisons. Camera processing and tone
mapping can still produce brightness and colour differences. Synthetic P010
patches check all three interpretations through preview, the C11 converter,
the editor and actual corrected HDR encoding. Correctly labelled BT.2020
input keeps its original HDR values.
These checks do not establish identical rendering to the iOS app.

Camera effects open in a collapsible live sheet. Add effects, toggle them or
clear the chain while viewing the camera. Changes during recording retain their
time ranges in the saved clip, so the effects remain editable. Kaleidoscope
mirrors angular sections; Widescreen 1953 adds cinema framing and highlight
flares. Their rendering is an implementation inferred from the supplied
controls, rather than verified iOS shader code.

## Microphones

Tap the microphone name to choose an input. The list includes physical recording
routes and excludes system-only tuner, telephony and echo-reference inputs.
Labels distinguish USB, Bluetooth, wired and phone inputs. Phone locations are
shown when reported; otherwise distinct route labels avoid guessing which hole
or microphone array a route represents.

Microphone priority lets you move an input to the top of the preferred list.
Separate routes with the same product name retain separate preference keys.
Before recording, the highest available preferred input is selected.

Disconnecting the selected microphone or losing recording access pauses
capture. Android and the accessory determine which routes can be selected or
changed during recording. A physical microphone array may be exposed as one
selectable route rather than individual elements.

Camera effects are non-destructive and remain editable after capture.
