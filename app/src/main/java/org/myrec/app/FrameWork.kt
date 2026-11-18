package org.myrec.app

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import java.io.Closeable

/** Used only on the owning worker. Leave camera readback and display time in
 * the frame budget, and let Android choose placement within its thermal limits. */
class FrameWork(private val context: Context,private val budget: Long=25_000_000L) : Closeable {
    private var session: PerformanceHintManager.Session?=null
    private var available=true
    fun begin(): Long {
        if (Build.VERSION.SDK_INT>=31 && available && session==null) {
            session=runCatching {
                context.getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(Process.myTid()),budget)
            }.getOrNull()
            available=session!=null
        }
        return System.nanoTime()
    }
    fun finish(start: Long) {
        if (Build.VERSION.SDK_INT>=31) try {
            session?.reportActualWorkDuration((System.nanoTime()-start).coerceAtLeast(1))
        } catch (_: RuntimeException) { close();available=false }
    }
    override fun close() {
        if (Build.VERSION.SDK_INT>=31) runCatching { session?.close() }
        session=null
    }
}
