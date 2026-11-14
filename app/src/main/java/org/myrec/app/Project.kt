package org.myrec.app

import java.util.UUID
import kotlin.math.*

fun uid() = UUID.randomUUID().toString()

fun timeLabel(seconds: Double, precise: Boolean = true): String {
    val t = max(0.0, seconds)
    val m = t.toInt() / 60
    return if (precise) java.lang.String.format(java.util.Locale.US, "%d:%05.2f", m, t % 60)
    else "%d:%02d".format(m, t.toInt() % 60)
}

data class Effect(
    val id: String = uid(),
    var kind: String,
    var from: Double = 0.0,
    var to: Double = Double.MAX_VALUE,
    var enabled: Boolean = true,
    val values: MutableMap<String, String> = mutableMapOf(),
    val parameters: MutableList<EffectParameter> = EffectContracts.parameters(kind),
    var motion: EffectMotion? = null,
    var subtitle: SubtitlePhrase? = null,
    var phase: EffectPhase? = null,
) {
    fun parameter(key: String) = parameters.firstOrNull { it.id == key }
        ?: if (key == "strength") parameters.firstOrNull { it.id == "intensity" } else null

    fun number(key: String, fallback: Float = 0f) =
        (parameter(key)?.value as? EffectValue.Number)?.value?.toFloat() ?: values[key]?.toFloatOrNull() ?: fallback

    fun numberAt(key: String, source: Double, fallback: Float = 0f) =
        motion?.pose(source)?.get(parameter(key)?.id ?: key)?.toFloat()
            ?: (parameter(key)?.at(source) as? EffectValue.Number)?.value?.toFloat() ?: number(key, fallback)

    fun setNumber(key: String, value: Float, source: Double? = null) {
        val p = parameter(key)
        if (p == null) values[key] = value.toString()
        else {
            val next = EffectValue.Number(value.toDouble())
            require(p.kind.accepts(next))
            if (source != null && p.keyframes.isNotEmpty()) p.setKeyframe(source, next) else p.value = next
        }
    }

    fun editNumber(key: String, value: Float, source: Double, begin: Double, animate: Boolean) {
        val p = parameter(key) ?: return setNumber(key, value)
        val next = EffectValue.Number(value.toDouble())
        require(p.kind.accepts(next))
        val prior = (p.at(source) as? EffectValue.Number)?.value
        if (prior != null && abs(prior - value) <= .000001) return
        if (animate && p.keyframes.isEmpty() && source > begin + .001) p.setKeyframe(begin, p.value)
        if (animate || p.keyframes.isNotEmpty()) p.setKeyframe(source, next) else p.value = next
    }

    fun sampled(source: Double): Effect {
        if (motion == null && parameters.none { it.keyframes.isNotEmpty() }) return this
        val pose = motion?.pose(source)
        return copy(motion = null, parameters = parameters.map {
            it.copy(value = pose?.get(it.id)?.let { n -> EffectValue.Number(n) } ?: it.at(source), keyframes = mutableListOf())
        }.toMutableList())
    }

    fun copyEffect(newId: Boolean = false) =
        copy(id = if (newId) uid() else id, values = values.toMutableMap(),
            parameters = parameters.map { it.duplicate() }.toMutableList(), motion = motion?.duplicate(),
            phase = phase?.copy(clock = phase!!.clock.toList()))

    fun active(local: Double) = enabled && local >= from && local < to

    fun strength(local: Double, duration: Double, fallback: Float = 1f): Float {
        if (parameters.isNotEmpty()) return number("intensity", fallback)
        val end = min(to, duration)
        val ramp = min(0.5, max(0.001, (end - from) / 4))
        val fadeIn = ((local - from) / ramp).toFloat().coerceIn(0f, 1f)
        val fadeOut = ((end - local) / ramp).toFloat().coerceIn(0f, 1f)
        return number("strength", fallback) *
            when (number("animate").toInt()) {
                1 -> fadeIn
                2 -> fadeOut
                3 -> min(fadeIn, fadeOut)
                else -> 1f
            }
    }
}

