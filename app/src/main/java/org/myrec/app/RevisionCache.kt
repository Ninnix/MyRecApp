package org.myrec.app

class RevisionCache(private val limit: Int = 8*1024*1024, private val count: Int = 128) {
    private data class Entry(val project: Project, val bytes: Int)
    private val entries = LinkedHashMap<String,Entry>(16,.75f,true)
    private var bytes = 0
    init { require(limit >= 0 && count >= 0) }

    @Synchronized operator fun get(hash: String): Project? = entries[hash]?.project?.duplicate()

    @Synchronized fun put(hash: String, project: Project, weight: Int) {
        require(weight > 0)
        entries.remove(hash)?.let { bytes -= it.bytes }
        if (weight > limit || count == 0) return
        entries[hash] = Entry(project.duplicate(),weight); bytes += weight
        val iterator = entries.entries.iterator()
        while (bytes > limit || entries.size > count) {
            val old = iterator.next(); bytes -= old.value.bytes; iterator.remove()
        }
    }
    @Synchronized fun footprint() = entries.size to bytes
}
