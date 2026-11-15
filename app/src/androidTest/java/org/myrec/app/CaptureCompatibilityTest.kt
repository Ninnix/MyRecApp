package org.myrec.app

import android.app.Instrumentation
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

object CaptureCompatibilityTest {
    fun run(i: Instrumentation, a: MainActivity, onMain: (() -> Unit) -> Unit,
            test: (String, () -> Unit) -> Unit) {
        val manager = a.getSystemService(CameraManager::class.java)
        val unsupported = manager.cameraIdList.filter { !HdrRecorder.supported(manager, it) }
        check(unsupported.isNotEmpty()) { "This suite requires a camera without native HDR capture" }
        onMain { a.showCapture() }
        val capture = (0 until a.root.childCount).map { a.root.getChildAt(it) }.filterIsInstance<Capture>().single()
        fun ready() {
            val deadline = SystemClock.uptimeMillis() + 12000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = capture.readyToRecord }
                if (ready) return
                Thread.sleep(100)
            }
            error("SDR camera did not become ready")
        }
        fun tap(id: String) {
            onMain {
                val r = capture.controlBounds(id) ?: error("Missing $id")
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now + 30, action, r.centerX(), r.centerY(), 0)
                    capture.dispatchTouchEvent(event); event.recycle()
                }
            }
            Thread.sleep(200)
        }
        fun control(name: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 4000
            while (SystemClock.uptimeMillis() < deadline) {
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
                i.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (node.text?.toString() == name || node.contentDescription?.toString() == name) return node
                    for (n in 0 until node.childCount) node.getChild(n)?.let { queue.add(it) }
                }
                Thread.sleep(100)
            }
            error("Missing $name settings control")
        }
        try {
            ready()
            for (id in unsupported) {
                onMain { capture.switchCamera(id) }
                ready()
                test("unsupported HDR requests leave camera $id available for SDR") {
                    repeat(3) { onMain { capture.setHdr(true) } }
                    ready()
                    tap("settings")
                    val hdr = control("HDR")
                    check(!hdr.isEnabled && !hdr.isSelected)
                    check(control("SDR").isSelected)
                    val r = Rect(); hdr.getBoundsInScreen(r)
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now + 30, action, r.exactCenterX(), r.exactCenterY(), 0)
                        i.uiAutomation.injectInputEvent(event, true); event.recycle()
                    }
                    Thread.sleep(200)
                    check(!control("HDR").isSelected && control("SDR").isSelected)
                    check(control("Camera settings done").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    ready()
                }
            }
            test("SDR recording survives an unsupported HDR request and remains playable") {
                onMain { a.editor.load(Project(name = "Unsupported HDR check")); capture.start() }
                Thread.sleep(1300)
                check(capture.recording)
                onMain { capture.setHdr(true) }
                check(capture.recording && !capture.paused)
                Thread.sleep(500)
                onMain { capture.stop() }
                val clip = a.editor.project.clips.single()
                check(File(clip.path).length() > 1000 && clip.duration > .5)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(clip.path)
                    val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                        .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
                    check(!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ||
                        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084))
                } finally { extractor.release() }
                VideoDecoder(clip.path).use { decoder ->
                    (decoder.frame(.3, 160, 90) ?: error("SDR recording cannot be decoded")).recycle()
                }
            }
        } finally {
            onMain { if (capture.recording) capture.stop(); capture.suspendCamera() }
        }
    }
}