data class Clip(
    val id: String = uid(),
    val path: String,
    var sourceIn: Double = 0.0,
    var sourceOut: Double,
    var start: Double = 0.0,
    var track: Int = 0,
    var rate: Double = 1.0,
    var video: Boolean = true,
    var audio: Boolean = true,
    var linked: Boolean = true,
    var gainDb: Float = 0f,
    val effects: MutableList<Effect> = mutableListOf(),
    var group: String = id,
    val marks: MutableList<Double> = mutableListOf(),
    var fadeIn: AudioEdgeFade? = null,
    var fadeOut: AudioEdgeFade? = null,
    val censors: MutableList<AudioCensorRegion> = mutableListOf(),
    var pauses: PauseReview? = null,
    var frozen: FrozenFrame? = null,
) {
    private var rateState: RateState? = null
    private var spans = emptyList<PlaybackSpan>()
    val playbackSpans: List<PlaybackSpan>
        get() {
            val state = RateState(rate, sourceIn, sourceOut, pauses, effects.filter {
                it.kind == "Step Printing" || it.kind == "Undercranking"
            }.map { it.copyEffect() })
            if (state != rateState) { spans = computePlaybackSpans(); rateState = state }
            return spans
        }
    val effectiveRate get() = rateAt(sourceIn)
    val duration get() = frozen?.duration ?: playbackSpans.sumOf { it.duration }

    val end
        get() = start + duration

    fun sourceTime(t: Double): Double {
        frozen?.let { return it.sourceTime }
        var remaining = (t - start).coerceIn(0.0, duration)
        for (span in playbackSpans) {
            if (remaining < span.duration - .0000001) return span.start + remaining * span.rate
            remaining -= span.duration
        }
        return sourceOut
    }

    fun contains(t: Double) = t >= start && t < end

    fun duplicate(newId: Boolean = false, keepGroup: Boolean = false): Clip {
        val next = if (newId) uid() else id
        return copy(
            id = next,
            group = if (newId && !keepGroup) next else group,
            effects = effects.map { it.copyEffect(newId) }.toMutableList(),
            censors = censors.toMutableList(),
            marks = marks.toMutableList(),
        )
    }
}

data class Transition(
    val id: String = uid(),
    var at: Double,
    var kind: String = "Dissolve",
    var duration: Double = 0.5,
    var amount: Float = 0.75f,
    var hold: Double = 0.0,
    var leftId: String? = null,
    var rightId: String? = null,
    var overlap: Double = 0.0,
    var angle: Float = 0f,
    var soft: Boolean = true,
    var phaseFrom: Float = 0f,
    var phaseTo: Float = 1f,
) {
    val begin
        get() = at - (duration + hold) / 2

    val end
        get() = at + (duration + hold) / 2

    private val midpoint
        get() =
            if (phaseTo - phaseFrom < .000001f) .5
            else ((.5 - phaseFrom) / (phaseTo - phaseFrom)).coerceIn(0.0, 1.0)

    val cut
        get() = begin + duration * midpoint + hold / 2

    fun progress(time: Double): Float {
        if (phaseTo - phaseFrom < .000001f) return phaseFrom
        val elapsed = (time - begin).coerceIn(0.0, duration + hold)
        val first = duration * midpoint
        val blend =
            if (elapsed <= first) elapsed
            else if (elapsed <= first + hold) first else elapsed - hold
        return (phaseFrom + (phaseTo - phaseFrom) * (blend / max(.000001, duration)))
            .toFloat()
            .coerceIn(phaseFrom, phaseTo)
    }

    fun slice(from: Double, to: Double): Transition? {
        val a = max(begin, from)
        val b = min(end, to)
        if (b - a < .000001) return null
        val startHold = begin + duration * midpoint
        val keptHold = max(0.0, min(b, startHold + hold) - max(a, startHold))
        return copy(
            id = uid(),
            at = (a + b) / 2,
            duration = b - a - keptHold,
            hold = keptHold,
            phaseFrom = progress(a),
            phaseTo = progress(b),
        )
    }
}

private fun samePart(a: Clip, b: Clip) =
    a.path == b.path &&
        abs(a.start - b.start) < .001 &&
        abs(a.sourceIn - b.sourceIn) < .001 &&
        abs(a.sourceOut - b.sourceOut) < .001

data class Project(
    val id: String = uid(),
    var name: String = "New project",
    val clips: MutableList<Clip> = mutableListOf(),
    val transitions: MutableList<Transition> = mutableListOf(),
    var width: Int = 1920,
    var height: Int = 1080,
    var fps: Int = 30,
    var captureCount: Int = 0,
) {
    val duration
        get() = clips.maxOfOrNull { it.end } ?: 0.0

    fun duplicate() =
        copy(
            clips = clips.map { it.duplicate() }.toMutableList(),
            transitions = transitions.map { it.copy() }.toMutableList(),
        )

    fun active(t: Double) = clips.filter { it.video && it.contains(t) }.sortedBy { it.track }

    fun transitionSource(id: String?, time: Double): Clip? {
        val reference = clips.firstOrNull { it.id == id } ?: return null
        val family = clips.filter { it.video && it.group == reference.group }
        return family.firstOrNull { it.contains(time) }
            ?: family.minByOrNull {
                if (time < it.start) it.start - time else max(0.0, time - it.end)
            }
    }

    fun sourceSpan(reference: Clip): ClosedFloatingPointRange<Double> {
        var from = reference.start
        var to = reference.end
        var changed: Boolean
        do {
            changed = false
            for (clip in clips) if (
                clip.group == reference.group &&
                    clip.video == reference.video &&
                    clip.start <= to + .000001 &&
                    clip.end >= from - .000001
            ) {
                val a = min(from, clip.start)
                val b = max(to, clip.end)
                if (a != from || b != to) {
                    from = a
                    to = b
                    changed = true
                }
            }
        } while (changed)
        return from..to
    }

    fun events(): List<Double> =
        (listOf(0.0, duration) + markers() +
                clips.flatMap { c ->
                    listOf(c.start, c.end) +
                        c.effects.flatMap {
                            listOf(c.start + it.from, c.start + min(c.duration, it.to))
                        }
                } +
                transitions.flatMap { listOf(it.begin, it.cut, it.end) })
            .map { it.coerceIn(0.0, duration) }
            .distinct()
            .sorted()

    fun markers(): List<Double> = clips.flatMap { clip ->
        clip.marks.filter { source -> clip.frozen == null && clip.keptIntervals().any { source >= it.start && source < it.end } }
            .map { clip.start + clip.timelineOffset(it) }
    }.distinct().sorted()
}

