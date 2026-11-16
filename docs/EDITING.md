# Editing and export

The first action row is Start, Split, Tools, Export. Start marks a range; after
marking it becomes End, then Move end.

Tap the preview or Play to start and pause. Double-tap Play to audition around
the cursor. The transport buttons move between editing events and return to
the previous position. Review speed changes playback without changing the edit.

Pinch the preview continuously from 1× to 8× to inspect it, then drag to pan.
Reset returns to the fitted picture. Inspection changes the view without
changing the edit or export. Tools with draggable content offer Inspect/Edit;
Crop uses its Frame/Inspect tabs. Playback controls return to Play when a preview
finishes, including a draft that changes the timeline length.

## Work on the timeline

Drag to scrub; pinch to zoom. The scale control offers fixed spans and Whole
project. Tap a clip to select it, or mark the start and end of a range. Drag the
range handles to adjust it. The yellow Snap to cuts button turns snapping on or off.

The + button offers Insert video, Add video layer, and Add audio. Insert video
accepts videos and photos together. Review the numbered order and use the arrows
to reorder before importing. The batch makes room at the playhead as one undoable
edit; photos start at three seconds and honor their stored orientation. Layers
start on separate tracks and leave existing clips in place. Add audio gives the
imported sound a waveform of its own.

Select a clip or range, then Copy or Cut. Copies omit wholly unselected gaps;
a marked range preserves its timing. Paste can insert linked picture and sound
or overlay independent clips. At coincident cuts, choose the destination. Pasting
into a picture gap fills it before inserting extra time. Copies own their effect
settings and link identities. Independent music keeps its position when production
footage inserts or removes time. Close gap removes only time empty on every track.

Move lets you drag the selection in time or between tracks. Drag above the top
video lane to add a higher layer, or below the audio lanes to add an audio track.
Done keeps the move; Cancel restores it. Higher video tracks cover lower ones.
When videos overlap, Placement lets you arrange, crop, and reorder the pictures.
Unlink separates sound from picture. Select contiguous parts and Link to join
them. For unequal spans, confirm linking only their shared interval; the outside
fragments stay independent. Restore sync returns detached sound to its original
picture timing without moving the picture.

Tools → Record here opens the camera with insertion pinned to the playhead.
Tools → Voiceover here opens Voice recording. Choose a microphone, tap Record,
then Stop; Record more appends another pass without the pause. Play auditions the
take. Done inserts independent sound at the original playhead as one undoable
edit. Start over and Cancel confirm before discarding recorded audio. Backgrounding
or losing the selected microphone pauses recording. Original takes remain in the
project when either insertion is undone, and completed recordings can recover
after interruption before saving the edit.

Yellow flags are recording marks. Use bookmark navigation to jump between them.
They follow the source through splits, moves, copies, and speed changes.

Drag vertically on audio to change gain. Volume gives you gain, mute, reset,
and preview controls. Apply keeps the draft. Remove resets the selected gain
and can be undone. Fade in and Fade out have separate durations, exact time entry,
Off and edge auditions. With several selected audio parts, only the first start
and last end receive the fades. Splitting inside a fade keeps its slope.

Tools → Censor audio replaces sound with a sine, triangle or square beep. Adjust
Start/End, select the audio parts, and set frequency, level and envelope. Short,
Soft and Pulsed shape the beep while the original stays muted for the entire
range. Where selected parts overlap, the loudest carries one beep. Tap the
Censor audio chip to reopen it; Remove or Undo restores the sound.

Clip speed uses pitch-preserving audio preparation. Splits, seeks, preview and
export reuse the prepared source at that rate. Long sources can take time to
prepare; cancellation keeps the original unchanged. Extreme rates and complex
music still need broader listening checks against the iOS engine.

Undo and redo survive reopening and project switching. A new edit replaces the
redo branch. Normal new recordings remain present when you undo an earlier
edit; explicitly deleting a take is still undoable. Project indexes have five
recovery copies. If storage fails, Changes not saved · Retry keeps the edit in
memory and lets you save it again.

In Projects, open the card's menu for Original recordings. Review a source,
save its unchanged file through Android's picker, or insert it at the playhead.
Review original also selects source Start/End and adds that range to the end
as one undoable insertion. The selected range survives rotation. Done closes
review without changing the edit.
Originals remain available when absent from the timeline. Duplicate makes an
independent copy of sources, overlays, sidecars, and undo/redo history.

