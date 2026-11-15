package org.myrec.app

import java.io.File
import kotlin.math.*

object StabilizationTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun near(a: Double, b: Double, epsilon: Double = .00001) = check(abs(a-b) < epsilon) { "$a != $b" }
        fun analysis(count: Int = 240, sample: (Int, Double) -> StabilizationPose): StabilizationAnalysis {
            val samples = List(count) { i -> val t = i/30.0; StabilizationSample(t,sample(i,t),0) }
            return StabilizationAnalysis(640,360,count/30.0,samples,count-1)
        }
        val pan = analysis { i,_ -> StabilizationPose(i*1.1,i*-.2) }
        val noisy = analysis { i,t -> StabilizationPose(i*1.1+6*sin(2*PI*4.1*t),
            i*-.2+4*sin(2*PI*3.3*t),.008*sin(2*PI*2.7*t)) }
        test("stabilization preserves constant pans at both ends and on irregular timestamps") {
            for (input in listOf(pan,StabilizationAnalysis(640,360,4.0,List(90) { i ->
                val t = i*.04+(i%3)*.001
                StabilizationSample(t,StabilizationPose(t*33,t*-6),0)
            },89))) {
                val plan = StabilizationPath.make(input,StabilizationLevel.Strong)
                near(plan.zoom,1.0)
                for (sample in plan.samples) { near(sample.pose.x,0.0); near(sample.pose.y,0.0) }
            }
        }
        test("stabilization levels remove shake within exact corner bounds on held frames") {
            var previous = Double.POSITIVE_INFINITY
            for (level in StabilizationLevel.entries.filter { it != StabilizationLevel.Off }) {
                val plan = StabilizationPath.make(noisy,level)
                val residual = (30 until 210).sumOf { i ->
                    val stable = plan.samples[i].pose.applying(noisy.samples[i].pose)
                    (stable.x-pan.samples[i].pose.x).pow(2)+(stable.y-pan.samples[i].pose.y).pow(2)
                }/180
                check(residual < previous+.05 && plan.zoom <= level.maximumZoom+.000001)
                for (frame in 0 until 720) {
                    val values = plan.transform(frame/90.0); val a = values[0]; val b = values[1]; val det = a*a+b*b
                    for (x in listOf(0.0,640.0)) for (y in listOf(0.0,360.0)) {
                        val dx = x-values[2]; val dy = y-values[3]
                        val sx = (a*dx+b*dy)/det; val sy = (-b*dx+a*dy)/det
                        check(sx >= -.001 && sx <= 640.001 && sy >= -.001 && sy <= 360.001)
                    }
                }
                previous = residual
            }
            check(previous < 2)
            val plan = StabilizationPlan(640,360,listOf(StabilizationSample(0.0,StabilizationPose(x = 1.0),0),
                StabilizationSample(.04,StabilizationPose(x = 7.0),0)),1.0,false,1.0)
            near(plan.transform(.039)[2],1.0); near(plan.transform(.04)[2],7.0)
            check(StabilizationPath.make(noisy,StabilizationLevel.Off).transform(1.0).contentEquals(doubleArrayOf(1.0,0.0,0.0,0.0)))
        }
        test("stabilization never smooths a deliberate zoom or invents translation after a pan") {
            val zoom = analysis { _,t -> StabilizationPose(logScale = t*ln(2.0)/2) }
            val plan = StabilizationPath.make(zoom,StabilizationLevel.Strong)
            check(plan.zoom == 1.0 && plan.samples.all { abs(it.pose.logScale) < 1e-10 })
            val panZoom = analysis(450) { _,t ->
                val z = 1+((t-7)/2).coerceIn(0.0,1.0)
                StabilizationPose(x = -z*min(t,4.0)*200,logScale = ln(z))
            }
            for (level in listOf(StabilizationLevel.Standard,StabilizationLevel.Strong))
                check(StabilizationPath.make(panZoom,level).samples.filter { it.time >= 6.5 }
                    .all { abs(it.pose.x) < .0001 && abs(it.pose.y) < .0001 })
        }
        test("stabilization resets at scene cuts and fades correction over 300 ms") {
            val cut = noisy.copy(samples = noisy.samples.mapIndexed { i,s ->
                if (i >= 120) s.copy(pose = StabilizationPose(),segment = 1) else s
            },tracked = 238)
            val plan = StabilizationPath.make(cut,StabilizationLevel.Strong)
            check(plan.samples.drop(120).all { abs(it.pose.x) < .000001 && abs(it.pose.y) < .000001 })
            near(plan.samples[119].pose.x,0.0); near(plan.samples[119].pose.y,0.0)
            val separate = StabilizationPath.make(cut.copy(samples = cut.samples.take(120),duration = 4.0,tracked = 119),StabilizationLevel.Strong)
            for (i in 20..90) { near(plan.samples[i].pose.x,separate.samples[i].pose.x); near(plan.samples[i].pose.y,separate.samples[i].pose.y) }
        }
        test("stabilization limits a large bump locally and keeps a static frame uncropped") {
            val input = analysis(360) { _,t -> StabilizationPose(x = 5*sin(2*PI*4*t)+if (t in 5.0..5.1) 280.0 else 0.0) }
            val plan = StabilizationPath.make(input,StabilizationLevel.Light)
            check(plan.limited && plan.zoom <= StabilizationLevel.Light.maximumZoom)
            val ordinary = StabilizationPath.make(analysis(360) { _,t -> StabilizationPose(x = 5*sin(2*PI*4*t)) },StabilizationLevel.Light)
            for (i in 15..60) near(plan.samples[i].pose.x,ordinary.samples[i].pose.x)
            check(StabilizationPath.make(analysis { _,_ -> StabilizationPose() },StabilizationLevel.Strong).zoom == 1.0)
        }
        test("stabilization reference range survives split trim copy and independent multi-source edits") {
            val c = Clip(path = "one.mp4",sourceIn = 1.0,sourceOut = 9.0)
            val editor = Editor(Project(clips = mutableListOf(c)))
            editor.applyEffect(Effect.create("Stabilize"))
            val fx = editor.project.clips.single().effects.single()
            check(StabilizationSettings.level(fx) == StabilizationLevel.Standard && StabilizationSettings.range(fx,c) == 1.0 to 9.0)
            editor.cursor = 4.0; editor.split()
            check(editor.project.clips.size == 2 && editor.project.clips.all { StabilizationSettings.range(it.effects.single(),it) == 1.0 to 9.0 })
            editor.selected.addAll(editor.project.clips.map { it.id }); editor.copySelection(); editor.cursor = editor.project.duration; editor.paste()
            check(editor.project.clips.all { StabilizationSettings.range(it.effects.single(),it) == 1.0 to 9.0 })
            editor.undo(); check(editor.project.clips.size == 2); editor.redo(); check(editor.project.clips.size == 4)
            val second = Clip(path = "two.mp4",sourceIn = 2.0,sourceOut = 5.0,start = editor.project.duration)
            editor.project.clips.add(second); editor.clearSelection(); editor.selected.addAll(editor.project.clips.map { it.id })
            val shared = Effect.create("Stabilize"); editor.applyEffect(shared)
            val changed = editor.project.clips.first().effects.single().copyEffect().apply { values["level"] = "Strong" }
            editor.applyEffect(changed,shared.id)
            val other = editor.project.clips.last()
            check(StabilizationSettings.range(other.effects.single(),other) == 2.0 to 5.0)
            check(!StabilizationSettings.modern(Effect(kind = "Stabilize",values = mutableMapOf("strength" to ".7"))))
        }
        test("stabilization cache verifies source identity checksum structure and finite poses") {
            val folder = kotlin.io.path.createTempDirectory("stabilization").toFile()
            val file = File(folder,"motion"); val key = "a".repeat(64)
            try {
                StabilizationCache.write(file,key,noisy)
                check(StabilizationCache.read(file,key) == noisy && StabilizationCache.read(file,"b".repeat(64)) == null)
                val bytes = file.readBytes(); bytes[bytes.lastIndex/2] = (bytes[bytes.lastIndex/2].toInt() xor 1).toByte()
                file.writeBytes(bytes); check(StabilizationCache.read(file,key) == null)
                file.writeBytes(bytes.copyOf(24)); check(StabilizationCache.read(file,key) == null)
                check(!noisy.copy(samples = listOf(StabilizationSample(0.0,StabilizationPose(Double.NaN),0)),tracked = 0).valid())
                check(runCatching { StabilizationCache.write(file,key,noisy.copy(tracked = 900)) }.isFailure)
            } finally { folder.deleteRecursively() }
        }
        test("stabilization cancellation does not publish a partial plan") {
            var polls = 0
            check(runCatching { StabilizationPath.make(noisy,StabilizationLevel.Strong) { ++polls > 1 } }.exceptionOrNull() is java.io.InterruptedIOException)
        }
    }
}
