package org.myrec.app

object AudioFilter {
    fun process(state: Long, pcm: FloatArray, effect: Effect, strength: Float) {
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
