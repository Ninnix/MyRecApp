package org.myrec.app

import kotlin.math.*

class PreviewViewport {
    var zoom = 1f
        private set
    var x = 0f
        private set
    var y = 0f
        private set
    private var width = 0f
    private var height = 0f
    private var imageWidth = 0f
    private var imageHeight = 0f

    fun configure(width: Float, height: Float, imageWidth: Float, imageHeight: Float) {
        if (this.width != width || this.height != height || this.imageWidth != imageWidth || this.imageHeight != imageHeight) reset()
        this.width = width; this.height = height
        this.imageWidth = imageWidth; this.imageHeight = imageHeight
    }

    fun reset() { zoom = 1f; x = 0f; y = 0f }

    private fun constrain() {
        if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) { x = 0f; y = 0f; return }
        val fit = min(width / imageWidth, height / imageHeight)
        val maxX = max(0f, (imageWidth * fit * zoom - width) / 2)
        val maxY = max(0f, (imageHeight * fit * zoom - height) / 2)
        x = x.coerceIn(-maxX, maxX)
        y = y.coerceIn(-maxY, maxY)
    }

    fun scale(factor: Float, anchorX: Float = width / 2, anchorY: Float = height / 2,
        previousX: Float = anchorX, previousY: Float = anchorY) {
        if (!factor.isFinite() || factor <= 0) return
        val next = (zoom * factor).coerceIn(1f, 8f)
        val ratio = next / zoom
        x = anchorX - width / 2 - (previousX - width / 2 - x) * ratio
        y = anchorY - height / 2 - (previousY - height / 2 - y) * ratio
        zoom = next
        constrain()
    }

    fun pan(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        x += dx; y += dy
        constrain()
    }
}