class EditRevision(val project: Project, var file: String? = null)

data class EditSession(
    val project: Project,
    val undo: List<EditRevision> = emptyList(),
    val redo: List<EditRevision> = emptyList(),
    val captures: List<Clip> = emptyList(),
    val originals: List<Clip> = emptyList(),
)

class Editor(var project: Project = Project()) {
    var cursor = 0.0
    var mark: Double? = null
    var range: ClosedFloatingPointRange<Double>? = null
    val selected = linkedSetOf<String>()
    var clipboard = listOf<Clip>()
    private var clipboardTransitions = listOf<Transition>()
    private val undo = ArrayDeque<EditRevision>()
    private val redo = ArrayDeque<EditRevision>()
    private val captures = mutableListOf<Clip>()
    private val originals = linkedMapOf<String, Clip>()
    private var historyId = project.id

    init { project.clips.forEach(::remember) }

    private fun remember(clip: Clip) {
        val old = originals[clip.path]
        if (old == null || clip.sourceOut > old.sourceOut)
            originals[clip.path] = clip.duplicate().apply { start = 0.0 }
    }

    fun session(): EditSession {
        // Explicit fixture/import callers may replace the public project value.
        if (historyId != project.id) resetHistory()
        project.clips.forEach(::remember)
        return EditSession(
            (batch ?: project).duplicate(), undo.toList(), redo.toList(),
            captures.map { it.duplicate() }, originals.values.map { it.duplicate() },
        )
    }

    private fun resetHistory() {
        undo.clear()
        redo.clear()
        captures.clear()
        originals.clear()
        historyId = project.id
        project.clips.forEach(::remember)
    }

    private fun materialize(saved: Project): Project = saved.duplicate().also { value ->
        value.name = project.name
        for (capture in captures.drop(value.captureCount)) {
            val clip = capture.duplicate()
            clip.start = value.duration
            value.clips.add(clip)
        }
        value.captureCount = captures.size
    }

    fun appendRecording(clip: Clip) {
        check(batch == null) { "Finish the current edit before recording" }
        clip.start = project.duration
        captures.add(clip.duplicate())
        project.captureCount = captures.size
        project.clips.add(clip)
        remember(clip)
        clearSelection()
        cursor = clip.start
        onChange()
    }
    private var batch: Project? = null
    var onChange: () -> Unit = {}
    val canUndo
        get() = undo.isNotEmpty()

    val canRedo
        get() = redo.isNotEmpty()

    var snap = true
    var matchLoudness = true
    var linked = true
    var scale = 10.0
    var wholeProject = false
    var playbackRate = 1f

    fun load(project: Project, saved: EditSession? = null) {
        this.project = project
        resetHistory()
        if (saved != null) {
            require(saved.project.id == project.id)
            undo.addAll(saved.undo)
            redo.addAll(saved.redo)
            captures.addAll(saved.captures.map { it.duplicate() })
            originals.clear()
            saved.originals.forEach(::remember)
            project.clips.forEach(::remember)
            this.project = materialize(project)
        }
        batch = null
        clearSelection()
        cursor = 0.0
        wholeProject = false
        onChange()
    }

    fun change(block: () -> Unit) {
        val before = if (batch == null) project.duplicate() else null
        try { block(); normalize() }
        catch (e: Exception) { if (before != null) project = before; throw e }
        if (before != null && project != before) {
            undo.addLast(EditRevision(before))
            redo.clear()
        }
        onChange()
    }

    fun beginBatch() {
        if (batch == null) batch = project.duplicate()
    }

    fun endBatch(cancel: Boolean) {
        val original = batch ?: return
        batch = null
        if (cancel) project = original
        else if (project != original) {
            undo.addLast(EditRevision(original))
            redo.clear()
        }
        normalize()
        onChange()
    }

