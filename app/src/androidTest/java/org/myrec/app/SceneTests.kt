package org.myrec.app

import android.app.Instrumentation
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.*

object SceneTests {
    fun run(instrumentation: Instrumentation, a: MainActivity, test: (String, () -> Unit) -> Unit) {
        val source = File(a.getExternalFilesDir(null), "test-inputs/sicily-8s.mp4")
        check(source.isFile) { "Install the eight-second landscape excerpt in test-inputs/sicily-8s.mp4" }
        val clip = ProjectStore.inspect(source).apply { sourceIn = .5; sourceOut = 2.5 }
        val step = Effect(kind = "Step Printing", phase = EffectPhase(.5))
        step.setNumber("speed", .5f); step.setNumber("smear", .7f)
        clip.effects.add(step)
        clip.effects.add(Effect(kind = "Negative Strobe", phase = EffectPhase(.5)))
        val project = Project(width = 480, height = 270, clips = mutableListOf(clip))
        test("user landscape excerpt imports as ordinary SDR media with indexed moving frames") {
            check(clip.video && clip.audio && !WorkingColor.hdr(source.path))
            check(VideoFrameIndex.get(source.path) {}.times.size >= 200)
            check(abs(project.duration - 4.0) < .000001)
        }
        test("landscape Step Printing and Negative Strobe remain deterministic after reverse seeks") {
            Frames().use { frames ->
                val first = frames.render(project, .1, 480, 270)
                val later = frames.render(project, .7, 480, 270)
                val repeated = frames.render(project, .1, 480, 270)
                try { check(first.sameAs(repeated) && !first.sameAs(later)) }
                finally { first.recycle(); later.recycle(); repeated.recycle() }
            }
        }
        test("landscape half speed effects export with matching audio video duration and visible preview") {
            val target = File(a.getExternalFilesDir(null), "test-output/sicily-edit.mp4")
            target.parentFile!!.mkdirs()
            Export(a.audioCache).run(project, target, 480, 270) {}
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(target.path)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                check(tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
                val video = tracks.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                check(video.getInteger(MediaFormat.KEY_WIDTH) == 480 && video.getInteger(MediaFormat.KEY_HEIGHT) == 270)
                check(abs(video.getLong(MediaFormat.KEY_DURATION) / 1e6 - 4.0) < .04)
            } finally { extractor.release() }
            MediaMetadataRetriever().use { reader ->
                reader.setDataSource(target.path)
                val image = reader.getFrameAtTime(100000) ?: error("The edited scene is not playable")
                try { check(image.width == 480 && image.height == 270) } finally { image.recycle() }
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "Scene export: ${target.path}\n") })
        }
    }
}
