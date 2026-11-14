package org.myrec.app

import kotlin.math.abs

object PreviewModelTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        fun near(a: Float, b: Float) = check(abs(a - b) < .0001f) { "$a != $b" }
        test("preview magnification is continuous between one and eight times") {
            val p = PreviewViewport()
            p.configure(200f, 100f, 400f, 200f)
            p.scale(1.2f); near(p.zoom, 1.2f)
            p.scale(1.25f); near(p.zoom, 1.5f)
            p.scale(100f); near(p.zoom, 8f)
            p.scale(.001f); near(p.zoom, 1f)
            near(p.x, 0f); near(p.y, 0f)
        }
        test("preview scaling keeps the source under the fingers and follows their center") {
            val p = PreviewViewport()
            p.configure(200f, 100f, 400f, 200f)
            p.scale(2f, 150f, 50f)
            near(p.x, -50f)
            p.scale(1f, 160f, 55f, 150f, 50f)
            near(p.x, -40f); near(p.y, 5f)
        }
        test("preview pan respects letterboxing and cannot expose picture edges") {
            val p = PreviewViewport()
            p.configure(200f, 200f, 400f, 200f)
            p.scale(1.5f); p.pan(1000f, 1000f)
            near(p.x, 50f); near(p.y, 0f)
            p.scale(2f); p.pan(-1000f, -1000f)
            near(p.x, -200f); near(p.y, -50f)
            p.scale(.1f)
            near(p.zoom, 1f); near(p.x, 0f); near(p.y, 0f)
        }
        test("preview inspection survives frames but resets for a new viewport or canvas") {
            val p = PreviewViewport()
            p.configure(200f, 100f, 400f, 200f); p.scale(2f)
            p.configure(200f, 100f, 400f, 200f); near(p.zoom, 2f)
            p.configure(100f, 200f, 400f, 200f); near(p.zoom, 1f)
            p.scale(3f); p.configure(100f, 200f, 200f, 400f); near(p.zoom, 1f)
            p.scale(Float.NaN); p.pan(Float.NaN, 0f); near(p.zoom, 1f); near(p.x, 0f)
        }
    }
}