    private fun normalize() {
        val origins = project.clips.flatMap { c -> c.effects.filter { it.kind in TimeEffects.kinds }.map { it.id to (c.start + it.from) } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.min() }
        for (clip in project.clips) for (effect in clip.effects) if (effect.kind in TimeEffects.kinds && effect.phase == null)
            effect.phase = EffectPhase.anchor(clip, max(0.0, clip.start + effect.from - origins.getValue(effect.id)))

        project.clips.removeAll { it.duration < 0.001 }
        for (transition in project.transitions.toList()) {
            val left = project.clips.firstOrNull { it.id == transition.leftId }
            val right = project.clips.firstOrNull { it.id == transition.rightId }
            val available = transitionAvailable(left, right, transition.id)
            if (transition.overlap > available + .000001)
                placeTransition(
                    transition.copy(duration = max(.001, available - 1.0 / project.fps))
                )
        }
        project.clips.forEach {
            it.start = max(0.0, it.start)
            it.track = max(0, it.track)
        }
        selected.retainAll(project.clips.map { it.id }.toSet())
        cursor = cursor.coerceIn(0.0, project.duration)
        project.transitions.removeAll { it.at > project.duration }
    }

    fun undo() {
        if (!canUndo) return
        val restored = materialize(undo.last().project)
        redo.addLast(EditRevision(project.duplicate()))
        undo.removeLast()
        project = restored
        clearSelection()
        normalize()
        onChange()
    }

    fun redo() {
        if (!canRedo) return
        val restored = materialize(redo.last().project)
        undo.addLast(EditRevision(project.duplicate()))
        redo.removeLast()
        project = restored
        clearSelection()
        normalize()
        onChange()
    }

    fun clearSelection() {
        selected.clear()
        range = null
        mark = null
    }

    fun select(c: Clip) {
        if (selected.contains(c.id)) clearSelection()
        else {
            clearSelection()
            selected.add(c.id)
            if (linked && c.linked)
                project.clips
                    .filter {
                        it.start == c.start &&
                            it.duration == c.duration &&
                            it.path == c.path &&
                            (it.group == c.group || it.track == c.track) &&
                            it.linked
                    }
                    .forEach { selected.add(it.id) }
        }
        onChange()
    }

    fun markRange() {
        if (mark == null) {
            mark = cursor
            range = null
        } else {
            range = min(mark!!, cursor)..max(mark!!, cursor)
            mark = null
            selected.clear()
        }
        onChange()
    }

    fun snapTime(t: Double, tolerance: Double = 0.12): Double {
        val bounded = t.coerceIn(0.0, project.duration)
        if (!snap) return bounded
        return project
            .events()
            .minByOrNull { abs(it - bounded) }
            ?.takeIf { abs(it - bounded) < tolerance } ?: bounded
    }

    fun jump(forward: Boolean) {
        val points = project.events()
        cursor =
            if (forward) points.firstOrNull { it > cursor + 0.035 } ?: project.duration
            else points.lastOrNull { it < cursor - 0.035 } ?: 0.0
        onChange()
    }

    // A cut keeps shared effect identities and independent parameter values.
    private fun splitAt(t: Double, ids: Set<String>? = null): Map<String, String> {
        for (tr in project.transitions) for (id in listOf(tr.leftId, tr.rightId)) {
            val source = project.clips.firstOrNull { it.id == id } ?: continue
            project.clips
                .filter { !it.video && samePart(it, source) }
                .forEach { it.group = source.group }
        }
        val created = mutableMapOf<String, String>()
        val clips =
            project.clips.filter {
                (ids == null || it.id in ids) && t > it.start + 0.001 && t < it.end - 0.001
            }
        for (clip in clips) {
            val oldDuration = clip.duration
            val local = t - clip.start
            val connected =
                project.transitions.any { tr ->
                    project.clips.any { source ->
                        (source.id == tr.leftId || source.id == tr.rightId) &&
                            source.group == clip.group
                    }
                }
            val right = clip.duplicate(true, keepGroup = connected)
            right.start = t
            if (clip.frozen == null) right.sourceIn = clip.sourceTime(t)
            right.effects.clear()
            for (fx in clip.effects) {
                val end = min(fx.to, clip.duration)
                if (end > local)
                    right.effects.add(
                        fx.copyEffect().apply {
                            from = max(0.0, from - local)
                            to = end - local
                        }
                    )
            }
            right.sliceAudio(local, oldDuration, oldDuration)
            clip.sliceAudio(0.0, local, oldDuration)
            val hold = clip.frozen
            if (hold != null) {
                clip.frozen = hold.copy(duration = local)
                right.frozen = hold.copy(duration = oldDuration - local, offset = hold.offset + local)
            } else clip.sourceOut = right.sourceIn
            clip.effects.removeAll { it.from >= local }
            clip.effects.forEach { it.to = min(it.to, local) }
            project.clips.add(right)
            project.transitions.filter { it.leftId == clip.id }.forEach { it.leftId = right.id }
            created[clip.id] = right.id
            if (clip.id in selected) selected.add(right.id)
        }
        return created
    }

