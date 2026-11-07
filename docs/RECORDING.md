# Recording

Open Projects, then Record or Continue recording. A new recording appends to the
current project unless you choose Start new project. The red button starts and
stops capture. Pause keeps the recording session and project together.

In portrait, scissors sit to the left of Record. They open the editor, stopping
an active recording first. The stacked icon opens Projects while idle; that
position holds Pause during recording.

## Cameras and focus

The camera-with-arrows button switches front/rear. The lens selector and its
chevron open the available camera choices. Zoom presets use the chosen duration;
a quick second tap changes zoom immediately.

Tap AF to lock or release focus. AF L and the yellow indicator show a requested
lock. The app uses a supported autofocus mode and checks Camera2's lock state
in device tests. Fixed-focus cameras are labelled Fixed. Lenses, zoom ranges
and focus behavior come from camera capabilities, not manufacturer checks.

## Microphones

Tap the microphone name to choose an input. The list includes physical recording
routes and excludes system-only tuner, telephony and echo-reference inputs.
Labels distinguish USB, Bluetooth, wired and phone inputs. Phone locations are
shown when reported; otherwise distinct route labels avoid guessing which hole
or microphone array a route represents.

Switching cameras keeps the same recording. Camera frames are rotated into the
encoder's initial orientation before encoding, so different front/rear sensor
orientations do not turn part of the file upside down. Source resolution can
change while the encoded resolution stays fixed. Frame timestamps use the
recording clock to preserve audio/video timing.

Microphone priority lets you move an input to the top of the preferred list.
Separate routes with the same product name retain separate preference keys.
Before recording, the highest available preferred input is selected.

Disconnecting the selected microphone or losing recording access pauses
capture. Android and the accessory determine which routes can be selected or
changed during recording. A physical microphone array may be exposed as one
selectable route rather than individual elements.

Camera effects are non-destructive and remain editable after capture.
