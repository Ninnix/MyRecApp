package org.myrec.app

object EffectOrder {
    private fun family(kind: String) = when (kind) {
        "Chroma Key", "Auto Background" -> 0
        "Dolly Zoom", "Crash Zoom", "Kaleidoscope", "Zoom", "Crop", "Mirror", "Rotation", "Stabilize", "Placement" -> 1
        "Quantization" -> 3
        "Halation", "16mm", "Super 8", "Film grain", "Vignette" -> 4
        "Widescreen 1953", "Letterbox" -> 5
        "Freeze Frame", "Step Printing", "Undercranking", "Freeze", "Strobe", "Echo" -> 6
        else -> 2
    }

    fun ordered(effects: List<Effect>): List<Effect> {
        return effects.sortedWith(compareBy<Effect> { if (it.kind == "Night fix") -1 else family(it.kind) }
            .thenBy { if (it.kind == "Kaleidoscope") 1 else 0 }
            .thenBy { if (it.kind == "Color Correction") 0 else 1 })
    }
}
