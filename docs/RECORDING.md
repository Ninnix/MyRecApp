# Recording

Open Projects, then Record or Continue recording. New footage appends to the
current project unless you choose Start new project. The red button starts and
stops the take. Pause keeps the session together. Completed takes use a recording
ledger, so undoing an older edit does not remove a new take. Interrupted files
that remain playable are recovered into their recorded project once. Unreadable
unfinished files are kept for recovery; the app does not yet repair unfinalized
MP4 containers.

While idle, scissors open the editor and the stacked icon opens Projects.
During a take, portrait puts Mark to the right and Pause/Resume to the left;
landscape puts Mark above Record and Pause/Resume below. Mark saves a point
without stopping, including while paused. The editor keeps those marks with
their source footage.

The controls follow Android's auto-rotate setting. Turning the phone does not
stop the take. The file keeps its starting frame size; rotated views fit inside
it without cropping the scene.

## Choose a camera

The camera-with-arrows button switches front and rear. The lens selector lists
available cameras. Zoom presets use the selected duration; a quick second tap
changes zoom immediately. Tap AF to request or release focus lock. AF L and a
yellow indicator show the request. Fixed-focus cameras are labelled Fixed.

Camera and zoom choices come from Android's reported capabilities. Switching
keeps the same recording and its encoded frame size. Frames are rotated to
that initial orientation; timestamps follow the recording clock.

The front camera is mirrored in preview and in the recording. Independent
preview/save mirror switches, manual lens focus, face following, and horizon
leveling from the iOS app are not implemented yet.

## Use the light

Torch is in Recording settings. Rear cameras use the hardware light when one
is available. The front camera uses a white screen border at full window
brightness. Its rounded inner edge is slightly transparent; bars outside the
video are opaque. Drag the border to change its thickness.

Brightness returns to its previous setting when the light is switched off,
the camera closes, or only a rear view remains. Split view can use the screen
light while its front camera is visible. The border is not recorded.

## Record two views

The overlapping-camera button offers rectangular or round picture in picture,
side by side, top/bottom, and diagonal layouts. This needs a supported concurrent
front/rear pair. Layout changes become part of the saved composition.

Tap the divider to swap views. Use two fingers to pan or zoom a region. In
picture in picture, the inset's camera-arrow button swaps the large and small
views. Drag its body to move it, an edge to resize one dimension, or a corner
to resize both. The round inset stays circular in both orientations.

These gestures work during recording. The circular arrow restores normal
framing. Enabling two-camera capture requires stopping; returning to normal
framing works within a dual recording. Inputs negotiate up to 1080p per camera
and fall back together if needed. Output keeps the chosen recording size.

## Record HDR

Choose SDR or HDR before recording. HDR needs Android 13, an HLG camera output,
HEVC Main10 encoding, and a compatible 10-bit EGL surface. Unsupported cameras
keep SDR. Recording stores 10-bit BT.2020 HLG; preview is tone mapped.

The app checks the camera's RGB primaries by comparing registered SDR and HLG
frames. Keep the camera still briefly when enabling HDR. A clear match may
select BT.709 or Display P3 interpretation rather than the declared BT.2020.
Ambiguous scenes keep the current profile. The choice is stored per camera and
Android build. Confirmed input is converted to BT.2020 before encoding.

The editor can also read an optional `.primaries` sidecar for an older file:
0 means declared, 1 means BT.709, and 2 means Display P3. It changes interpretation
without rewriting that file. Synthetic patch tests cover these paths, but do
not establish the same color rendering as iOS.

The editor uses half-float HDR working images. Export offers H.264 SDR and, on
capable hardware, HEVC 10-bit HLG. A device must expose ten-bit decoder samples
and recording/encoding support; unsupported paths report the missing capability.
The original recording stays HDR. Identified native Log capture remains open.

## Camera effects

Open the live effects sheet to add, toggle, or clear effects. Changes during a
take keep their time ranges, so you can edit them later. Kaleidoscope mirrors
the picture; RGB Offset separates color edges; Widescreen 1953 adds framing and
highlight flares. Their controls and processing still differ from the iOS
implementation.

## Choose a microphone

Tap the microphone name to choose an input. The list shows physical recording
routes and omits system-only routes. USB, Bluetooth, wired, and phone inputs
have distinct labels. Phone locations appear when Android reports them;
otherwise route labels avoid guessing where a microphone sits.

Microphone priority remembers separate routes, even when they share a product
name. Before recording, the highest available preferred input is selected.
Disconnecting the chosen microphone or losing recording access pauses capture.
This differs from iOS's independent microphone engine and automatic fallback.
A physical microphone array may appear as one route, rather than separate
selectable elements. A live peak meter works before and during recording.
Per-microphone recording gain and the iOS clipping/no-signal diagnostics are
still missing.
