package org.yourec.app

import android.graphics.Bitmap

object Native {
    init {
        System.loadLibrary("yourec")
    }

    external fun kaleidoscope(
        bitmap: Bitmap,
        strength: Float,
        segments: Int,
        rotation: Float,
        zoom: Float,
        centerX: Float,
        centerY: Float,
    )

    external fun widescreen(
        bitmap: Bitmap,
        strength: Float,
        ratio: Float,
        bars: Int,
        flares: Float,
        color: Int,
    )

    external fun asrAvailable(): Boolean

    external fun asrLoad(path: String, threads: Int): Long

    external fun asrTranscribe(handle: Long, audio: FloatArray, language: String): ByteArray?

    external fun asrFree(handle: Long)

    external fun audioCreate(rate: Int): Long

    external fun audioProcess(
        handle: Long,
        pcm: FloatArray,
        frames: Int,
        gain: Float,
        highpass: Float,
        eq: Float,
        frequency: Float,
        compressor: Float,
        gate: Float,
        denoise: Float,
        limiter: Float = 0f,
    )

    external fun audioDestroy(handle: Long)

    external fun rnnoiseAvailable(): Boolean

    external fun studioAvailable(): Boolean

    external fun neuralLoad(path: String, kind: Int, threads: Int): Long

    external fun neuralProcess(handle: Long, mono: FloatArray): FloatArray

    external fun neuralFree(handle: Long)

    external fun studioLoad(path: String, threads: Int): Long

    external fun studioProcess(handle: Long, mono: FloatArray): FloatArray

    external fun studioFree(handle: Long)

    external fun personLoad(path: String): Long

    external fun personMask(handle: Long, bitmap: Bitmap): FloatArray

    external fun personFree(handle: Long)

    external fun isolate(
        bitmap: Bitmap,
        strength: Float,
        hue: Float,
        tolerance: Float,
        softness: Float,
        person: FloatArray?,
    )

    external fun colorCorrect(
        bitmap: Bitmap,
        strength: Float,
        exposure: Float,
        contrast: Float,
        saturation: Float,
        temperature: Float,
        tint: Float,
    )

    external fun night(bitmap: Bitmap, strength: Float, cleanup: Float)

    external fun temporal(bitmap: Bitmap, previous: Bitmap, motion: FloatArray, strength: Float)

    external fun yuv(
        y: java.nio.ByteBuffer,
        u: java.nio.ByteBuffer,
        v: java.nio.ByteBuffer,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        uPixel: Int,
        vPixel: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        rotation: Int,
        standard: Int,
        full: Boolean,
        output: Bitmap,
        depth: Int = 8,
        transfer: Int = 3,
        primaries: Int = 0,
    )

    external fun image(
        bitmap: Bitmap,
        kind: Int,
        amount: Float,
        param: Float,
        smoothing: Float = 0f,
        dither: Boolean = false,
        palette: Int = 0,
        monochrome: Boolean = false,
    )

    external fun blend(
        a: Bitmap,
        b: Bitmap,
        out: Bitmap,
        kind: Int,
        progress: Float,
        amount: Float,
        angle: Float = 0f,
        soft: Boolean = true,
    )

    external fun similarity(a: Bitmap, b: Bitmap): FloatArray

    external fun motion(a: Bitmap, b: Bitmap): FloatArray
}
