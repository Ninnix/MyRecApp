package org.myrec.app

object AudioFilter {
    val direct = setOf("Low-cut filter", "Voice EQ", "Output gain")
    fun modern(effect: Effect) = effect.kind == "Output gain" || effect.values["dsp"] == "2"
    fun valid(effect: Effect): Boolean {
        val frequency = effect.number("frequency", if (effect.kind == "Low-cut filter") 80f else 1800f)
        val gain = effect.number("gain")
        return frequency.isFinite() && frequency in 20f..21600f && gain.isFinite() && gain in -24f..24f
    }
    fun effect(kind: String) = Effect(kind = kind).apply {
        if (kind in direct) {
            values["dsp"] = "2"
            values["gain"] = "0"
            values["frequency"] = if (kind == "Low-cut filter") "80" else "1800"
        }
    }

    fun process(state: Long, pcm: FloatArray, effect: Effect, strength: Float) {
        if (!effect.enabled || strength <= 0) return
        if (effect.kind in direct && modern(effect)) {
            Native.audioDirect(state, pcm, pcm.size / 2, when (effect.kind) {
                "Low-cut filter" -> 1; "Voice EQ" -> 2; else -> 0
            }, effect.number("frequency", if (effect.kind == "Low-cut filter") 80f else 1800f).toDouble(),
                effect.number("gain").toDouble())
            return
        }
        var hp = 0f; var eq = 0f; var frequency = 1800f
        var compressor = 0f; var gate = 0f; var limiter = 0f
        when (effect.kind) {
            "Low-cut filter" -> hp = effect.number("frequency", 80f)
            "Voice EQ" -> { eq = effect.number("gain", 3f) * strength; frequency = effect.number("frequency", 1800f) }
            "Compressor" -> compressor = strength
            "Noise gate" -> gate = effect.number("threshold", .015f) * strength
            "Limiter" -> limiter = strength
            else -> error("Unprepared audio effect: ${effect.kind}")
        }
        Native.audioProcess(state, pcm, pcm.size / 2, 1f, hp, eq, frequency, compressor, gate, limiter)
    }
}
