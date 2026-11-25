package org.myrec.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

internal class DirectPreview(context: Context, private val changed: () -> Unit) :
    TextureView(context), TextureView.SurfaceTextureListener {
    var surface: Surface? = null
        private set
    var showing = false
        private set
    private var firstFrame = 0L
    private val frame = RectF()

    init {
        surfaceTextureListener = this
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun position(rect: RectF) {
        if (frame == rect || width <= 0 || height <= 0) return
        frame.set(rect)
        setTransform(Matrix().apply { setRectToRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), frame, Matrix.ScaleToFit.FILL) })
    }

    fun present(target: Surface, timestamp: Long) {
        if (surface !== target || firstFrame != 0L) return
        firstFrame = timestamp
        reveal(surfaceTexture)
    }

    fun snapshot(width: Int, height: Int): Bitmap? {
        val transform = getTransform(null)
        setTransform(null)
        return try { getBitmap(width,height) } finally { setTransform(transform) }
    }

    fun hide() {
        firstFrame = 0
        if (!showing) return
        showing = false
        changed()
    }

    private fun reveal(texture: SurfaceTexture?) {
        if (showing || firstFrame == 0L || texture == null || texture.timestamp < firstFrame) return
        showing = true
        changed()
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(texture)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        frame.setEmpty()
        changed()
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = reveal(texture)

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        surface?.release()
        surface = null
        hide()
        return true
    }
}