    fun split() {
        if (cursor > 0 && cursor < project.duration)
            change { splitAt(cursor, selected.takeIf { it.isNotEmpty() }?.toSet()) }
    }

    fun targets(): List<Clip> {
        val r = range
        return when {
            r != null -> project.clips.filter { it.start < r.endInclusive && it.end > r.start }
            selected.isNotEmpty() -> project.clips.filter { it.id in selected }
            else ->
                project.clips.filter {
                    it.contains(cursor.coerceAtMost(max(0.0, project.duration - 0.001)))
                }
        }
    }

    fun applyEffect(effect: Effect, replaceId: String? = null) {
        change {
            val retimed = mutableSetOf<Pair<Double, Double>>()
            val r = range
            if (r != null) {
                splitAt(r.endInclusive)
                splitAt(r.start)
            }
            for (c in targets().sortedByDescending { it.start }) {
                if (replaceId != null && c.effects.none { it.id == replaceId }) continue
                val oldDuration = c.duration
                val oldEnd = c.end
                c.effects.removeAll {
                    it.id == replaceId ||
                        (replaceId == null &&
                            it.kind == effect.kind &&
                            effect.kind !in listOf("Text", "Image"))
                }
                c.effects.add(
                    effect.copyEffect().apply {
                        if (
                            replaceId == null &&
                                kind in listOf("Text", "Image") &&
                                r == null &&
                                selected.isEmpty()
                        ) {
                            val offset = (cursor - c.start).coerceAtLeast(0.0)
                            from += offset
                            to += offset
                        }
                        to = min(to, oldDuration)
                        if (kind == "Dolly Zoom") values.putIfAbsent("origin", c.sourceTime(c.start + from).toString())
                    }
                )
                if (abs(c.duration - oldDuration) > .000001)
                    retime(c, oldDuration, oldEnd, retimed.add(c.start to oldEnd))
            }
        }
    }

    fun replaceChain(chain: List<Effect>, kinds: List<String>) {
        change {
            val retimed = mutableSetOf<Pair<Double, Double>>()
            range?.let {
                splitAt(it.endInclusive)
                splitAt(it.start)
            }
            for (c in targets().sortedByDescending { it.start }) {
                val oldDuration = c.duration
                val oldEnd = c.end
                c.effects.removeAll { it.kind in kinds }
                chain.forEach {
                    c.effects.add(
                        it.copyEffect().apply {
                            from = 0.0
                            to = oldDuration
                        }
                    )
                }
                if (abs(c.duration - oldDuration) > .000001)
                    retime(c, oldDuration, oldEnd, retimed.add(c.start to oldEnd))
            }
        }
    }

    internal fun isolateAudioScope(): Set<String> {
        val ids = targets().filter { it.audio }.map { it.id }.toMutableSet()
        val r = range ?: return ids
        ids.addAll(splitAt(r.endInclusive, ids).values)
        ids.addAll(splitAt(r.start, ids).values)
        return project.clips.filter { it.id in ids && it.start < r.endInclusive && it.end > r.start }.map { it.id }.toSet()
    }

    internal fun isolateVideoScope(): Set<String> {
        range?.let { r ->
            splitAt(r.endInclusive, targets().filter { it.video }.map { it.id }.toSet())
            splitAt(r.start, targets().filter { it.video }.map { it.id }.toSet())
        }
        return targets().filter { it.video }.map { it.id }.toSet()
    }

    internal fun replaceVideoStates(states: Map<String, List<Effect>>, kinds: Set<String>) {
        change {
            val retimed = mutableSetOf<Pair<Double, Double>>()
            for (c in project.clips.filter { it.id in states }.sortedByDescending { it.start }) {
                val duration = c.duration
                val end = c.end
                c.effects.removeAll { it.kind in kinds }
                c.effects.addAll(states.getValue(c.id).map { it.copyEffect() })
                if (abs(c.duration - duration) > .000001)
                    retime(c, duration, end, retimed.add(c.start to end))
            }
        }
    }

    fun commitDraft(expected: Project, edited: Project) {
        change {
            check(project == expected) { "The project changed while this tool was open. Reopen the tool to keep the newer edits." }
            project = edited.duplicate()
        }
    }

    fun applyPlacements(placements: Map<String, Effect>, tracks: Map<String, Int>) {
        change {
            val effects = placements.toMutableMap()
            val order = tracks.toMutableMap()
            range?.let { r ->
                for (time in listOf(r.endInclusive, r.start)) {
                    for ((left, right) in splitAt(time, effects.keys.toSet())) {
                        effects[left]?.let { effects[right] = it.copyEffect(true) }
                        order[left]?.let { order[right] = it }
                    }
                }
            }
            for (clip in
                project.clips.filter {
                    it.id in effects &&
                        (range == null || it.start < range!!.endInclusive && it.end > range!!.start)
                }) {
                val placement = effects[clip.id] ?: continue
                clip.effects.removeAll { it.kind == "Placement" }
                clip.effects.add(
                    placement.copyEffect(true).apply {
                        if (range != null) from = 0.0
                        to = if (range != null) clip.duration else min(to, clip.duration)
                    }
                )
                order[clip.id]?.let { clip.track = max(0, it) }
            }
            for (clip in project.clips.filter { it.id !in effects }) {
                order[clip.id]?.let { clip.track = max(0, it) }
            }
        }
    }

