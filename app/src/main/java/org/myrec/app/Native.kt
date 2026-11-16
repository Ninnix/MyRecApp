package org.myrec.app

import android.graphics.Bitmap

object Native {
    external fun transition(first: Bitmap,second: Bitmap,result: Bitmap,kind: Int,progress: Float,amount: Float,angle: Float,soft: Boolean,parameters: FloatArray)
    external fun nightFuse(original: Bitmap, fused: Bitmap, neighbor: Bitmap, mass: FloatArray, distance: Int, precise: Boolean, progress: AudioProgress?=null)
    external fun nightRestore(bitmap: Bitmap, fused: Bitmap, strength: Float, brightness: Float, reduction: Float, detail: Float, progress: AudioProgress?=null)
    external fun trackSubject(previous: Bitmap,current: Bitmap,bounds: FloatArray): FloatArray?
    external fun timeFilm(bitmap: Bitmap, kind: Int, strength: Float, parameters: FloatArray, frame: Long, elapsed: Double)
    external fun crashZoom(bitmap: Bitmap, scale: Float, blur: Float, targetX: Float, targetY: Float)
    external fun frameBlur(bitmap: Bitmap,strength: Float,sigma: Float)
    external fun widescreenFrame(bitmap: Bitmap,strength: Float,ratio: Int,blurFill: Boolean,flares: Float,color: Int)
    external fun legacyVisual(bitmap: Bitmap,kind: Int,strength: Float,value: Float)
    external fun focusPeaking(bitmap: Bitmap)
    external fun workingAverage(bitmap: Bitmap, other: Bitmap, weight: Float)
    external fun workingNegative(bitmap: Bitmap, strength: Float)
    external fun hlgSurface(surface: android.view.Surface): Boolean
    external fun workingToneMap(bitmap: Bitmap)
    external fun workingDisplay(bitmap: Bitmap, output: Bitmap, toneMap: Boolean)
    external fun dollyMask(bitmap: Bitmap,mask: FloatArray,width: Int,height: Int,scale: Float,targetX: Float,targetY: Float,intensity: Float)
    external fun dolly(bitmap: Bitmap, mask: FloatArray, scale: Float, targetX: Float, targetY: Float, intensity: Float)
    external fun packP010(y: java.nio.ByteBuffer,u: java.nio.ByteBuffer,v: java.nio.ByteBuffer,yr: Int,ur: Int,vr: Int,us: Int,vs: Int,
        width: Int,height: Int,outY: java.nio.ByteBuffer,outUV: java.nio.ByteBuffer): Boolean
    external fun colorLut(bitmap: android.graphics.Bitmap, entries: FloatArray, size: Int,
        oneDimensional: Boolean, bounds: FloatArray, input: Int, strength: Float, exposure: Float, highlights: Float, source: Int = 0,
        warmth: Float = 0f, tint: Float = 0f,recovery: Float=0f,adaptation: FloatArray?=null,extrapolate: Boolean=false)

    init {
        System.loadLibrary("myrec")
    }

    external fun kaleidoscope(bitmap: Bitmap, strength: Float, divisions: Int, centerX: Float, centerY: Float,
        splitAngle: Float, sourcePanX: Float, sourcePanY: Float, sourceAngle: Float)
    external fun rgbChannels(bitmap: Bitmap, strength: Float,
        redX: Float, redY: Float, greenX: Float, greenY: Float, blueX: Float, blueY: Float)

    external fun filmColor(bitmap: Bitmap, kernel: Int, strength: Float, parameters: FloatArray)
    external fun halation(bitmap: Bitmap, strength: Float, parameters: FloatArray)
    external fun noir(bitmap: Bitmap, strength: Float, parameters: FloatArray, frame: Long)
    external fun sixteenMm(bitmap: Bitmap, strength: Float, parameters: FloatArray, frame: Long)
    external fun primaryColor(bitmap: Bitmap, strength: Float, parameters: FloatArray)

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

    fun interface AudioProgress { fun step(frames: Long, total: Long): Boolean }
    external fun audioResample(input: String, output: String, from: Int, to: Int, frames: Long, channels: Int = 2, progress: AudioProgress): Long
    external fun audioRamp(input: String, output: String, plan: DoubleArray, progress: AudioProgress): Long
    external fun audioStretch(input: String, output: String, speed: Double, progress: AudioProgress): Long

    external fun noiseCreate(): Long
    external fun noiseProcess(handle: Long, input: FloatArray): FloatArray?
    external fun noiseFree(handle: Long)

    external fun audioCreate(rate: Int): Long
    external fun audioDirect(handle: Long, pcm: FloatArray, frames: Int, kind: Int, frequency: Double, gain: Double)

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
        limiter: Float = 0f,
    )

    external fun audioDestroy(handle: Long)

    external fun rnnoiseAvailable(): Boolean

    external fun studioAvailable(): Boolean
    external fun inferenceVersion(): String

    external fun neuralLoad(path: String, kind: Int, threads: Int): Long

    external fun neuralProcess(handle: Long, mono: FloatArray): FloatArray
    external fun neuralHop(handle: Long,mono: FloatArray): FloatArray
    external fun neuralState(handle: Long,state: ByteArray?): ByteArray
    external fun neuralStereo(left: Long,right: Long,input: java.nio.ByteBuffer,drain: Boolean,output: java.nio.ByteBuffer): Int

    external fun neuralFree(handle: Long)

    external fun studioLoad(path: String, threads: Int): Long

    external fun studioProcess(handle: Long, mono: FloatArray): FloatArray

    external fun studioFree(handle: Long)

    external fun personLoad(path: String): Long

    external fun personMask(handle: Long, bitmap: Bitmap): FloatArray
    external fun backgroundMatte(bitmap: Bitmap, person: FloatArray?, plate: Bitmap?, settings: FloatArray): FloatArray
    external fun backgroundComposite(bitmap: Bitmap, background: Bitmap, plate: Bitmap?, matte: FloatArray, width: Int, height: Int, settings: FloatArray)
    external fun backgroundBlur(bitmap: Bitmap, sigma: Float, exposure: Float)


    external fun isolate(
        bitmap: Bitmap,
        strength: Float,
        hue: Float,
        tolerance: Float,
        softness: Float,
        person: FloatArray?,
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

}