Save all originals to Files creates a separate folder in the Android folder
picker's destination. Each copy is checked against its source; Cancel or a
failed copy removes only that operation's new files. If a project cannot open,
Browse original files lists source files independently of editing metadata.
Saving those files does not reconstruct or change a timeline.

Create recovered project makes checked, independent copies of playable original
files and their color/mirror sidecars. It leaves the active edit unchanged and
adds a separate project card. Unreadable files stay with the original collection;
they can still be saved. A file with no readable picture is not made into a
synthetic black take.

Zip project includes the originals, every editing state, and Timeline.otio.
Use Projects → Import to restore a ZIP or import a video. Restore checks entry
paths and checksums. Missing or damaged media keeps its editing history; damaged
payloads are retained separately. In Original recordings, Relink original lets
you select the unchanged source. Missing overlay images have a relink action too.
MyRec verifies the bytes against a checksum recorded before the loss, then
updates the complete edit and capture history. A source lost before its first
checksum cannot be verified. Remove unavailable takes keeps the sources and
history available and removes the missing clips as one undoable edit.

Cancellation removes only that operation's unpublished files. Startup cleans
marked interrupted copies, restores and repairs; referenced history assets and
files whose project indexes cannot be checked are kept.

Unzip an archive and open Timeline.otio in an external editor for a source
conform with tracks, gaps, trims, speed, bookmarks, and simple dissolves.
MyRec grades, overlays, processing and custom transitions need recreation there.
The conform's JSON timing is tested; external-editor import still needs checks.
Audio fades and generated censoring remain in the MyRec project payload.
Archives currently limit metadata to 64 MB and keep 64 MB of free-space reserve.

## Add effects

Tools contains video and audio effects, transitions, crop, placement,
stabilization, text, images, LUTs, speed, and pause removal. Applied effects
appear beneath the timeline. Swipe that row to reach more of them, then tap an
effect to reopen it. Most numeric readouts accept exact values. Text and placement
can be dragged or pinched in the preview.

Crop includes 4:5, Frame/Inspect, and quarter turns. Original restores the source
shape. Dissolves overlap neighboring footage; solid-color and Still holds can
add time. Transition controls include split axis, divider and separate framing,
fade color, wipe direction and feathering, Hitchcock brightness order, Flash Cut
count and acceleration, Still dissolve and optical Defocus amount. Audio can
crossfade or favor either side of a split, with optional offset and blend time.
The union of picture and sound determines the required source handles. Range
edits keep the surviving transition clock. Older transitions keep their original
rendering until you choose Use full transition controls.

Stabilize offers Off, Light, Standard and Strong. Original/Stabilized compares
the same edit without changing the source. The inspector reports the actual
crop and explains when poor tracking or the crop limit reduces correction.
Light, Standard and Strong limit zoom to 1.045×, 1.10× and 1.18× respectively.
Smoothing follows source presentation timestamps, preserves intentional pans
and zooms, and resets at cuts or unreliable motion. Splitting or trimming an
already stabilized clip keeps its original analysis range and framing; speed
and freeze holds reuse the corresponding source correction.

Analysis uses frames up to 640 pixels on the longer side, runs before the first
stabilized preview, and is reused across levels and reopening. Completed cache
files are checksummed and tied to the source, mirror/color sidecars, range and
processing version. Analysis currently accepts at most 600,000 source frames.
Export cancellation interrupts preparation. Cancelling a new inspector draft
also stops its unfinished source work; completed analysis remains reusable.
Original comparison uses the same held source frame and does not save a bypass.
Long-take checkpoint/resume and
broader real-camera/HDR comparisons still need work.

Earlier saved stabilization edits keep their Strength slider and original
processing. Choose Use stabilization levels to convert a particular edit;
Cancel leaves it unchanged. New level-based edits require the updated app.

Kaleidoscope starts with two divisions and offers 2–9, including odd counts.
Drag the picture to pan its source and twist with two fingers to rotate it.
Drag the center or round handle to move or rotate the split. Fine adjustments
separates source pan/rotation from split center/rotation; Reset split and Center
source reset those controls independently. Its guide currently follows the fitted
canvas; mapping it through transformed layers and the live camera still needs work.

