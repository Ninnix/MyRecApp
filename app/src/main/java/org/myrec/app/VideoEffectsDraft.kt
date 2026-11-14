package org.myrec.app

class VideoEffectsDraft(source: Editor, private val kinds: Set<String>) {
    private val original = source.project.duplicate()
    private val edit = Editor(original.duplicate()).apply {
        cursor = source.cursor; range = source.range; selected.addAll(source.selected)
    }
    private val ids = edit.isolateVideoScope()
    val chain = edit.project.clips.filter { it.id in ids }.flatMap { it.effects }
        .filter { it.kind in kinds }.distinctBy { it.id }.map { it.copyEffect() }.toMutableList()
    var endpoint: Boolean? = null
        private set
    private var lastEndpoint = false
    private var previous = chain.associate { it.id to it.copyEffect() }

    init { edit.beginBatch() }

    val clips get() = edit.project.clips.filter { it.id in ids }
    val project get() = edit.project

    fun clip(at: Double) = clips.firstOrNull { it.contains(at) }
        ?: clips.minByOrNull { minOf(kotlin.math.abs(at - it.start), kotlin.math.abs(at - it.end)) }

    private fun entries(id: String) = clips.flatMap { c ->
        c.effects.filter { it.id == id }.map { c to it }
    }

    fun effect(id: String, at: Double): Effect? = clip(at)?.effects?.firstOrNull { it.id == id }
        ?: entries(id).firstOrNull()?.second

    fun visible(at: Double): Set<String> = clip(at)?.let { c ->
        val local = (at - c.start).coerceIn(0.0, (c.duration - .000001).coerceAtLeast(0.0))
        c.effects.filter { it.kind in kinds && local >= it.from && local < it.to }.map { it.id }.toSet()
    } ?: emptySet()

    fun flush() {
        val current = chain.associateBy { it.id }
        val removed = previous.keys - current.keys
        val added = chain.filter { it.id !in previous }
        val changed = chain.filter { previous[it.id]?.let { old -> old != it } == true }
        if (removed.isEmpty() && added.isEmpty() && changed.isEmpty()) return
        val states = clips.associate { c ->
            val effects = c.effects.filter { it.kind in kinds && it.id !in removed }.map { it.copyEffect() }.toMutableList()
            for (fx in added) effects.add(fx.copyEffect().apply {
                from = 0.0; to = c.duration
                if (kind == "Dolly Zoom") values.putIfAbsent("origin", c.sourceIn.toString())
            })
            for (next in changed) {
                val before = previous.getValue(next.id)
                for (fx in effects.filter { it.id == next.id }) {
                    if (next.enabled != before.enabled) fx.enabled = next.enabled
                    for (key in before.values.keys + next.values.keys) if (before.values[key] != next.values[key]) {
                        next.values[key]?.let { fx.values[key] = it } ?: fx.values.remove(key)
                    }
                    for (p in next.parameters) if (p != before.parameter(p.id)) {
                        val index = fx.parameters.indexOfFirst { it.id == p.id }
                        if (index >= 0) fx.parameters[index] = p.duplicate()
                    }
                }
            }
            c.id to effects
        }
        edit.replaceVideoStates(states, kinds)
        previous = chain.associate { it.id to it.copyEffect() }
    }

    fun adopt(display: Effect, at: Double) {
        val actual = effect(display.id, at)?.copyEffect() ?: return
        display.values.clear(); display.values.putAll(actual.values)
        display.parameters.clear(); display.parameters.addAll(actual.parameters)
        display.enabled = actual.enabled
        display.motion = actual.motion?.duplicate()
        previous = previous + (display.id to display.copyEffect())
    }

    fun number(display: Effect, key: String, at: Double, fallback: Float = 0f): Float {
        val c = clip(at)
        val actual = effect(display.id, at) ?: display
        val selected = endpoint
        val pose = actual.motion?.let { if (selected == true) it.b else if (selected == false) it.a else null }
        return pose?.get(actual.parameter(key)?.id ?: key)?.toFloat()
            ?: actual.numberAt(key, c?.sourceTime(at) ?: 0.0, fallback)
    }

