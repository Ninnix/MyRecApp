package org.myrec.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.InterruptedIOException
import java.util.concurrent.locks.LockSupport

internal class MediaThermalGate(private val operation: String = "Audio preparation",
                                private val status: () -> Int = { currentStatus() }) {
    private var checked=0L
    fun cooperate() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("$operation cancelled")
        val now=System.nanoTime();if (now-checked<200000000) return
        checked=now
        when (status()) {
            3 -> LockSupport.parkNanos(50000000)
            in 4..6 -> throw InterruptedIOException("Your device is too warm to continue. Let it cool, then try again. Completed work is kept.")
        }
    }
    companion object {
        @Volatile private var current: ()->Int={ 0 }
        fun currentStatus(): Int = current()
        fun configure(context: Context) {
            val manager=context.applicationContext.getSystemService(PowerManager::class.java)
            current={ if (Build.VERSION.SDK_INT>=29) manager.currentThermalStatus else 0 }
        }
    }
}