    fun removeEffect(id: String) {
        change {
            val retimed = mutableSetOf<Pair<Double, Double>>()
            for (clip in targets().sortedByDescending { it.start }) {
                val duration = clip.duration
                val end = clip.end
                clip.effects.removeAll { it.id == id }
                if (abs(clip.duration - duration) > .000001)
                    retime(clip, duration, end, retimed.add(clip.start to end))
            }
        }
    }

    private fun detachTransition(value: Transition): Double {
        val left = project.clips.firstOrNull { it.id == value.leftId }
        val right = project.clips.firstOrNull { it.id == value.rightId }
        if (right != null && value.overlap > 0) {
            shiftFrom(right, value.overlap, left, value)
        }
        project.transitions.remove(value)
        return project.clips.firstOrNull { it.id == value.leftId }?.end ?: right?.start ?: value.at
    }

    private fun shiftFrom(root: Clip, delta: Double, excluded: Clip?, ignored: Transition?) {
        val from = root.start
        val following = mutableSetOf(root.id)
        var added: Boolean
        do {
            added = false
            for (tr in project.transitions) if (
                tr !== ignored && tr.leftId in following && tr.rightId != null
            )
                if (following.add(tr.rightId!!)) added = true
        } while (added)
        // A short clip can temporarily move its outgoing overlap before its own start.
        val parts = project.clips.filter { it.id in following }
        val moving =
            project.clips.filter { clip ->
                (excluded == null || !samePart(clip, excluded)) &&
                    (clip.start >= from - .001 || parts.any { samePart(it, clip) })
            }
        moving.forEach { it.start += delta }
        project.transitions
            .filter {
                it !== ignored &&
                    (it.at >= from || it.leftId in following || it.rightId in following)
            }
            .forEach { it.at += delta }
    }

    fun putTransition(value: Transition) {
        change { placeTransition(value) }
    }

    private fun transitionAvailable(left: Clip?, right: Clip?, except: String? = null): Double {
        val incoming =
            left?.let { clip ->
                project.transitions
                    .filter { it.id != except && it.rightId == clip.id }
                    .sumOf { it.overlap }
            } ?: 0.0
        val outgoing =
            right?.let { clip ->
                project.transitions
                    .filter { it.id != except && it.leftId == clip.id }
                    .sumOf { it.overlap }
            } ?: 0.0
        fun length(clip: Clip) = project.sourceSpan(clip).let { it.endInclusive - it.start }
        return min(
            left?.let { length(it) - incoming } ?: Double.MAX_VALUE,
            right?.let { length(it) - outgoing } ?: Double.MAX_VALUE,
        )
    }

    fun transitionMaximum(left: Clip?, right: Clip?, except: String? = null): Double {
        val available = transitionAvailable(left, right, except)
        if (available == Double.MAX_VALUE) return 5.0
        if (available <= .000001) return 0.0
        return max(min(.01, available), available - 1.0 / project.fps)
    }

    private fun placeTransition(value: Transition) {
        val existing = project.transitions.firstOrNull { it.id == value.id }
        val boundary = if (existing != null) detachTransition(existing) else value.at
        val left =
            project.clips.firstOrNull { it.id == value.leftId }
                ?: project.clips
                    .filter { it.video && abs(it.end - boundary) < .02 }
                    .maxByOrNull { it.track }
        val right =
            project.clips.firstOrNull { it.id == value.rightId }
                ?: project.clips
                    .filter { it.video && abs(it.start - boundary) < .02 }
                    .maxByOrNull { it.track }
        if (left == null && right == null) return
        if (left != null && right != null && left.group == right.group) right.group = uid()
        for (source in listOfNotNull(left, right)) project.clips
            .filter { it.id == source.id || it.audio && !it.video && samePart(it, source) }
            .forEach { it.group = source.group }
        val maximum = transitionMaximum(left, right)
        if (maximum <= 0) return
        val duration = value.duration.coerceIn(min(.01, maximum), maximum)
        val result =
            value.copy(
                duration = duration,
                leftId = left?.id,
                rightId = right?.id,
                overlap = if (left != null && right != null) duration else 0.0,
            )
        if (left != null && right != null) {
            shiftFrom(right, left.end - right.start - duration, left, null)
            result.at = left.end - duration / 2
        } else
            result.at = if (left != null) left.end - duration / 2 else right!!.start + duration / 2
        if (abs(cursor - boundary) < .1 || existing != null && abs(cursor - existing.at) < .1)
            cursor = result.at
        project.transitions.add(result)
    }

