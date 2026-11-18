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
    companion object {
        fun create(kind: String) = Effect(kind = kind,parameters = when (kind) {
            in ExistingEffects.kinds -> ExistingEffects.parameters(kind)
            in FrameEffects.kinds -> FrameEffects.parameters(kind)
            "Noir" -> Noir.parameters()
            "16mm" -> SixteenMm.parameters()
            else -> EffectContracts.parameters(kind)
        }).also {
            if (kind == "Stabilize") StabilizationSettings.initialize(it)
            if (kind in BackgroundEffect.kinds) it.values["matteVersion"]="2"
        }
    }
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
            val next = p.number(value)
            if (source != null && p.keyframes.isNotEmpty()) p.setKeyframe(source, next) else p.value = next
        }
    }

    fun editNumber(key: String, value: Float, source: Double, begin: Double, animate: Boolean) {
        val p = parameter(key) ?: return setNumber(key, value)
        val next = p.number(value)
        val prior = (p.at(source) as? EffectValue.Number)?.value
        if (prior != null && abs(prior - value) <= .000001) return
        if (animate && p.keyframes.isEmpty() && source > begin + .001) p.setKeyframe(begin, p.value)
        if (animate || p.keyframes.isNotEmpty()) p.setKeyframe(source, next) else p.value = next
    }

    fun sampled(source: Double): Effect {
        if (motion == null && parameters.none { it.keyframes.isNotEmpty() }) return this
        val pose = motion?.pose(source)
        if (kind in listOf("Color LUTs", "Crop", "Image", "Placement") && pose != null) return copy(motion = null,
            values = values.toMutableMap().apply { for ((key, value) in pose) put(key, value.toString()) })
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
        if (ExistingEffects.modern(this) && kind in ExistingEffects.noIntensity) return 1f
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
    var occurrence: String = group,
    var link: String? = null,
    var photo: Boolean = false,
    val bookmarks: MutableList<SourceBookmark> = mutableListOf(),
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

    fun duplicate(newId: Boolean = false, keepGroup: Boolean = false, keepOccurrence: Boolean = keepGroup): Clip {
        val next = if (newId) uid() else id
        return copy(
            id = next,
            group = if (newId && !keepGroup) next else group,
            occurrence = if (newId && !keepOccurrence) next else occurrence,
            link = if (newId && !keepOccurrence) null else link,
            effects = effects.map { it.copyEffect(newId).also { fx -> if (newId && !keepOccurrence) fx.subtitle=fx.subtitle?.independent() } }.toMutableList(),
            censors = censors.toMutableList(),
            marks = marks.toMutableList(),bookmarks=bookmarks.toMutableList(),
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
    var controls: TransitionControls? = null,
    var windowFrom: Double? = null,
    var windowTo: Double? = null,
) {
    private val window: Pair<Double,Double>
        get()=if (controls==null) 0.0 to duration+hold else TransitionClock(this).let { (windowFrom ?: it.lower) to (windowTo ?: it.upper) }
    val pictureOrigin get()=begin-window.first
    val begin
        get() = at - (window.second-window.first) / 2

    val end
        get() = at + (window.second-window.first) / 2

    private val midpoint
        get() =
            if (phaseTo - phaseFrom < .000001f) .5
            else ((.5 - phaseFrom) / (phaseTo - phaseFrom)).coerceIn(0.0, 1.0)

    val cut
        get() = if (controls!=null) (pictureOrigin+TransitionClock(this).cut).coerceIn(begin,end) else begin + duration * midpoint + hold / 2

    fun progress(time: Double): Float {
        if (controls!=null) return TransitionClock(this).progress(time-pictureOrigin)
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
        if (controls!=null) return copy(id=uid(),at=(a+b)/2,windowFrom=a-pictureOrigin,windowTo=b-pictureOrigin)
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

internal fun samePart(a: Clip, b: Clip) =
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
    val bookmarks: MutableList<SourceBookmark> = mutableListOf(),
) {
    val duration
        get() = clips.maxOfOrNull { it.end } ?: 0.0

    fun duplicate() =
        copy(
            clips = clips.map { it.duplicate() }.toMutableList(),
            transitions = transitions.map { it.copy() }.toMutableList(),
            bookmarks=bookmarks.toMutableList(),
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

    fun markers(): List<Double> = resolvedBookmarks().map { it.time }.distinct().sorted()

}

class EditRevision private constructor(private var value: Project?,var file: String?,
    private var read: (() -> Project)?,private var owner: String,private var captures: Int) {
    constructor(project: Project,file: String?=null):this(project,file,null,project.id,project.captureCount)
    val project get()=value ?: read!!.invoke()
    internal val projectId get()=value?.id ?: owner
    internal val captureCount get()=value?.captureCount ?: captures
    internal val deferred get()=value==null
    internal fun bind(hash: String,load: () -> Project) {
        value?.let { owner=it.id;captures=it.captureCount };file=hash;value=null;read=load
    }
    companion object {
        internal fun deferred(owner: String,captures: Int,file: String?=null,load: () -> Project)=EditRevision(null,file,load,owner,captures)
    }
}

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
    val clipboardInsertsTime: Boolean get() {
        val fragment = Project(clips = clipboard.toMutableList())
        return TimelineRules.groups(fragment).any { TimelineRules.productionGroup(fragment, it) }
    }
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

    var onSnapChange: (Boolean) -> Unit = {}
    var snap = true
        set(value) {
            if (field == value) return
            field = value
            move?.snap = null
            onSnapChange(value)
        }
    var matchLoudness = true
    var linked = true
    var scale = 10.0
    var wholeProject = false
    var playbackRate = 1f
    var move: TimelineMove? = null
        private set
    private var moveSelectionBefore = setOf<String>()
    private var moveRangeBefore: ClosedFloatingPointRange<Double>? = null

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
        move = null
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
        ArchiveGeometry.clean(project)
        val links = project.clips.mapNotNull { it.link }.groupingBy { it }.eachCount()
        project.clips.filter { it.link != null && links.getValue(it.link!!) < 2 }.forEach { it.link = null }
        for (transition in project.transitions.toList()) {
            val left = project.clips.firstOrNull { it.id == transition.leftId }
            val right = project.clips.firstOrNull { it.id == transition.rightId }
            val available = transitionAvailable(left, right, transition.id)
            val cost=if (transition.controls!=null) transition.handleCosts().let { max(it.first,it.second) } else transition.overlap
            if (cost > available + .000001)
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
            if (linked) selected.addAll(TimelineMove.linked(project, setOf(c.id)))
        }
        onChange()
    }

    fun toggleSelection(c: Clip) {
        val members = if (linked) TimelineMove.linked(project, setOf(c.id)) else setOf(c.id)
        if (c.id in selected) selected.removeAll(members) else selected.addAll(members)
        range = null
        mark = null
        onChange()
    }

    fun beginMove() {
        if (move != null || targets().isEmpty()) return
        moveSelectionBefore = selected.toSet()
        moveRangeBefore = range
        val position = cursor
        beginBatch()
        range?.let { r ->
            splitAt(r.endInclusive)
            splitAt(r.start)
            splitTransitions(r.endInclusive)
            splitTransitions(r.start)
        }
        val picked = targets().map { it.id }.toSet()
        if (picked.isEmpty()) { endBatch(true); return }
        move = TimelineMove(project.duplicate(), picked, position, scale)
        selected.clear()
        selected.addAll(move!!.members)
        range = null
        mark = null
        onChange()
    }

    fun changeMoveScope(scope: MoveScope) {
        val draft = move ?: return
        if (!draft.available(scope) || draft.scope == scope) return
        draft.scope = scope
        updateMove(draft.requested, 1.0, snapping = false, changingScope = true)
    }

    fun updateMove(offset: Double, pointsPerSecond: Double, snapping: Boolean = snap,
                   changingScope: Boolean = false) {
        val draft = move ?: return
        val result = draft.resolve(offset, pointsPerSecond, snapping)
        draft.offset = result.offset
        draft.snap = result.snap
        if (!changingScope) draft.requested = result.offset
        project = draft.base.duplicate()
        selected.clear()
        selected.addAll(draft.members)
        range = null
        if (result.offset == 0.0) onChange()
        else moveSelection(project.clips.filter { it.id in draft.members }.minOf { it.start } + result.offset)
    }

    fun placeMove(at: Double) {
        val draft = move ?: return
        val target = TimelineMove.time(at)
        if (target !in draft.boundaries) return
        updateMove(target - draft.anchor, 1.0, snapping = false)
        draft.snap = MoveSnap(target, draft.anchor)
        cursor = target.coerceIn(0.0, project.duration)
        onChange()
    }

    fun finishMove(cancel: Boolean) {
        val draft = move ?: return
        move = null
        endBatch(cancel || draft.offset == 0.0)
        clearSelection()
        if (cancel || draft.offset == 0.0) {
            selected.addAll(moveSelectionBefore)
            range = moveRangeBefore
            scale = draft.scale
        }
        cursor = draft.cursor.coerceIn(0.0, project.duration)
        onChange()
    }

    fun markRange(pointsPerSecond: Double = 60.0, peak: (Clip, Double) -> Float? = { _, _ -> null }): String? {
        cursor = TimelineSnap.mark(project, cursor, pointsPerSecond, snap, peak)
        val r = range
        if (r != null) {
            if (cursor - r.start < TimelineSnap.FRAME - .00001) {
                onChange()
                return "Place the end after the start"
            }
            range = r.start..cursor
        } else if (mark == null) {
            mark = cursor
            range = null
        } else {
            if (abs(cursor - mark!!) < TimelineSnap.FRAME - .00001) {
                onChange()
                return "Choose an end at least one frame away"
            }
            range = min(mark!!, cursor)..max(mark!!, cursor)
            mark = null
            selected.clear()
        }
        onChange()
        return null
    }

    fun snapTime(t: Double, pointsPerSecond: Double = 60.0): Double {
        val bounded = t.coerceIn(0.0, project.duration)
        return if (snap) TimelineSnap.snap(project, bounded, pointsPerSecond) else bounded
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
        val splitGroups = mutableMapOf<String, String>()
        val splitLinks = clips.mapNotNull { it.link }.distinct().associateWith { uid() }
        for (part in project.clips) if (part.start >= t) part.link?.let { splitLinks[it]?.let { next -> part.link = next } }
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
            val right = clip.duplicate(true, keepGroup = connected, keepOccurrence = true)
            right.link = clip.link?.let { splitLinks[it] ?: it }
            if (!connected) right.group = splitGroups.getOrPut(clip.group) { right.group }
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
                            subtitle=subtitle?.independent()
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
            selected.isNotEmpty() -> project.clips.filter { it.id in TimelineMove.linked(project, selected) }
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
            val title = effect.kind == "Text" && effect.values["design"] == "1"
            val base = targets().minOfOrNull { it.start } ?: 0.0
            val origin = r?.start ?: if (selected.isEmpty() && replaceId == null) cursor else base
            for (c in targets().sortedByDescending { it.start }) {
                if (replaceId != null && c.effects.none { it.id == replaceId }) continue
                if (StabilizationSettings.modern(effect) && (!c.video || c.photo)) continue
                val previous = c.effects.firstOrNull { it.id == replaceId }
                val oldDuration = c.duration
                val oldEnd = c.end
                c.effects.removeAll {
                    it.id == replaceId ||
                        (replaceId == null &&
                            it.kind == effect.kind &&
                            effect.kind !in listOf("Text", "Image"))
                }
                if (title && (origin + effect.to <= c.start || origin + effect.from >= c.end)) continue
                c.effects.add(
                    effect.copyEffect().apply {
                        if (StabilizationSettings.modern(this)) StabilizationSettings.bind(this,c,previous)
                        if (kind == "Color LUTs" && motion != null) {
                            val base = r?.start ?: targets().minOfOrNull { it.start } ?: 0.0
                            motion = motion!!.anchored(c, c.start - base)
                        }
                        if (title) {
                            from = max(0.0, origin + effect.from - c.start)
                            to = min(c.duration, origin + effect.to - c.start)
                            phase = EffectPhase.anchor(c, c.start - base)
                            values["titleOrigin"] = (origin + effect.from - base).toString()
                            values["titleDuration"] = (effect.to - effect.from).toString()
                        } else if (
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
                        if (kind == "Dolly Zoom" || kind in BackgroundEffect.kinds || kind in ExistingEffects.kinds) values.putIfAbsent("origin", c.sourceTime(c.start + from).toString())
                        if (kind in BackgroundEffect.kinds) {
                            values.putIfAbsent("backgroundStart",c.sourceTime(c.start+from).toString())
                            values.putIfAbsent("backgroundEnd",c.sourceTime(c.start+to).toString())
                        }
                        if (kind in ExistingEffects.kinds) values.putIfAbsent("clockEnd",c.sourceTime(c.start+to).toString())
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
        if (right != null && (value.overlap > 0 || value.controls!=null && value.overlap!=0.0)) {
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
                    .sumOf { if (it.controls!=null) it.handleCosts().second else it.overlap }
            } ?: 0.0
        val outgoing =
            right?.let { clip ->
                project.transitions
                    .filter { it.id != except && it.leftId == clip.id }
                    .sumOf { if (it.controls!=null) it.handleCosts().first else it.overlap }
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
        if (result.controls!=null) {
            result.windowFrom=null;result.windowTo=null
            var clock=TransitionClock(result)
            val available=transitionAvailable(left,right)
            val cost=max(clock.leftCost,clock.rightCost)
            if (cost>available && cost>0) {
                val factor=max(.000001,(available-1.0/project.fps)/cost)
                result.duration*=factor;result.hold*=factor
                result.controls=result.controls!!.let { c -> c.copy(audioOffset=c.audioOffset?.times(factor),audioBlend=c.audioBlend?.times(factor),stillDissolve=c.stillDissolve*factor) }
                clock=TransitionClock(result)
            }
            result.overlap=if (left!=null && right!=null) clock.overlap else 0.0
            val origin=if (left!=null) left.end-clock.leftEnd else right!!.start-clock.rightStart
            if (left!=null && right!=null) shiftFrom(right,origin+clock.rightStart-right.start,left,null)
            result.at=origin+(clock.lower+clock.upper)/2
            if (abs(cursor-boundary)<.1) cursor=result.cut
            project.transitions.add(result);return
        }
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
        project.shiftBookmarks(from,delta)
    }

    private var clipboardBookmarks=emptyList<SourceBookmark>()

    fun copySelection() {
        val t = targets()
        val origin = t.minOfOrNull { max(it.start, range?.start ?: it.start) } ?: 0.0
        val groups = mutableMapOf<String, String>()
        val occurrences = mutableMapOf<String, String>()
        val links = mutableMapOf<String, String>()
        clipboard =
            t.mapNotNull { clip ->
                val c = clip.duplicate(true)
                c.group = groups.getOrPut(clip.group) { uid() }
                c.occurrence = occurrences.getOrPut(clip.occurrence) { uid() }
                c.link = clip.link?.let { links.getOrPut(it) { uid() } }
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
        ArchiveGeometry.remap(clipboard,ArchiveGeometry.owners(t).associateWith { uid() })
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
        val fragment = Project(clips = clipboard.toMutableList(), transitions = clipboardTransitions.toMutableList())
        val linkCounts = clipboard.mapNotNull { it.link }.groupingBy { it }.eachCount()
        clipboard.filter { it.link != null && linkCounts.getValue(it.link!!) < 2 }.forEach { it.link = null }
        val spans = clipboard.map { TimelineSpan(it.start, it.end) }.toMutableList()
        for (members in TimelineRules.groups(fragment)) {
            val parts = clipboard.filter { it.id in members }
            if (parts.size > 1) spans.add(TimelineSpan(parts.minOf { it.start }, parts.maxOf { it.end }))
        }
        val islands = if (range == null) TimelineRules.union(spans) else
            spans.takeIf { it.isNotEmpty() }?.let { listOf(TimelineSpan(it.minOf { p -> p.start }, it.maxOf { p -> p.end })) } ?: emptyList()
        fun collapsed(time: Double): Double {
            var amount = islands.firstOrNull()?.start ?: 0.0
            for ((left, right) in islands.zipWithNext()) if (time >= right.start - .000001) amount += right.start - left.end
            return time - amount
        }
        clipboardBookmarks=project.bookmarks.filter { it.time>=origin && it.time<end && (range!=null || islands.any { span -> span.contains(it.time-origin) }) }
            .map { it.copy(id=uid(),time=collapsed(it.time-origin)) }
        clipboard.forEach { it.start = collapsed(it.start) }
        clipboardTransitions.forEach { it.at = collapsed(it.at) }
        onChange()
    }

    fun cutSelection() {
        if (range == null && selected.isEmpty()) return
        copySelection()
        deleteSelection()
    }

    fun pasteTargets(): List<PasteTarget> {
        val result = mutableListOf<PasteTarget>()
        fun at(time: Double): Boolean {
            val ticks = TimelineMove.time(time)
            return abs(ticks - cursor) < .0001 || abs(ceil(ticks * 30 - .000001) / 30 - cursor) < .000001
        }
        if (at(0.0)) result.add(PasteTarget(0.0, PasteKind.PROJECT, "Start"))
        if (at(project.duration) && project.duration > 0) result.add(PasteTarget(project.duration, PasteKind.PROJECT, "End"))
        for (audio in listOf(false, true)) {
            val parts = project.clips.filter { if (audio) it.audio && !it.video && !it.linked && it.link == null else it.video }
            for (right in parts) {
                val position = if (audio) right.start else TimelineLayout.interval(project, right).start
                val left = parts.lastOrNull { other -> other.id != right.id &&
                    abs((if (audio) other.end else TimelineLayout.interval(project, other).endInclusive) - position) < .00001 }
                if (left != null && at(position)) result.add(PasteTarget(position,
                    if (audio) PasteKind.SOUND else PasteKind.PICTURE,
                    "${if (audio) "Audio" else "Video"} · ${project.clips.indexOf(left) + 1} → ${project.clips.indexOf(right) + 1}", right.id))
            }
        }
        TimelineRules.gap(project, cursor, true)?.let { result.add(PasteTarget(it.start, PasteKind.PICTURE, "Picture gap", gap = it)) }
        return result.distinctBy { Triple(it.kind, it.at, it.right) }
    }

    fun paste(target: PasteTarget? = null) {
        if (clipboard.isEmpty()) return
        change {
            val length = clipboard.maxOf { it.end }
            val hasPicture = clipboard.any { it.video }
            var position = target?.at ?: cursor
            if (target?.gap != null && hasPicture) {
                val overflow = max(0.0, length - target.gap.duration)
                if (overflow > 0) { shiftParts(project.clips.filter { it.start >= target.gap.end }.map { it.id }.toSet(), overflow);project.shiftBookmarks(target.gap.end,overflow) }
            } else if (target?.kind == PasteKind.SOUND && !hasPicture && target.right != null) {
                val chain = TimelineMove.companions(project, setOf(target.right)).getValue(MoveScope.RIGHT)
                shiftParts(chain.filter { id -> project.clips.any { it.id == id && it.start >= position } }.toSet(), length)
            } else if (hasPicture && (clipboardInsertsTime || target != null && target.label != "End")) {
                position = openPictureTime(position, length, target?.right)
            }
            clearSelection()
            val ids = mutableMapOf<String, String>()
            val groups = mutableMapOf<String, String>()
            val occurrences = mutableMapOf<String, String>()
            val links = mutableMapOf<String, String>()
            val front = (project.clips.filter { it.video }.maxOfOrNull { it.track } ?: -1) + 1
            val layers = clipboard.filter { it.video }.map { it.track }.distinct().sorted()
            clipboard.forEach { c ->
                project.clips.add(c.duplicate(true).apply {
                    start += position
                    group = groups.getOrPut(c.group) { uid() }
                    occurrence = occurrences.getOrPut(c.occurrence) { uid() }
                    link = c.link?.let { links.getOrPut(it) { uid() } }
                    if (video) track = front + layers.indexOf(c.track)
                    ids[c.id] = id
                    selected.add(id)
                })
            }
            ArchiveGeometry.remap(project.clips.filter { it.id in selected },ArchiveGeometry.owners(clipboard).associateWith { uid() })
            clipboardTransitions.forEach { transition ->
                project.transitions.add(transition.copy(id = uid(), at = transition.at + position,
                    leftId = ids[transition.leftId], rightId = ids[transition.rightId]))
            }
            project.bookmarks.addAll(clipboardBookmarks.map { it.copy(id=uid(),time=it.time+position) })
            cursor = position
        }
    }

    private fun openPictureTime(at: Double, length: Double, rightId: String? = null): Double {
        if (length <= .000001) return at
        for (transition in project.transitions.filter { at > it.begin && at < it.end }.toList()) {
            val right = transition.rightId
            project.transitions.remove(transition)
            if (right != null && (transition.overlap > 0 || transition.controls!=null && transition.overlap!=0.0)) {
                val tail = TimelineMove.companions(project, setOf(right)).getValue(MoveScope.RIGHT)
                shiftParts(tail, transition.overlap)
            }
        }
        val position = rightId?.let { id -> project.clips.firstOrNull { it.id == id }?.start } ?: at
        val pictures = project.clips.filter { it.video }.map { it.id }.toSet()
        val production = TimelineMove.linked(project, pictures)
        splitAt(position, production)
        val tail = TimelineMove.linked(project, project.clips.filter { it.video && it.start >= position - .000001 }.map { it.id }.toSet())
        shiftParts(tail, length)
        project.shiftBookmarks(position,length)
        return position
    }

    fun deleteSelection(ripple: Boolean = true) {
        if (range == null && selected.isEmpty()) return
        change {
            range?.let { r ->
                splitAt(r.endInclusive); splitAt(r.start)
                splitTransitions(r.endInclusive); splitTransitions(r.start)
            }
            val before = project.duplicate()
            val parts = targets()
            val ids = parts.map { it.id }.toSet()
            val groups = TimelineRules.groups(before)
            val removed = groups.filter { it.all(ids::contains) && TimelineRules.productionGroup(before, it) }.flatten().toSet()
            val closing = TimelineRules.union(before.clips.filter { it.video && it.id in removed }.map { TimelineSpan(it.start, it.end) })
            val downstream = groups.filter { it.none(ids::contains) && TimelineRules.productionGroup(before, it) }
            val remaining = before.clips.filter { it.video && it.id in downstream.flatten() }
            val gaps = if (!ripple) emptyList() else closing.mapNotNull { r ->
                val left = remaining.filter { it.start < r.start }.maxOfOrNull { it.end } ?: r.start
                val right = remaining.filter { it.start >= r.start }.minOfOrNull { it.start } ?: r.end
                TimelineSpan(max(r.start, left), min(r.end, right)).takeIf { it.duration > .000001 }
            }
            val selectedRange=range
            if (selectedRange!=null) project.bookmarks.removeAll { it.time>=selectedRange.start && it.time<selectedRange.endInclusive }
            for (index in project.bookmarks.indices) {
                val mark=project.bookmarks[index]
                project.bookmarks[index]=mark.copy(time=max(0.0,mark.time-gaps.sumOf { min(it.duration,max(0.0,mark.time-it.start)) }))
            }
            project.clips.removeAll { it.id in ids }
            project.transitions.removeAll { it.leftId in ids || it.rightId in ids }
            for (group in downstream) {
                val anchor = before.clips.filter { it.video && it.id in group }.minOfOrNull { it.start } ?: continue
                val amount = gaps.filter { it.start <= anchor }.sumOf { it.duration }
                for (clip in project.clips.filter { it.id in group }) clip.start -= amount
            }
            for (transition in project.transitions) {
                val id = transition.leftId ?: transition.rightId
                val old = before.clips.firstOrNull { it.id == id }
                val now = project.clips.firstOrNull { it.id == id }
                if (old != null && now != null) transition.at += now.start - old.start
            }
            val removedTime = gaps.sumOf { min(it.duration, max(0.0, cursor - it.start)) }
            val visible = before.active(cursor).lastOrNull() ?: before.clips.firstOrNull { it.audio && it.contains(cursor) }
            val kept = visible?.let { c -> project.clips.firstOrNull { it.id == c.id } }
            cursor = if (gaps.none { it.contains(cursor) } && visible != null && kept != null)
                cursor + kept.start - visible.start else cursor - removedTime
            clearSelection()
        }
    }

    fun moveSelection(to: Double, track: Int? = null) {
        val items = targets()
        if (items.isEmpty()) return
        val originTrack = items.minOf { it.track }
        val delta = TimelineMove.time(max(0.0, to) - items.minOf { it.start })
        if (delta == 0.0 && (track == null || track == originTrack)) return
        change {
            val ids = items.map { it.id }.toSet()
            val attached = project.transitions.filter { it.leftId in ids || it.rightId in ids }
            val previous = project.duplicate()
            for (c in items) {
                c.start = TimelineMove.time(c.start + delta)
                if (track != null) c.track = max(0, c.track + track - originTrack)
            }
            for (transition in attached) {
                val participants = setOfNotNull(transition.leftId, transition.rightId)
                if (participants.all { it in ids }) transition.at += delta
                else {
                    val left = project.clips.firstOrNull { it.id == transition.leftId }
                    val right = project.clips.firstOrNull { it.id == transition.rightId }
                    val overlap = if (left != null && right != null) TimelineMove.time(left.end - right.start) else 0.0
                    if (overlap <= 0 || overlap > transition.overlap + .00001) project.transitions.remove(transition)
                    else {
                        val ratio = overlap / transition.overlap
                        transition.duration *= ratio
                        transition.hold *= ratio
                        transition.overlap = overlap
                        transition.at = (left!!.end + right!!.start) / 2
                    }
                }
            }
            if (track == null && delta != 0.0) {
                val old = previous.clips.associateBy { it.id }
                val stationary = project.clips.filter { it.video && it.id !in ids }
                fun overlap(a: Clip, b: Clip) = TimelineMove.time(a.start) < TimelineMove.time(b.end) &&
                    TimelineMove.time(b.start) < TimelineMove.time(a.end)
                if (items.any { c -> c.video && stationary.any { other ->
                    overlap(c, other) && !overlap(old.getValue(c.id), old.getValue(other.id))
                } }) {
                    val front = (project.clips.filter { it.video }.maxOfOrNull { it.track } ?: 0) + 1
                    val pictures = items.filter { it.video }.sortedBy { old.getValue(it.id).track }
                    for ((rank, picture) in pictures.withIndex()) {
                        picture.track = front + rank
                        for (sound in items.filter { !it.video && it.linked && it.group == picture.group })
                            sound.track = picture.track
                    }
                }
            }
        }
    }

    val selectionLinked: Boolean get() {
        if (selected.isEmpty() || range != null || mark != null) return false
        val groups = TimelineRules.groups(project)
        return groups.any { it == selected && (it.size > 1 || project.clips.any { c ->
            c.id in it && c.video && c.audio && c.linked
        }) }
    }

    fun linkPlan(): LinkPlan {
        require(range == null && mark == null) { "Clear the time range before linking parts." }
        return TimelineRules.linkPlan(project, selected)
    }

    fun linkSelection(intersectionOnly: Boolean = false, expected: Project? = null) {
        check(expected == null || project == expected) { "The timeline changed. Select the parts again." }
        val plan = linkPlan()
        require(!plan.needsIntersection || intersectionOnly) { "Link only the shared interval?" }
        change {
            val span = plan.intersection.takeIf { plan.needsIntersection }
            val members = plan.members.toMutableSet()
            if (span != null) {
                members.addAll(splitAt(span.end, members.toSet()).values)
                members.addAll(splitAt(span.start, members.toSet()).values)
            }
            val parts = project.clips.filter { clip ->
                clip.id in members && (span == null || clip.start >= span.start - .000001 && clip.end <= span.end + .000001)
            }
            val id = uid()
            selected.clear()
            for (part in parts) { part.link = id; part.linked = true; selected.add(part.id) }
        }
    }

    fun unlink() {
        val items = targets()
        change {
            for (clip in items) {
                clip.link = null
                clip.linked = false
                if (clip.video && clip.audio) {
                    clip.audio = false
                    project.clips.add(clip.duplicate(true, keepGroup = true, keepOccurrence = true).apply {
                        video = false; audio = true; selected.add(id)
                    })
                    clip.effects.removeAll { it.subtitle!=null && it.values["subtitleOwner"]=="audio" }
                }
            }
        }
    }

    fun syncOffset(): Double? = targets().singleOrNull()?.let { TimelineRules.syncOffset(project, it) }

    fun restoreSync() {
        val sound = targets().singleOrNull() ?: return
        val offset = TimelineRules.syncOffset(project, sound) ?: return
        if (abs(offset) < .000001) return
        change { sound.start = max(0.0, sound.start - offset) }
    }

    fun closeGap() {
        val gap = TimelineRules.gap(project, cursor) ?: return
        change {
            shiftParts(project.clips.filter { it.start >= gap.end }.map { it.id }.toSet(), -gap.duration)
            for (index in project.bookmarks.indices) {
                val mark=project.bookmarks[index]
                if (mark.time>=gap.start) project.bookmarks[index]=mark.copy(time=max(gap.start,mark.time-gap.duration))
            }
            cursor = gap.start
        }
    }

    private fun shiftParts(ids: Set<String>, delta: Double) {
        for (clip in project.clips) if (clip.id in ids) clip.start = TimelineMove.time(clip.start + delta)
        for (transition in project.transitions) {
            val members = setOfNotNull(transition.leftId, transition.rightId)
            if (members.isNotEmpty() && members.all { it in ids }) transition.at += delta
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
            val gap = TimelineRules.gap(project, cursor, true)
            val at = gap?.start ?: cursor
            if (gap != null) {
                val overflow = max(0.0, clip.duration - gap.duration)
                if (overflow > 0) { shiftParts(project.clips.filter { it.start >= gap.end }.map { it.id }.toSet(), overflow);project.shiftBookmarks(gap.end,overflow) }
            } else openPictureTime(at, clip.duration)
            clip.start = at
            clip.track = (project.clips.filter { it.video }.maxOfOrNull { it.track } ?: -1) + 1
            project.clips.add(clip)
            clearSelection(); selected.add(clip.id); cursor = clip.end
        }
    }

    fun insertBatch(clips: List<Clip>, at: Double, layer: Boolean = false, shape: Pair<Int, Int>? = null) {
        if (clips.isEmpty()) return
        require(shape == null || shape.first in 1..16384 && shape.second in 1..16384) { "This media's dimensions are unsupported" }
        check(batch == null) { "Finish the current edit before importing" }
        beginBatch()
        try {
            if (shape != null && project.clips.none { it.video }) {
                project.width = shape.first; project.height = shape.second
            }
            cursor = at.coerceIn(0.0, project.duration)
            for (clip in clips) {
                if (layer) { addLayer(clip, cursor); cursor = clip.end }
                else insert(clip)
            }
            endBatch(false)
        } catch (e: Exception) { endBatch(true); throw e }
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
        project.shiftBookmarks(at,length)
    }
}
