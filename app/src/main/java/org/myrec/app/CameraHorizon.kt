package org.myrec.app

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.SystemClock
import java.io.Closeable
import kotlin.math.*

class CameraHorizon(context: Context,private val handler: Handler): SensorEventListener,Closeable {
    private data class Sample(val time: Double,val x: Double,val y: Double)
    private val manager=context.getSystemService(SensorManager::class.java)
    private val sensor=manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val samples=java.util.ArrayDeque<Sample>()
    val available get()=sensor!=null
    @Volatile var active=false
        private set
    @Volatile var received=0L
        private set

    @Synchronized fun configure(enabled: Boolean) {
        if (enabled==active) return
        manager.unregisterListener(this); samples.clear()
        active=enabled && sensor!=null && manager.registerListener(this,sensor,16667,handler)
    }
    override fun onSensorChanged(event: SensorEvent) {
        val x: Double; val y: Double
        if (event.sensor.type==Sensor.TYPE_GRAVITY || event.sensor.type==Sensor.TYPE_ACCELEROMETER) {
            x=-event.values[0].toDouble()/SensorManager.GRAVITY_EARTH
            y=-event.values[1].toDouble()/SensorManager.GRAVITY_EARTH
        } else {
            val matrix=FloatArray(9); SensorManager.getRotationMatrixFromVector(matrix,event.values)
            x=-matrix[6].toDouble(); y=-matrix[7].toDouble()
        }
        val time=(event.timestamp-(SystemClock.elapsedRealtimeNanos()-System.nanoTime()))/1e9
        synchronized(this) {
            if (!active) return
            val prior=samples.peekLast()
            if (event.sensor.type==Sensor.TYPE_ACCELEROMETER && prior!=null) {
                // An accelerometer also sees hand motion. Ignore strong acceleration and smooth the rest.
                val norm=sqrt(x*x+y*y+(event.values[2]/SensorManager.GRAVITY_EARTH).toDouble().pow(2))
                if (norm !in .8..1.2) return
                val weight=1-exp(-(time-prior.time).coerceIn(0.0,1.0)/.35)
                samples.addLast(Sample(time,prior.x+(x-prior.x)*weight,prior.y+(y-prior.y)*weight))
            } else samples.addLast(Sample(time,x,y))
            while (samples.size>90) samples.removeFirst()
            received++
        }
    }
    @Synchronized fun gravity(time: Double): CameraPoint? {
        val last=samples.peekLast() ?: return null
        if (abs(last.time-time)>=1) return null
        var a: Sample?=null
        for (b in samples) {
            if (b.time>time) {
                val previous=a ?: return null
                val w=((time-previous.time)/max(.00001,b.time-previous.time)).coerceIn(0.0,1.0)
                return CameraPoint(previous.x+(b.x-previous.x)*w,previous.y+(b.y-previous.y)*w)
            }
            a=b
        }
        return a?.takeIf { time-it.time<.12 }?.let { CameraPoint(it.x,it.y) }
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) {}
    override fun close()=configure(false)
}
