# Editing and export

Tap the preview or Play to start and pause. Double-tap Play to audition around
the cursor. The transport buttons move between editing events and return to
the previous position. Review speed changes playback without changing the edit.

## Work on the timeline

Drag to scrub; pinch to zoom. The scale control offers fixed spans and Whole
project. Tap a clip to select it, or mark the start and end of a range. Drag the
range handles to adjust it. The yellow magnet turns snapping on or off.

The + button offers Insert video, Add video layer, and Add audio. Insert video
makes room at the playhead. Layers start there on separate tracks and leave
existing clips in place. Add audio opens the system picker, copies the sound
into the project media, and gives it a waveform of its own.

Select a clip or range, then Copy. Paste · Inserts time appears beneath the
timeline; scrub to a destination and tap it. You can paste repeatedly until you
dismiss the clipboard. Copies own their effect settings.

Move lets you drag the selection in time or between tracks. Drag above the top
video lane to add a higher layer, or below the audio lanes to add an audio track.
Done keeps the move; Cancel restores it. Higher video tracks cover lower ones.
When videos overlap, Placement lets you arrange, crop, and reorder the pictures.
Unlink separates sound from picture. Link joins matching parts again.

Yellow flags are recording marks. Use bookmark navigation to jump between them.
They follow the source through splits, moves, copies, and speed changes.

Drag vertically on audio to change gain. Volume gives you gain, mute, reset,
and preview controls. Apply keeps the draft. Remove resets the selected gain
and can be undone. Independent volume fades are still missing.

Undo and redo are kept in memory. Reopening a project or leaving it for another
project clears that history. Source files remain on disk; a portable project
archive and a complete original-recordings browser are still missing.

## Add effects

Tools contains video and audio effects, transitions, crop, placement,
stabilization, text, images, LUTs, speed, and pause removal. Applied effects
appear beneath the timeline. Swipe that row to reach more of them, then tap an
effect to reopen it. Numeric readouts accept exact values. Text and placement
can be dragged or pinched in the preview.

Crop includes 4:5, Frame/Inspect, and quarter turns. Original restores the source
shape. Transitions overlap neighboring footage and shorten the composition;
range edits keep surviving portions of a transition.

Kaleidoscope starts with four mirrored sections. Two gives a two-way mirror;
larger counts make angular patterns. Zoom and Rotation change the framing;
Center X/Y move the junction. RGB Offset separates green and magenta edges.
These controls and algorithms still differ from the released iOS effects.

Audio filters run in the order shown. Select a container to change its values
or position. Neural effects prepare and cache audio before playback. Models
can change vocal character; compare the result before applying it.

Pause removal currently detects quiet spans and deletes them as one undoable
edit. It has no candidate-by-candidate keep/cut review or independent padding.
Speed changes also change audio pitch. Captions use fixed six-second recognition
chunks and can be corrected as text; estimated word timing and regrouping are
not implemented.

## Export

Export saves H.264 video with AAC stereo audio to `Movies/MyRec`, then offers
playback and sharing. Resolution depends on the output shape and encoder.
HDR sources are converted to SDR for editing and export; their original files
remain HDR. There is no Log editing path yet.

Export and inference run while the app is open. A background export service,
checksum receipts, and reuse of an unchanged completed export are still missing.
