# Recording

Open Projects, then Record or Continue recording. New footage appends to the
current project unless you choose Start new project. The red button starts and
stops the take. Pause keeps the session together. Completed takes use a recording
ledger, so undoing an older edit does not remove a new take. MyRec reserves
space to assemble the finished movie and stops the take if storage runs low or
Android reports critical thermal pressure. A finished movie can recover into
its recorded project once even if MyRec stopped before saving the timeline.
New recordings keep checksummed encoded samples while the take is running.
After a process stop, MyRec rebuilds the movie from complete samples and
reattaches the take when the project opens. A torn final sample is discarded.
Recordings made by earlier builds can still require repair of an unfinished MP4.

While idle, scissors open the editor and the stacked icon opens Projects.
During a take, portrait puts Mark to the right and Pause/Resume to the left;
landscape puts Mark above Record and Pause/Resume below. Mark saves a point
without stopping, including while paused. The editor keeps those marks with
their source footage.

The controls follow Android's auto-rotate setting. Turning the phone does not
stop the take. The file keeps its starting frame size; rotated views fit inside
it without cropping the scene.

The top bar has the microphone on the left, the clock in the center, and live
video effects and Camera settings on the right. Camera settings opens a bottom
panel in portrait and a side panel in landscape. It contains stabilization,
horizon leveling, face following, the light, Use two cameras, and the SDR/HDR
recording-color selector. More settings opens resolution, frame rate, microphone
priority, separate selfie preview/save mirror switches, Max angle and Reset
framing. Stop the take to change color or resolution; pause it to change the
camera setup or horizon settings.


## Choose a camera

The camera-with-arrows button switches front and rear. The lens selector lists
available cameras. Zoom presets use the selected duration. Swipe up or down on
the duration to change it in half-second steps. Double-tap the same preset for
a one-second move. Each camera remembers its own zoom and focus state.
In Single camera mode with AF enabled, tap a subject in the preview to focus on
it. A yellow box marks the selected point on lenses that support it.
Tap AF to request or release focus lock. On adjustable lenses, drag the locked
focus control vertically for manual focus; short movements make fine changes,
and longer movements accelerate. The percentage runs toward infinity at 100%.
Hold the focus control to toggle red focus peaking. It marks sharp edges in the
preview and is never recorded. Fixed-focus cameras are labelled Fixed.

Dolly Zoom can follow a face or a tapped subject while you move. Stretch can
first zoom in to leave room for compensation; Compress follows as you move away.
Start / stop tracking starts the move or brakes it smoothly. The duration,
subject loss and lens limits bound the move. Face measurements use the applied
camera zoom; a tapped subject uses image tracking. The physical compensation is
already in the recorded source, so editing does not apply it again.

With Crash Zoom enabled, Zoom now or Volume up triggers a push. Return or
Volume down releases the hold. Push duration, amount and motion blur shape the
move. Tapping selects a target; Follow face uses the camera's face detections.
The recording retains the actual trigger, return timing and measured target
keys for editor rendering. Tracking depends on the reported camera features
and visible detail; it is not a guarantee of a locked subject.

Camera and zoom choices come from Android's reported capabilities. Switching
keeps the same recording and its encoded frame size. Frames are rotated to
that initial orientation; timestamps follow the recording clock.

Selfie preview is mirrored by default; saved footage is unmirrored. Change the
two switches independently in More settings before recording. Saved mirroring
is locked during a take, including while paused. Camera combinations and HDR
support depend on the phone and its drivers.

## Camera assistance

Follow face uses the camera's face detection and moves smoothly within the
available crop. Single-camera capture reserves a small amount of zoom at the
wide end; dual capture follows within each region's existing crop. Brief face
loss keeps the last framing. Panning a picture manually turns following off.
Following is unavailable when the active cameras do not report face detection.

Stabilize uses electronic or optical stabilization when the lens provides it.
For single-camera takes on other lenses, MyRec attaches an editable stabilization
effect to the affected part of the clip. Its correction appears in the editor and export.

Level horizon applies to rear cameras. It uses gravity, a rotation vector or a
filtered accelerometer, with scene edges refining small corrections. Without a
motion sensor it follows consistent scene edges and holds neutral framing when
it cannot establish an angle. Max angle selects a limit
from 0.5° to 10° in half-degree steps. Its fixed crop avoids changing zoom as the
phone tilts. Large tilts fade back toward normal framing. The angle panel previews
the crop before enabling the setting. Both Camera settings and Max angle let
you turn leveling on, including during a take. With only the selfie camera open,
the switch arms leveling for the next rear-camera view. Preview and saved footage use the same
framing. The guides and controls are not added to the movie.

## Use the light

The light is in Camera settings. Rear cameras use the hardware light when one
is available. The front camera uses a rounded screen border. Tap its bulb to
choose Thin, Medium, or Wide; White, Warm, Cool, or a custom color; and opacity
in 5% steps. These choices are remembered. Drag the border to adjust its thickness
continuously. Nonzero opacity raises window brightness to full; zero opacity
restores the previous brightness without closing the controls.

Custom opens a dark color sheet with Grid, Spectrum, and RGB Sliders tabs and
hexadecimal input. Changes update the light immediately; close the sheet to
return to the front-light controls. Color stays opaque because the light has
its own opacity setting.