    fun removeTransition(id: String) {
        val value = project.transitions.firstOrNull { it.id == id } ?: return
        change { detachTransition(value) }
    }

    private fun retime(clip: Clip, oldDuration: Double, oldEnd: Double, ripple: Boolean = true) {
        val ratio = clip.duration / oldDuration
        clip.retimeAudio(oldDuration)
        val delta = clip.duration - oldDuration
        clip.effects.forEach {
            it.from *= ratio
            it.to = min(clip.duration, it.to * ratio)
        }
        if (!ripple) return
        val overlap =
            project.transitions.filter { it.leftId == clip.id }.maxOfOrNull { it.overlap } ?: 0.0
        val from = oldEnd - overlap
        project.clips
            .filter { it !== clip && it.start >= from - .001 }
            .forEach { it.start += delta }
        project.transitions.filter { it.at >= from }.forEach { it.at += delta }
    }

    fun copySelection() {
        val t = targets()
        val origin = range?.start ?: t.minOfOrNull { it.start } ?: 0.0
        val groups = mutableMapOf<String, String>()
        clipboard =
            t.mapNotNull { clip ->
                val c = clip.duplicate(true)
                c.group = groups.getOrPut(clip.group) { uid() }
                val r = range
                if (r != null) {
                    val a = max(r.start, c.start)
                    val b = min(r.endInclusive, c.end)
                    if (b <= a) return@mapNotNull null
                    c.sliceAudio(a - clip.start, b - clip.start, clip.duration)
                    if (clip.frozen != null) c.frozen = clip.frozen!!.copy(duration = b - a,
                        offset = clip.frozen!!.offset + a - clip.start)
                    else { c.sourceOut = clip.sourceTime(b); c.sourceIn = clip.sourceTime(a) }
                    c.start = a
                    c.effects.forEach {
                        it.from = max(0.0, it.from - (a - clip.start))
                        it.to = min(c.duration, it.to - (a - clip.start))
                    }
                    c.effects.removeAll { it.to <= it.from }
                }
                c.start -= origin
                c
            }
        val end = range?.endInclusive ?: t.maxOfOrNull { it.end } ?: origin
        clipboardTransitions =
            project.transitions.mapNotNull { tr ->
                val left = project.clips.firstOrNull { it.id == tr.leftId }
                val right = project.clips.firstOrNull { it.id == tr.rightId }
                if (
                    left != null && left.group !in groups || right != null && right.group !in groups
                )
                    return@mapNotNull null
                val slice = tr.slice(origin, end) ?: return@mapNotNull null
                fun source(reference: Clip?, time: Double): Clip? {
                    if (reference == null) return null
                    val family = clipboard.filter { it.group == groups[reference.group] }
                    return family.firstOrNull { it.contains(time - origin) }
                        ?: family.minByOrNull { abs((it.start + it.end) / 2 - (time - origin)) }
                }
                val before = source(left, slice.end - .000001)
                val after = source(right, slice.begin + .000001)
                if (before == null && after == null) return@mapNotNull null
                slice.copy(
                    at = slice.at - origin,
                    leftId = before?.id,
                    rightId = after?.id,
                    overlap =
                        if (before != null && after != null && tr.overlap > 0)
                            max(
                                0.0,
                                min(before.end, slice.end - origin) -
                                    max(after.start, slice.begin - origin),
                            )
                        else 0.0,
                )
            }
        onChange()
    }

    fun paste() {
        if (clipboard.isEmpty()) return
        change {
            val length = clipboard.maxOf { it.end }
            makeGap(cursor, length)
            clearSelection()
            val ids = mutableMapOf<String, String>()
            val groups = mutableMapOf<String, String>()
            clipboard.forEach { c ->
                project.clips.add(
                    c.duplicate(true).apply {
                        start += cursor
                        group = groups.getOrPut(c.group) { uid() }
                        ids[c.id] = id
                        selected.add(id)
                    }
                )
            }
            clipboardTransitions.forEach { transition ->
                project.transitions.add(
                    transition.copy(
                        id = uid(),
                        at = transition.at + cursor,
                        leftId = ids[transition.leftId],
                        rightId = ids[transition.rightId],
                    )
                )
            }
        }
    }

