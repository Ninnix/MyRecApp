package org.myrec.app

object ProjectAssets {
    internal val transfers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    internal fun begin(working: java.io.File, destination: java.io.File) {
        transfers.add(working.absolutePath); transfers.add(destination.absolutePath)
    }

    internal fun end(working: java.io.File, destination: java.io.File) {
        transfers.remove(working.absolutePath); transfers.remove(destination.absolutePath)
    }

    fun states(saved: EditSession) = listOf(saved.project) +
        saved.undo.map { it.project } + saved.redo.map { it.project }

    fun clips(saved: EditSession) = states(saved).flatMap { it.clips } + saved.originals + saved.captures

    fun paths(saved: EditSession): List<String> = clips(saved).let { clips ->
        (clips.map { it.path } + clips.flatMap { c -> c.effects.flatMap { it.assetPaths() } }).distinct()
    }

    fun remap(saved: EditSession, id: String, name: String, path: (String) -> String): EditSession {
        fun clip(c: Clip): Clip = c.duplicate().copy(path = path(c.path)).also {
            for (effect in it.effects) effect.remapAssets(path)
        }
        fun state(p: Project) = p.duplicate().copy(id = id, name = name).also {
            for (i in it.clips.indices) it.clips[i] = clip(it.clips[i])
        }
        return EditSession(state(saved.project), saved.undo.map { EditRevision(state(it.project)) },
            saved.redo.map { EditRevision(state(it.project)) },
            saved.captures.map(::clip), saved.originals.map(::clip))
    }
}
