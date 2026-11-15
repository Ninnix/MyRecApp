package org.myrec.app

import kotlin.math.abs
import kotlin.random.Random

object CoverFrameTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        test("cover sampling merges overlapping picture and scope while excluding timeline gaps") {
            val pool = CoverFramePool(listOf(0.0..2.0,1.0..3.0,10.0..11.0),listOf(.5..10.5))
            check(pool.count == 90)
            check(pool.time(0) == .5 && abs(pool.time(74) - 2.9666666667) < .000001)
            check(pool.time(75) == 10.0 && abs(pool.time(89) - 10.4666666667) < .000001)
            check(runCatching { pool.time(90) }.isFailure)
        }
        test("cover pool retains tiny off-grid spans and bounds sampling for multi-day projects") {
            val tiny = CoverFramePool(listOf(.001.. .002,.03.. .05),listOf(0.0..1.0))
            check(tiny.count == 2 && tiny.time(0) == .001 && abs(tiny.time(1) - 1.0/30) < .000001)
            val long = CoverFramePool(listOf(0.0..345600.0),listOf(0.0..345600.0))
            check(long.count == 10368000 && long.picks().size == 9)
            check(CoverFramePool(emptyList(),listOf(0.0..1.0)).picks().isEmpty())
        }
        test("cover refresh samples chronological bands without repeating available choices") {
            val pool = CoverFramePool(listOf(0.0..20.0),listOf(0.0..20.0))
            val random = Random(91)
            var previous = pool.picks(random = random)
            repeat(100) {
                val next = pool.picks(previous,random)
                check(next == next.sorted() && next.size == 9 && next.indices.all { next[it] != previous[it] })
                previous = next
            }
        }
        test("cover scope snapshots edits and uses picture under selected audio without changing the project") {
            val video = Clip(path = "picture.mp4",sourceOut = 3.0)
            val audio = Clip(path = "voice.wav",sourceOut = 2.0,start = 1.0,video = false)
            val project = Project(name = "Bad:/\\\nName",clips = mutableListOf(video,audio))
            val source = CoverSource(project,setOf(audio.id))
            check(source.scopeLabel == "Selected clips" && source.pool.count == 60 && source.pool.time(0) == 1.0)
            project.clips[0].sourceOut = .5
            check(source.project.clips[0].sourceOut == 3.0 && source.pool.count == 60)
            check(source.filename(0) == "Bad-Name - 0-01.00.jpg")
            check(CoverSource(project,range = 4.0..5.0).pool.count == 0)
        }
    }
}