    fun deleteSelection(ripple: Boolean = true) {
        if (range == null && selected.isEmpty()) return
        change {
            val r = range
            if (r != null) {
                splitAt(r.endInclusive)
                splitAt(r.start)
                splitTransitions(r.endInclusive)
                splitTransitions(r.start)
            }
            val targets = targets()
            val a = targets.minOfOrNull { it.start } ?: return@change
            val b = targets.maxOf { it.end }
            val ids = targets.map { it.id }.toSet()
            val connections = project.transitions.filter { it.leftId in ids || it.rightId in ids }
            val preceding =
                connections
                    .filter { it.leftId !in ids && it.rightId in ids }
                    .maxOfOrNull { it.overlap } ?: 0.0
            val following =
                connections
                    .filter { it.leftId in ids && it.rightId !in ids }
                    .maxOfOrNull { it.overlap } ?: 0.0
            val neighbors = connections.flatMap { listOfNotNull(it.leftId, it.rightId) }.toSet()
            project.clips.removeAll(targets.toSet())
            project.transitions.removeAll(connections.toSet())
            if (
                ripple && project.clips.none { it.start < b && it.end > a && it.id !in neighbors }
            ) {
                val amount = max(0.0, b - a - preceding - following)
                project.clips.filter { it.start >= a - .001 }.forEach { it.start -= amount }
                project.transitions.removeAll { it.at in a..b }
                project.transitions.filter { it.at > a }.forEach { it.at -= amount }
            }
            clearSelection()
            cursor = a
        }
    }

    fun moveSelection(to: Double, track: Int) {
        val items = targets()
        if (items.isEmpty()) return
        val originTrack = items.minOf { it.track }
        change {
            val ids = items.map { it.id }.toSet()
            val attached = project.transitions.filter { it.leftId in ids || it.rightId in ids }
            for (transition in attached) if (
                transition.leftId != null && transition.leftId !in ids ||
                    transition.rightId != null && transition.rightId !in ids
            )
                detachTransition(transition)
            val origin = items.minOf { it.start }
            attached.filter { it in project.transitions }.forEach { it.at += to - origin }
            for (c in items) {
                c.start = max(0.0, c.start + to - origin)
                c.track = max(0, c.track + track - originTrack)
            }
        }
    }

    fun unlink() {
        val items = targets()
        change {
            val link = items.none { it.video && it.audio } && items.any { !it.linked }
            if (link) {
                for (c in items) {
                    project.clips
                        .filter { other ->
                            other.path == c.path &&
                                (other.group == c.group || other.track == c.track) &&
                                abs(other.start - c.start) < .001 &&
                                abs(other.sourceIn - c.sourceIn) < .001 &&
                                abs(other.sourceOut - c.sourceOut) < .001 &&
                                abs(other.effectiveRate - c.effectiveRate) < .0001
                        }
                        .forEach {
                            it.linked = true
                            selected.add(it.id)
                        }
                }
            } else
                for (c in items) if (c.video && c.audio) {
                    c.audio = false
                    c.linked = false
                    project.clips.add(
                        c.duplicate(true, keepGroup = true).apply {
                            video = false
                            audio = true
                            track = c.track
                            selected.add(id)
                        }
                    )
                } else c.linked = false
        }
    }

    fun setGain(db: Float) {
        val items = targets()
        change { items.forEach { it.gainDb = db.coerceIn(-60f, 24f) } }
    }

    fun setRate(rate: Double) {
        val items = targets().sortedByDescending { it.start }
        change {
            val retimed = mutableSetOf<Pair<Double, Double>>()
            for (c in items) {
                val oldEnd = c.end
                val oldDuration = c.duration
                c.rate = rate.coerceIn(0.1, 8.0)
                retime(c, oldDuration, oldEnd, retimed.add(c.start to oldEnd))
            }
        }
    }

    fun append(c: Clip) {
        change {
            clearSelection()
            c.start = project.duration
            project.clips.add(c)
            cursor = c.start
        }
    }

    fun insert(clip: Clip) {
        change {
            val at = cursor
            makeGap(at, clip.duration)
            clip.start = at
            project.clips.add(clip)
            clearSelection()
            selected.add(clip.id)
            cursor = clip.end
        }
    }

    fun addLayer(clip: Clip, at: Double = cursor) {
        change {
            clip.start = max(0.0, at)
            clip.track = (project.clips.filter {
                clip.video && it.video || clip.audio && it.audio
            }.maxOfOrNull { it.track } ?: -1) + 1
            project.clips.add(clip)
            clearSelection()
            selected.add(clip.id)
            cursor = clip.start
        }
    }

    private fun splitTransitions(at: Double) {
        for (tr in
            project.transitions.filter { at > it.begin + .000001 && at < it.end - .000001 }) {
            val parts = listOfNotNull(tr.slice(tr.begin, at), tr.slice(at, tr.end))
            for (part in parts) {
                val left = project.transitionSource(tr.leftId, part.end - .000001)
                val right = project.transitionSource(tr.rightId, part.begin + .000001)
                part.leftId = left?.id
                part.rightId = right?.id
                part.overlap =
                    if (tr.overlap > 0 && left != null && right != null)
                        max(0.0, min(left.end, part.end) - max(right.start, part.begin))
                    else 0.0
            }
            project.transitions.remove(tr)
            project.transitions.addAll(parts)
        }
    }

    private fun makeGap(at: Double, length: Double) {
        splitAt(at)
        splitTransitions(at)
        project.clips.filter { it.start >= at - .001 }.forEach { it.start += length }
        project.transitions.filter { it.at >= at }.forEach { it.at += length }
    }
}
