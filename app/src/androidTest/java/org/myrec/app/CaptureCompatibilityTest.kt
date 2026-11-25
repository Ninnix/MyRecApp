package org.myrec.app

import android.app.Instrumentation
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
        fun control(name: String, selected: Boolean? = null): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 10000
            while (SystemClock.uptimeMillis() < deadline) {
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
                i.uiAutomation.rootInActiveWindow?.let { queue.add(it) }
                var scroll: AccessibilityNodeInfo?=null
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    if (node.isVisibleToUser && (selected==null || node.isSelected==selected) &&
                        (node.text?.toString() == name || node.contentDescription?.toString() == name)) return node
                    if (node.isScrollable) scroll=node
                    for (n in 0 until node.childCount) node.getChild(n)?.let { queue.add(it) }
                }
                if (name.endsWith("HDR") || name=="SDR") scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                Thread.sleep(100)
            }
            error("Missing $name settings control")
        }
        try {
            ready()
            for (id in unsupported) {
                onMain { capture.switchCamera(id) }
                ready()
                val software=SoftwareHdr.exposureSteps(manager.getCameraCharacteristics(id))<0
                test("camera $id offers the available HDR fallback and returns to SDR") {
                    tap("settings")
                    val name=if (software) "Software HDR" else "HDR"
                    val hdr=control(name)
                    check(hdr.isEnabled==software && !hdr.isSelected)
                    check(control("SDR").isSelected)
                    if (software) {
                        check(hdr.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        control(name,true)
                        check(control("SDR",false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        control("SDR",true)
                    } else {
                        onMain { capture.setHdr(true) }
                        control("SDR",true)
                    }
                    check(control("Camera settings done").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    check(!capture.softwareHdr)
                    ready()
                }
            }
            test("SDR recording survives an unsupported HDR request and remains playable") {
                onMain { a.editor.load(Project(name = "Unsupported HDR check")); capture.start() }
                val deadline=SystemClock.uptimeMillis()+20000
                while (capture.recording && capture.elapsedSeconds()<2.0 && SystemClock.uptimeMillis()<deadline) Thread.sleep(100)
                check(capture.recording && capture.elapsedSeconds()>=2.0) { "No two seconds of camera frames: ${a.lastMessage}" }
                onMain { capture.setHdr(true) }
                check(capture.recording && !capture.paused)
                Thread.sleep(500)
                onMain { capture.stop() }
                val savedBy=SystemClock.uptimeMillis()+20000
                var clip: Clip?=null
                while (clip==null && SystemClock.uptimeMillis()<savedBy) {
                    onMain { clip=a.editor.project.clips.singleOrNull() }
                    if (clip==null) Thread.sleep(100)
                }
                val saved=checkNotNull(clip) { "The SDR camera take did not save: ${a.lastMessage}" }
                check(File(saved.path).length() > 1000 && saved.duration > .5)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(saved.path)
                    val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                        .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC)
                    check(!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ||
                        format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) !in listOf(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_TRANSFER_ST2084))
                } finally { extractor.release() }
                VideoDecoder(saved.path).use { decoder ->
                    (decoder.frame(.3, 160, 90) ?: error("SDR recording cannot be decoded")).recycle()
                }
            }
        } finally {
            onMain { if (capture.recording) capture.stop(); capture.suspendCamera() }
        }
        test("software HDR adapts locally over time and matches the recording") {
            SoftwareHdrTest.run(a,onMain)
        }
    }
}