Brightness returns to its previous setting when the light is switched off,
the camera closes, or only a rear view remains. Split view can use the screen
light while its front camera is visible. The border is not recorded.

## Record two views

Enable Use two cameras in Camera settings. The lower camera strip then shows
Swap and Layout. Layout offers rectangular or round picture in picture, side
by side, top/bottom, and diagonal layouts. First and Second choose among the
camera combinations. When Android lists no concurrent pairs, MyRec tries the
selected pair before recording, starting at the smallest common supported size
among 480p, 720p and 1080p. Both inputs must deliver matched frames. If the
driver refuses the pair, MyRec reopens one camera. You can still switch lenses
during a take. Layout changes become part of the saved composition.

Swap exchanges views. Start both fingers inside one picture to pan or zoom it;
crossing a divider keeps control of that picture. While Layout is
open, drag a split divider, reverse the diagonal, or move and resize the inset.
Rectangle and Circle share the inset's position and size; returning to Rectangle
restores its aspect ratio. Moving or resizing the inset keeps face following
enabled. Done hides the layout guides.

These gestures work during recording. More settings → Reset framing restores
the positions and crops while keeping both cameras and the take active. Pause
before enabling or disabling two-camera capture or changing its camera pair.
Inputs negotiate up to 1080p per camera and fall back together if needed. Output
keeps the chosen recording size.

## Record HDR

Choose SDR, HDR, or Software HDR under Camera settings → Recording color
before recording. Native HDR needs Android 13, an HLG camera output, HEVC Main10 encoding, and a compatible
10-bit EGL surface with `EGL_EXT_gl_colorspace_bt2020_hlg`. When the driver cannot
sample HDR camera textures directly, the compositor uses the camera's ten-bit
P010 buffers. This path retains framing, rotation, mirroring and camera layouts;
it also requires a supported P010 capture size. Camera settings checks graphics
and encoder support before closing a working SDR camera. Native HDR records
10-bit BT.2020 HLG. Live monitoring, the
editor and conversion to SDR use a gradual highlight shoulder from middle
grey to the nominal 1000-nit HLG peak.

Software HDR is offered when native HDR is unavailable and the selected
cameras support negative exposure compensation. It lowers auto-exposure
by about 0.85 stops to preserve highlights. A small GPU luminance map adjusts
the tone curve for bright and shaded parts of the picture. The map is smoothed
over time and limits the lift near black. Preview and recording use the same
map, and the saved video uses H.264 SDR.
Detail clipped by the sensor or camera pipeline cannot be recovered.
For single-camera SDR on lenses with exposure compensation, Protect highlights
in SDR lowers the requested exposure by about two-thirds of a stop. The file
retains its ordinary SDR format.

On a camera without a known color profile, the app compares registered SDR
and HLG frames in the background. Holding the camera still briefly on the
first switch helps it identify the primaries. A clear match can select
BT.709 or Display P3. If recording has already started, the result applies
the next time the camera opens. Profiles are stored per camera and Android
build. Confirmed input is converted to BT.2020 before encoding.
On OnePlus 15 firmware `B.R4T3.50a3663-29f0eba-29fce1c`, both cameras start with
their measured BT.709 input primaries and a luminance correction matched to
the SDR camera response. The correction retains a 1000-nit HLG peak and
compensates midtones for the camera's highlight-protection exposure bias.
Protect HDR highlights lowers auto-exposure by about two-thirds
of a stop on both cameras on this firmware. Dual HDR uses each camera's
setting. You can change it in Camera settings with the lens selected.

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

Tap the microphone name to open Recording volume. Gain ranges from −40 to
+12 dB in half-decibel steps, with Reset to 0. MyRec remembers gain for each
input and applies changes smoothly to the saved audio, including during a take.
The live meter reports dBFS, holds a clipping warning briefly after a peak, and
distinguishes missing samples from a quiet signal. Lowering gain cannot repair
distortion already introduced by the microphone.

Tap the input name inside Recording volume to choose a microphone. The list
shows physical recording routes and omits system-only routes. USB, Bluetooth,
wired, and phone inputs have distinct labels. Phone locations appear when
Android reports them; otherwise labels avoid guessing where a microphone sits.
The displayed name follows the input Android actually supplies.

Microphone priority remembers separate routes, including disconnected inputs
and routes sharing a product name. Before recording, the highest available
preferred input is requested. A manual choice stays selected until Stop and
does not reorder the saved priorities. Connecting a preferred input during a
take does not automatically switch away from the current microphone.

The audio engine stays open across camera switches and SDR/HDR setup changes.
During a take, a disconnected input triggers bounded attempts to obtain a
fallback; its name is announced only after Android confirms the route. Missing
audio or lost recording access pauses capture if recovery fails. Check the
input before resuming. A physical array may appear as one route, rather than
separate selectable elements. Accessory routing depends on Android and the
connected device; Bluetooth and USB disconnect acceptance still needs physical
accessory testing.

Chroma Key and Auto Background are available in the recording effects menu. The
camera preview processes each mask with its own picture on the effects worker;
background media and typed controls are retained in the take's editable recipe.
The original picture remains available. Background settings use the same inspector
and renderer as the editor; see [background effects](EDITING.md).
