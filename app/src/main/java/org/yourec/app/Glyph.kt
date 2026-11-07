package org.yourec.app

import android.content.Context
import android.graphics.*
import android.view.View

class Glyph(context: Context, private val kind: String, private val tint: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val scale = minOf(width, height) / 20f
        c.save()
        c.translate(width / 2f, height / 2f)
        c.scale(scale, scale)
        p.color = tint
        p.strokeWidth = 1.1f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        fun path(vararg xy: Float) {
            val q = Path()
            q.moveTo(xy[0], xy[1])
            for (i in 2 until xy.size step 2) q.lineTo(xy[i], xy[i + 1])
            c.drawPath(q, p)
        }
        when (kind) {
            "Import" -> {
                path(-6f, -2f, -6f, 7f, 6f, 7f, 6f, -2f)
                path(0f, -8f, 0f, 3f)
                path(-3f, 0f, 0f, 3f, 3f, 0f)
            }
            "Selected" -> {
                p.style = Paint.Style.FILL
                c.drawCircle(0f, 0f, 6f, p)
                p.color = Color.rgb(17, 17, 17)
                p.style = Paint.Style.STROKE
                path(-2.5f, 0f, -.5f, 2f, 3f, -3f)
            }
            "Sparkles" -> {
                path(0f, -8f, 2f, -2f, 7f, 0f, 2f, 2f, 0f, 8f, -2f, 2f, -7f, 0f, -2f, -2f, 0f, -8f)
                path(6f, -8f, 6f, -4f)
                path(4f, -6f, 8f, -6f)
            }
            "Camera",
            "Camera flip" -> {
                val body =
                    Path().apply {
                        moveTo(-7.5f, -4f)
                        lineTo(-3f, -4f)
                        lineTo(-2f, -6f)
                        lineTo(2f, -6f)
                        lineTo(3f, -4f)
                        lineTo(7.5f, -4f)
                        quadTo(9f, -4f, 9f, -2.5f)
                        lineTo(9f, 5.5f)
                        quadTo(9f, 7f, 7.5f, 7f)
                        lineTo(-7.5f, 7f)
                        quadTo(-9f, 7f, -9f, 5.5f)
                        lineTo(-9f, -2.5f)
                        quadTo(-9f, -4f, -7.5f, -4f)
                        close()
                    }
                c.drawPath(body, p)
                if (kind == "Camera flip") {
                    c.drawArc(-4f, -3f, 4f, 5f, 190f, 140f, false, p)
                    path(1.4f, -1f, 3.8f, -1f, 3.8f, -3.2f)
                    c.drawArc(-4f, -3f, 4f, 5f, 10f, 140f, false, p)
                    path(-1.4f, 3f, -3.8f, 3f, -3.8f, 5.2f)
                } else c.drawCircle(0f, 1f, 3f, p)
            }
            "Stack" -> {
                c.drawRoundRect(-6f, -3f, 6f, 8f, 1.5f, 1.5f, p)
                path(-4f, -6f, 4f, -6f)
                path(-2f, -8f, 2f, -8f)
            }
            "Pause" -> {
                path(-2f, -6f, -2f, 6f)
                path(2f, -6f, 2f, 6f)
            }
            "Play" -> path(-3f, -6f, 6f, 0f, -3f, 6f, -3f, -6f)
            "Scissors" -> {
                c.drawCircle(-5f, -4f, 2.5f, p)
                c.drawCircle(-5f, 4f, 2.5f, p)
                path(-3f, -3f, 7f, 5f)
                path(-3f, 3f, 7f, -5f)
            }
            "Color & style" -> {
                val outline =
                    Path().apply {
                        moveTo(7f, -3f)
                        cubicTo(7f, -9f, -7f, -9f, -8f, -1f)
                        cubicTo(-9f, 6f, -1f, 9f, 1f, 5f)
                        cubicTo(3f, 2f, -1f, 1f, 3f, 0f)
                        cubicTo(5f, 0f, 7f, 0f, 7f, -3f)
                    }
                c.drawPath(outline, p)
                for ((x, y) in listOf(-4f to -3f, 0f to -4f, 4f to -3f, -5f to 1f)) c.drawCircle(
                    x,
                    y,
                    .8f,
                    p,
                )
            }
            "Camera & frame" -> {
                path(-7f, -3f, -7f, -7f, -3f, -7f)
                path(3f, -7f, 7f, -7f, 7f, -3f)
                path(7f, 3f, 7f, 7f, 3f, 7f)
                path(-3f, 7f, -7f, 7f, -7f, 3f)
            }
            "Time" -> {
                c.drawCircle(0f, 0f, 7f, p)
                path(0f, -4f, 0f, 0f, -4f, 0f)
            }
            "Background" -> {
                c.drawRoundRect(-8f, -6f, 8f, 6f, 1f, 1f, p)
                c.drawCircle(0f, -1.5f, 2f, p)
                c.drawArc(-4f, 1f, 4f, 8f, 180f, 180f, false, p)
            }
            "Projects" -> {
                c.drawRoundRect(-6f, -6f, 6f, 7f, 1f, 1f, p)
                path(-3f, -8f, 3f, -8f)
                path(-4f, -3f, 4f, -3f)
            }
            "Record" -> {
                c.drawCircle(0f, 0f, 6f, p)
                c.drawCircle(0f, 0f, 2f, p)
            }
            "Edit" -> {
                for (y in -5..5 step 5) {
                    path(-7f, y.toFloat(), 7f, y.toFloat())
                    c.drawCircle(if (y == 0) 3f else -3f, y.toFloat(), 1.5f, p)
                }
            }
            "Add video" -> {
                c.drawRoundRect(-5f, -6f, 6f, 3f, 1.5f, 1.5f, p)
                path(6f, -3f, 10f, -5f, 10f, 3f, 6f, 1f)
                c.drawCircle(-6f, 4f, 3.5f, p)
                path(-8f, 4f, -4f, 4f)
                path(-6f, 2f, -6f, 6f)
            }
            "Video effects" -> {
                c.drawRoundRect(-7f, -5f, 7f, 4f, 1f, 1f, p)
                path(-2f, 7f, 2f, 7f)
                path(0f, 4f, 0f, 7f)
                path(-3f, -0.5f, 3f, -0.5f)
                path(0f, -3.5f, 0f, 2.5f)
            }
            "Stabilize" -> {
                c.drawRoundRect(-8f, -3f, 8f, 3f, 1f, 1f, p)
                for (x in -5..5 step 3) path(x.toFloat(), -1f, x.toFloat(), 1f)
            }
            "Crop" -> {
                path(-7f, -4f, 5f, -4f, 5f, 8f)
                path(-4f, -7f, -4f, 5f, 8f, 5f)
            }
            "Mirror" -> {
                path(-2f, -5f, -7f, 5f, -2f, 5f, -2f, -5f)
                path(2f, -5f, 7f, 5f, 2f, 5f, 2f, -5f)
                path(0f, -8f, 0f, 8f)
            }
            "Color LUTs" -> {
                c.drawCircle(-2f, -2f, 5f, p)
                c.drawCircle(2f, 2f, 5f, p)
                c.drawCircle(-3f, 4f, 4f, p)
            }
            "Speed" -> {
                c.drawCircle(0f, 0f, 7f, p)
                path(0f, 0f, 3f, -4f)
                path(-5f, 1f, -4f, 1f)
                path(4f, 3f, 5f, 4f)
            }
            "Pauses",
            "Enhance audio" ->
                for (x in -7..7 step 3) {
                    val n = if (x % 2 == 0) 3f else 6f
                    path(x.toFloat(), -n, x.toFloat(), n)
                }
            "Placement",
            "Presets" ->
                for (y in -4..4 step 4) path(
                    -7f,
                    y.toFloat(),
                    0f,
                    y - 4f,
                    7f,
                    y.toFloat(),
                    0f,
                    y + 4f,
                    -7f,
                    y.toFloat(),
                )
            "Transition" -> {
                c.drawRoundRect(-7f, -7f, 3f, 3f, 1f, 1f, p)
                c.drawRoundRect(-3f, -3f, 7f, 7f, 1f, 1f, p)
            }
            "Image" -> {
                c.drawRoundRect(-7f, -6f, 7f, 6f, 1f, 1f, p)
                c.drawCircle(3f, -2f, 1f, p)
                path(-6f, 4f, -2f, -1f, 1f, 2f, 3f, 0f, 6f, 4f)
            }
            "Text" -> {
                p.style = Paint.Style.FILL
                p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                p.textSize = 13f
                p.textAlign = Paint.Align.CENTER
                c.drawText("Aa", 0f, 5f, p)
            }
            else -> {
                c.drawCircle(0f, 0f, 6f, p)
                path(-3f, 0f, 3f, 0f)
                path(0f, -3f, 0f, 3f)
            }
        }
        c.restore()
    }
}