RGB Offset has separate red, green, and blue pads. Drag a cross, double-tap to
center it, or use its accessibility actions. A pad's outer ring corresponds to
12% of the picture's shorter side. Color Correction has Light/Color tabs,
Advanced light controls, linear-stop exposure, tone endpoints, white balance,
vibrance and hue. Tap a correction value to reset it; hold its label for help.

Effects keep their own settings on each selected clip. Adding an effect shares
its identity across the selection; splitting retains that identity and makes
independent copies of its controls. The applied list follows the playhead.
Animate adds source-time keys to the current clip. Existing keys remain editable
with Animate off. Static changes affect matching effect IDs in the selection.

Color Correction's Animate menu offers Edit A and Edit B. Adjust either endpoint,
then choose Timing for Linear, Ease in, Ease out, Ease in & out, Rise & hold, or
Rise, hold & return. The rise curves also have ramp shape and duration controls;
the return curve adds release. Done editing endpoints returns to normal preview.
Remove animation keeps the last edited endpoint as a static correction. Reset
correction resets that endpoint when animated, or all controls and keys otherwise.
Source clocks keep the animation stable through later cuts and speed changes.

Silent Tint offers five dyes and Tint/Tone processing. Three-Strip separates
saturation, red depth and warmth. Bleach Bypass has separate Strength and
Intensity. Day for Night separates darkness, blue and moonlight highlights.
Expressionism combines monochrome contrast, oval/circle vignette and tint.
Their controls, including choices and toggles, support source-time keys.

New Noir effects separate Contrast, Black crush, Grain and Blue shadows. Grain
uses the assembled presentation frame, including held footage; seeking back to
the same frame produces the same grain. Existing saved Noir effects retain their
original processing. The new controls and source keys use algorithm 4, so older
versions refuse them rather than silently changing the picture.

New 16mm effects provide Fine/Medium/Coarse grain, Softness, Gate weave,
Frame edge, and Color/B&W. Softness uses a resolution-scaled linear-light
Gaussian; grain and weave follow the assembled frame through cuts and holds.
The Grain size choice appears when Grain is above zero. Existing grain-only
16mm recipes retain their processing. CPU rendering of these textures can be
slow on older phones, especially at full export resolution.

Halation separates Glow, Halation red, Shadow lift, and Neutral/Warm/Cool flash
color. Bright source samples are isolated before downsampling, so small lights
remain visible between reduced-image centers. Three resolution-scaled Gaussian
blurs spread their light; the red fringe and shadow lift stay independent.
Flash color appears when Shadow lift is above zero. Intensity blends in linear
light, with exact zero bypass; active output uses the reference's bounded color
range, including on a half-float canvas. Its numeric and color-choice keys remain
anchored to the source through cuts and retiming. Existing Noir/16mm recipes keep
their older processing.

Video effects use fixed families with primary correction before creative looks.
Older project controls are converted on loading; there is one renderer and
inspector per effect. Color passes use linear light; HDR and explicit Log
retain half-float intermediates. Actual ten-bit media/export and an iOS GPU
comparison still need capable-device acceptance.

Tools → Video effects → Background offers Chroma Key and Auto Background.
Chroma Key supports green/blue border detection, manual color or picture sampling,
a clean plate, matte/original comparison, black/white clipping, edge softness,
shrink/grow, despill, optional person hints and light wrap. Auto Background uses
the bundled person model with guided edge refinement and portrait blur.

Both effects offer Solid, Image, Video, Blur original, Studio and Window backgrounds.
Image/video assets are copied into the project and travel with its archive. Fill/Fit,
position, zoom, blur, exposure and color matching share the normal effect inspector
and source-time animation. Background videos loop with a moving 200 ms overlap;
optional sound uses the same clock at −18 dB. Background motion continues during a
foreground hold; held-audio settings control its sound. Cuts, trims and speed changes
retain the effect's background clock. Source-bound matte windows make repeated and
backward seeks deterministic and reset on scene cuts.

Auto Background uses PPHumanSeg rather than Apple Vision. Saved footage currently
recomputes masks from its source; replay of the exact masks accepted by live preview
is still pending. Broad background blur uses area reduction before Gaussian filtering
to bound CPU and scratch memory, so it is not a pixel-identical Core Image result.

New Blur effects have a Blur radius control in pixels at 1080p and a separate
Intensity control. Gaussian filtering and intensity use linear light; the radius
scales with the picture, keeping preview and export softness consistent. New
Letterbox effects have separate Bar size and Intensity controls and work in the
recording preview. Zoom, Mirror and Rotation also work in the recording preview;
zero intensity restores the original picture in the editor.

