package org.myrec.app

import kotlin.math.min

object OriginalRange {
    fun clip(source: Clip, start: Double, end: Double, fps: Int): Clip {
        require(source.frozen == null && fps in 1..240 && start.isFinite() && end.isFinite() &&
            start >= source.sourceIn && end <= source.sourceOut && end > start && end-start >= min(1.0/fps,source.sourceOut-source.sourceIn)-.0000001)
        val original = source.duplicate().apply { this.start = 0.0 }
        val edit = Editor(Project(fps = fps,clips = mutableListOf(original)))
        edit.range = original.timelineOffset(start)..original.timelineOffset(end)
        edit.copySelection()
        return edit.clipboard.single().apply { sourceIn = start; sourceOut = end }
    }
}
