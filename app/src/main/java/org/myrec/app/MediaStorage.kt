package org.myrec.app

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

internal class MediaStorage(private val available: (File) -> Long = { it.usableSpace }) {
    fun fits(folder: File, bytes: Long = 0): Boolean = available(folder) - RESERVE >= bytes

    fun requireSpace(folder: File, message: String, bytes: Long = 0) {
        if (!fits(folder, bytes)) throw IOException(message)
    }

    companion object {
        const val RESERVE = 64L * 1024 * 1024
    }
}

internal fun syncDirectory(directory: File) {
    val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try { Os.fsync(fd) } finally { Os.close(fd) }
}