New Widescreen 1953 effects use the reference ratios (2.39, 2.20, 1.85 and 1.33),
Black or Blur fill bars, and Blue, Amber or Neutral flares. Horizontal streaks
sample individual bright highlights before reduction. Frame decorations follow
picture geometry and texture; text and image annotations remain above them.
Existing Blur and Widescreen recipes retain their previous rendering. Their
inspectors offer an explicit upgrade to the new controls. Blur and Letterbox
are Android compatibility effects in Extra effects. Background matches YouRec's
Auto Background/Chroma Key choices; Auto Background starts with a blurred backdrop
and retains the detected person. Widescreen 1953 is in Camera & frame. Source-only Freeze/Strobe/Echo effects
remain available in the editor and are disabled in the recording library.

Tools → Select cover samples nine chronological choices from the assembled
project, selected range or selected clips, including picture underneath selected
audio. Update refreshes the choices. Opening a choice renders the full project
canvas with its looks and text; Save JPEG and Share JPEG use that immutable edit
snapshot. Rotation keeps the choices and selected frame. Like the reference,
the picker exports JPEGs; it does not replace a saved project-card thumbnail.

Audio filters run in the order shown. Select a container to change its values
or position. Newly added Low-cut filters use the reference second-order
Butterworth high-pass response. Voice EQ uses Q=1 and starts at 0 dB; its
frequency extends to 12 kHz. Output gain is an ordered ±24 dB stage with exact
neutral bypass and no internal clipping. EQ and gain have a centered 0 dB line;
the Low-cut fader follows a logarithmic 20–500 Hz scale. These stages retain
independent stereo state across decoder buffers. Existing saved Low-cut/EQ
stages retain their earlier processing; remove and add a stage to use the new
implementation. New direct DSP stages carry effect algorithm 3, so older app
versions refuse them rather than render a different sound. Neural effects prepare and cache audio before playback. Models
can change vocal character; compare the result before applying it.

Text opens a preview and a collapsible Duration picker. Choose 1–10 seconds,
15, 30, or 60; choices beyond the available footage are disabled. To end and
Start/End trimming keep exact timing. Looks, Font, Fill, Outline, Shadow,
Background, and Motion use separate tabs. The eight look cards show the entered
words with their actual fonts. Bundled title families include Atkinson Hyperlegible
Next, Barlow Condensed, Archivo Black, Source Serif 4, Fraunces, IBM Plex Mono,
and Caveat. Import TTF/OTF fonts or choose a device font.

Fill supports solid colors and twelve gradient presets with editable colors,
opacity and angle. Outline, shadow, block/per-line backgrounds, letter/line
spacing and alignment affect preview and export. Entrance and Exit each offer
None, Fade, Drift, Scale, and Wipe, with duration, direction and audition. Short
cues fit their animation ends around a readable hold. Source clocks preserve
motion across cuts. Save as channel style reuses appearance across projects;
outline and motion stay on the individual overlay. Imported fonts remain in
project history and ZIPs.

Color LUTs has frame-based look previews, Before/After, numeric animation
endpoints, favorite looks, imported LUTs and saved grades. Strength sits beside
its slider; Highlights has a reset. Exposure & white balance expands exposure,
relative warmth and tint controls. These adjustments precede the creative look
and remain active at zero Strength. The fourteen built-in looks include Natural,
Warm and Cool shadows. Imported standalone 1D and 3D `.cube` files belong to the
project, including history and archives. Choose the LUT input separately from
source interpretation. Recorded uses normal decoding; Apple Log and Apple Log 2
explicitly interpret unmanaged camera code values. Log viewing uses a highlight
shoulder and half-float working images. Automatic Log metadata recognition,
local highlight recovery and exact Core Image white-balance matching remain open.
New styled titles and animated/balanced grades carry effect algorithm 2, which
older builds refuse rather than silently discard their appearance or motion.

Pauses analyzes original-source RMS, then shows candidates with separate keep
before/after padding. Automatic mode estimates a threshold; Manual uses the
chosen dB level. Checked pauses are skipped without deleting their source
intervals. Apply saves candidates and decisions; reopening the tool lets you
keep them again. Linked picture and sound use the same cuts. Speech-probability
classification and steady-noise handling still need work.