    fun editNumber(display: Effect, key: String, value: Float, at: Double, animate: Boolean) {
        flush()
        val active = clip(at)
        val time = active?.sourceTime(at) ?: 0.0
        val states = clips.associate { c -> c.id to c.effects.filter { it.kind in kinds }.map { it.copyEffect() } }
        for (c in clips) for (fx in states.getValue(c.id).filter { it.id == display.id }) {
            if (animate && c.id != active?.id) continue
            val motion = fx.motion
            if (motion != null) {
                val id = fx.parameter(key)?.id ?: key
                require(fx.parameter(id)?.kind?.accepts(EffectValue.Number(value.toDouble())) == true)
                fx.motion = if (endpoint ?: lastEndpoint) motion.copy(b = motion.b + (id to value.toDouble()))
                    else motion.copy(a = motion.a + (id to value.toDouble()))
            } else fx.editNumber(key, value, time, c.sourceIn, animate)
        }
        edit.replaceVideoStates(states, kinds)
        adopt(display, at)
    }

    fun value(display: Effect, key: String, at: Double): EffectValue? {
        val actual = effect(display.id, at) ?: display
        return actual.parameter(key)?.at(clip(at)?.sourceTime(at) ?: 0.0)
    }

    fun editValue(display: Effect, key: String, value: EffectValue, at: Double, animate: Boolean) {
        flush()
        val active = clip(at)
        val time = active?.sourceTime(at) ?: 0.0
        for ((c, fx) in entries(display.id)) {
            if (animate && c.id != active?.id) continue
            val p = fx.parameter(key) ?: continue
            require(p.kind.accepts(value))
            if (p.at(time) == value) continue
            if (animate && p.keyframes.isEmpty() && time > c.sourceIn + .001) p.setKeyframe(c.sourceIn, p.value)
            if (animate || p.keyframes.isNotEmpty()) p.setKeyframe(time, value) else p.value = value
        }
        adopt(display, at)
    }

    fun reset(display: Effect, at: Double) {
        flush()
        for ((_, fx) in entries(display.id)) {
            val neutral = EffectContracts.parameters(fx.kind)
            val pose = neutral.associate { it.id to (it.value as EffectValue.Number).value }
            val motion = fx.motion
            if (motion != null) fx.motion = if (endpoint ?: lastEndpoint) motion.copy(b = pose) else motion.copy(a = pose)
            else {
                fx.values.clear(); fx.parameters.clear(); fx.parameters.addAll(neutral)
            }
        }
        adopt(display, at)
    }

    private fun bounds(): Pair<Double, Double> {
        val start = clips.minOfOrNull { it.start } ?: 0.0
        return start to ((clips.maxOfOrNull { it.end } ?: start) - start)
    }

    fun selectEndpoint(display: Effect, b: Boolean, at: Double): Double {
        flush()
        val (start, length) = bounds()
        require(length > 0 && display.kind == "Color Correction")
        for ((c, fx) in entries(display.id)) if (fx.motion == null) {
            fun pose(t: Double) = fx.parameters.associate { it.id to (it.at(t) as EffectValue.Number).value }
            fx.motion = EffectMotion(pose(c.sourceIn), pose(c.sourceOut), duration = length,
                frame = 1.0 / project.fps).anchored(c, c.start - start)
            fx.parameters.forEach { it.keyframes.clear() }
        }
        endpoint = b; lastEndpoint = b
        adopt(display, at)
        val motion = effect(display.id, at)?.motion ?: return at
        return start + motion.timing.endpoint(b, length, motion.frame)
    }

    fun timing(display: Effect, timing: EffectTiming, at: Double): Double {
        flush()
        val (start, length) = bounds()
        for ((c, fx) in entries(display.id)) fx.motion?.let {
            fx.motion = it.copy(timing = timing, duration = length).anchored(c, c.start - start)
        }
        adopt(display, at)
        val motion = effect(display.id, at)?.motion ?: return at
        return endpoint?.let { start + timing.endpoint(it, length, motion.frame) } ?: at
    }

    fun endEndpoint() { endpoint = null }

    fun removeMotion(display: Effect, at: Double) {
        flush()
        for ((_, fx) in entries(display.id)) fx.motion?.let { motion ->
            val pose = if (endpoint ?: lastEndpoint) motion.b else motion.a
            for (p in fx.parameters) { p.value = EffectValue.Number(pose.getValue(p.id)); p.keyframes.clear() }
            fx.motion = null
        }
        endpoint = null
        adopt(display, at)
    }

    fun snapshot(bypass: Boolean = false): Project {
        flush()
        return project.duplicate().also { p ->
            if (bypass) p.clips.filter { it.id in ids }.forEach { c -> c.effects.removeAll { it.kind in kinds } }
        }
    }

    fun commit(target: Editor) { target.commitDraft(original, snapshot()) }
}
