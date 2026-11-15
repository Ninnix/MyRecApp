package org.myrec.app

import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

class TestMicrophone(recorder: CaptureRecorder, device: AudioDeviceInfo, failure: AtomicReference<Throwable>) : Closeable {
    private val input=LiveMicrophone(Handler(Looper.getMainLooper()),{}, { failure.set(it) }, {0f}) { pcm,time -> recorder.appendAudio(pcm,time) }
    init { input.start(device,MediaRecorder.AudioSource.CAMCORDER) }
    override fun close() = input.close()
}