Automatic subtitles analyze speech islands and recognize at most twelve
seconds at once. Review subtitles to correct phrases, regroup words, adjust their
vertical placement or copy the transcript. Corrections preserve surviving word
anchors; Apply makes one undoable edit. Recognition and word timing are separate:
word times are estimates within speech, not forced alignment. Recognition needs
the caption model; transcript review does not. Completed speech sections are
checkpointed. Retrying the same edited audio, range and language resumes them;
changed audio starts a new job. Cancellation waits for an active recognition
call to return.

Night fix runs before the grade and composition. Fast applies spatial and
chroma cleanup; Medium and Precise align three and nine source frames, rejecting
cuts, inconsistent motion and dissimilar pixels. Shadow brightness is a fixed
0–8-stop curve, with separate Noise reduction and Preserve detail controls.
Completed restorations are cached independently of those adjustments. Cancelling
stops within the current frame; a retry reuses completed frames. The Android
backend uses C11 local flow rather than Apple's Vision optical flow, so its
motion estimates can differ.

Freeze Frame inserts a hold at the playhead and resumes the following footage.
Choose duration, push-in, Clean or Print styling, and Cut, Continue or Fade-out
audio. Print adds restrained saturation, vignette and moving grain. Split and
copy preserve the held source frame and the progress through its push and fade.
Dolly Zoom separates a person from the background for a Stretch or Compress
move, using a refined person matte and a finite source history. Imported
Cinematic depth remains unsupported. Crash Zoom has push duration, amount,
optional return, hold, radial motion blur and target coordinates.

Undercranking offers 12/16/18 fps, 1–1.5× speed-up, Flicker, Gate weave and Mute
source audio. Animate Speed-up to draw a continuous ramp. Super 8 offers Fade,
coarse Grain, Weave, 18/24 fps, moving Light leaks and a rounded frame border.
Their cadence holds the complete rendered look; source phase survives trims
and seeks. Background sound remains separate from Undercranking's source mute.

Step Printing groups two to six actual source frames, including variable-rate
footage. Smear mixes their linear-light average with the first frame; Speed
can be 1× or 0.5× with linked picture and pitch-preserved sound. Animate can
author held source-time Speed keys. Picture, audio, seeking and timeline duration
share those rate spans, including through splits and pauses. Audio uses one
stretch operation across the rate plan rather than joining separately stretched
files at each speed change. Video-background sound uses the same clock.
Negative Strobe alternates positive and negative frames with selectable cycle
frequency and an optional timed burst. Its source clock survives cuts. In the
camera inspector, Try strobe previews a burst; recording starts a fresh phase.

Neural audio processes each original source before timeline speed, gain, fades
and censoring. Left and right channels have independent inference; exact dual
mono can reuse a prediction. Content-addressed stages and window checkpoints
are shared by cuts. Speech detail uses reflected 192,000-sample windows with
16,384-sample margins. Bundled RNNoise removes its initial model latency and
flushes the final partial frame. VoiceFixer uses a 44.1 kHz, 112,455-sample
context with reflected edges, centered vocoder output and 3,087-sample overlap.
Rate conversion is band limited. The ONNX networks use FP32 rather than the
reference's Core ML precision; DeepFilter still needs its continuous recurrent
state contract. Speed changes preserve pitch.

## Export

Export saves H.264 SDR or HEVC 10-bit HLG with AAC stereo audio to
`Movies/MyRec`, then offers playback and sharing. Resolution depends on the
output shape and encoder. HDR and explicit Log use linear half-float working
images. SDR output maps HDR highlights once per source layer; ordinary SDR
layers retain their brightness. Bounded creative effects use the reference's
working highlight shoulder. HDR output requires Main10 encoding, a ten-bit EGL
recording surface and actual ten-bit HDR decoder samples. Unsupported hardware
reports the missing capability. Source files stay unchanged. Broader HDR
material and numerical comparisons with iOS remain open.

An unchanged completed movie is reused after checking the edit, output settings,
source bytes, imported assets, orientation/color sidecars and movie checksum.
The movie's first and last frames, size, duration and audio are verified before
publication. Changing or damaging a source/output prevents reuse. A failed or
cancelled replacement keeps the previous completed movie; a verified replacement
retires it. The completed render remains available if saving to the gallery fails.
On Android 9, saving to Movies requires the system storage permission.

Export and inference run while the app is open. A background export service and
resuming unfinished picture sections remain open.
