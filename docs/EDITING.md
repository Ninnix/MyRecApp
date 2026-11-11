# Editing and export

Tap the preview or Play to toggle playback. Double-tap Play to audition around
the cursor. Transport controls jump between editing events and return to the
previous position. Playback speed can be changed independently of clip speed.

## Timeline

Drag to scrub and pinch to zoom. The scale control includes fixed spans and
Whole project. Tap a clip to select it, or mark start/end to select a range.
Drag range boundaries to adjust them. The yellow magnet toggles snapping.

The + button offers Insert video, Add video layer and Add audio. Inserting video
makes room at the playhead. Layers start at the playhead on separate tracks,
keeping existing clips and transitions in place. Add audio opens a sheet with
Choose audio from Files; imported sound gets its own waveform and supports
Move, Volume, splitting, removal and undo.

Select a clip or range to reveal Copy and Delete beneath the timeline. Copy
shows Paste · Inserts time; scrub to the destination and tap Paste. The clipboard
stays available for repeated pastes until dismissed. Copied effects and media
ranges remain independent of their originals.

Use Move to drag a selected section horizontally or between tracks. Drag above
the top video lane to create a higher layer, or below the audio lanes for another
audio track. Done commits the move; Cancel restores it. Higher video tracks
appear above lower ones. Overlapping video exposes Placement · default for
arranging and cropping each layer separately, with Bring to front and Send to back.

Recording marks appear as yellow timeline flags. The bookmark controls jump
between them; marks follow their source sections through splitting, moving,
copying and speed changes.

Split, copy, paste, delete, undo and redo operate on actual clips and source
ranges. Move allows repositioning and track changes with Done or Cancel. Unlink
separates audio; Link restores joint selection for matching parts.

Drag vertically on audio to change gain. Volume provides precise gain, mute,
reset, preview and removal. Changes remain drafts until Apply; removal resets
the selected section's gain as one undoable edit.

## Effects

Tools opens ordered video/audio effects, transitions, crop, placement,
stabilization, text, images, LUTs, speed and pause removal. Applied effects appear
as editable buttons beneath the timeline. Swipe the button row horizontally to
reach additional effects. Drag or pinch text and placement in preview.
Tap a numeric readout for exact parameter entry. Crop includes 4:5, Frame/Inspect
and independent quarter turns. Original restores the source video shape.

Kaleidoscope starts with four mirrored sections meeting at the frame center.
Two segments give a two-way mirror; larger counts use angular sections.
Zoom and Rotation adjust the framing. Center X/Y place the junction where the
mirrored sections meet. RGB Offset separates green and magenta edges and can be
combined with Kaleidoscope in either order.

Audio containers show filter order and strength. Select a container for its
parameters or to move it in the chain. Neural restoration and speech detail prepare audio before
playback; the status indicator reports that work.

Edits preserve the source files. Split sections own independent effect data.
Transitions overlap their sources and shorten the composition accordingly;
range operations preserve surviving transition phases.

## Export

Export writes H.264 video and AAC stereo audio into `Movies/YouRec`, followed by
playback and sharing actions. Resolution choices depend on the selected output
and device encoder. Export and inference currently run while the app is open;
there is no background export service.
