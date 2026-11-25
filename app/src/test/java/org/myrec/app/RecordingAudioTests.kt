package org.myrec.app

import kotlin.math.*

object RecordingAudioTests {
    fun run(test: (String, () -> Unit) -> Unit) {
        test("recording gain ramps both original channels in 20 ms without hiding clipping") {
            val gain=RecordingGain()
            val source=FloatArray(1920*2) { if (it%2==0) .4f else -.2f }
            val original=source.copyOf()
            gain.set(12f)
            val output=gain.process(source,source.size,2)
            check(output[1918]>.4f && output[1920]>1.5f && output.last()<-.79f)
            check(source.contentEquals(original))
            gain.set(0f)
            val reset=gain.process(source,source.size,2)
            check(abs(reset[1920]-.4f)<1e-6 && abs(reset.last()+.2f)<1e-6)
            check(reset.size==source.size)
        }
        test("recording gain validates limits, half steps, mono mapping and finite samples") {
            check(RecordingGain.normalized(-90f)==-40f && RecordingGain.normalized(15f)==12f)
            check(RecordingGain.normalized(.25f)==.5f && RecordingGain.normalized(-.25f)==-.5f)
            check(RecordingGain.normalized(Float.NaN)==0f)
            val result=RecordingGain().process(floatArrayOf(.5f,Float.NaN,Float.POSITIVE_INFINITY),3,1)
            check(result.contentEquals(floatArrayOf(.5f,.5f,0f,0f,0f,0f)))
            val phone=MicrophoneInput(12,15,"Phone","")
            check(phone.gainKey(listOf(phone))==phone.copy(id=400).gainKey(listOf(phone.copy(id=400))))
            val duplicate=phone.copy(id=13)
            check(phone.gainKey(listOf(phone,duplicate))!=duplicate.gainKey(listOf(phone,duplicate)))
        }
        test("microphone diagnostics distinguish clipping, received silence and missing input") {
            val meter=RecordingReadings()
            meter.update(floatArrayOf(.99f),1_000_000_000)
            check(meter.clipping(1_349_000_000) && !meter.clipping(1_351_000_000))
            check(meter.arriving(1_799_000_000) && !meter.arriving(1_801_000_000))
            meter.clearLevel()
            check(meter.arriving(1_100_000_000) && !meter.clipping(1_100_000_000))
            meter.update(FloatArray(480*2),2_000_000_000)
            check(meter.arriving(2_100_000_000) && meter.peakDB==-160f)
            meter.reset(); check(!meter.arriving(2_100_000_000))
        }
        test("microphone sample time survives backward and future hardware timestamps") {
            val clock=MicrophoneClock()
            check(clock.block(480,1_020_000_000,1_000_000_000)==1_000_000_000L)
            check(clock.block(480,1_030_000_000,1_010_500_000)==1_010_500_000L)
            check(clock.block(480,1_040_000_000,900_000_000)==1_020_500_000L)
            check(clock.block(480,1_050_000_000,2_000_000_000)==1_030_500_000L)
            check(clock.block(480,1_060_000_000,null)==1_040_500_000L)
            check(clock.block(480,1_070_000_000,1_051_000_000)==1_051_000_000L)
            check(clock.block(480,1_500_000_000,null)==1_340_000_000L)
        }
        test("microphone priority preserves disconnected inputs and never upgrades an active take") {
            val order=listOf("dji","headset","phone")
            check(RecordingMicrophoneRoute.priority(order,listOf("phone","usb"),"phone")==order+"usb")
            check(RecordingMicrophoneRoute.priority(emptyList(),listOf("phone","headset"),"headset")==listOf("headset","phone"))
            val route=RecordingMicrophoneRoute()
            fun observe(available: List<String>,actual: String?,preferred: String?,time: Double,take: Boolean=true)=
                route.resolve(available,actual,preferred,take,time)
            check(observe(order,"phone","phone",0.0).request=="dji")
            check(observe(order,"dji","dji",1.0).request==null)
            route.select("phone")
            check(observe(order,"dji","dji",2.0).request=="phone")
            check(observe(order,"phone","phone",2.1).request==null)
            check(observe(listOf("headset","dji"),"dji","phone",3.0).request==null)
            check(observe(emptyList(),null,null,3.2).disconnected==null)
            check(observe(order,"dji","phone",3.5).request=="phone")
            check(observe(listOf("headset","dji","phone"),"phone","phone",4.0).request==null)
            check(observe(listOf("dji","headset"),"phone","phone",5.0).disconnected==null)
            route.disconnected(listOf("phone"))
            check(observe(listOf("dji","headset"),"headset",null,6.0).recheck!=null)
            check(observe(listOf("dji","headset"),"headset",null,7.1).request=="dji")
            check(observe(listOf("dji","headset"),"dji","dji",7.5).disconnected=="phone")
            check(observe(listOf("phone","dji"),"dji","dji",8.0).disconnected==null)
            check(observe(listOf("phone","dji"),"phone","dji",9.0).request=="dji")
            route.reset()
            check(observe(order,"phone","phone",10.0,false).request=="dji")
        }
        test("microphone fallback retries negotiation and moves past a refused device") {
            val route=RecordingMicrophoneRoute(); route.select("headset")
            val inputs=listOf("dji","phone")
            for (time in listOf(11.0,13.0)) check(route.resolve(inputs,"phone","headset",true,time).request==null)
            for (time in listOf(14.1,14.5,15.1,16.2)) check(route.resolve(inputs,"phone","dji",true,time).request=="dji")
            check(route.resolve(inputs,"phone","dji",true,18.0).recheck!=null)
            check(route.resolve(inputs,"phone","dji",true,18.1).disconnected=="headset")
            check(route.resolve(listOf("headset")+inputs,"phone","phone",true,19.0).request==null)
            route.select("headset")
            repeat(4) { check(route.resolve(listOf("headset","phone"),"phone","headset",true,30.0+it*2).request=="headset") }
            check(route.resolve(listOf("headset","phone"),"phone","headset",true,40.0).failed=="headset")
            route.retry()
            check(route.pinned=="headset" && route.resolve(listOf("headset","phone"),"phone","headset",true,41.0).request=="headset")
        }
        test("recording clocks map queued frames around pause and resume without changing old times") {
            val c=RecordingClock(); val origin=1_000_000_000L
            c.start(origin); c.pause(origin+20_000_000); c.resume(origin+40_000_000); c.stop(origin+60_000_000)
            check(c.time(origin-1)==null && c.time(origin)==0L)
            check(c.time(origin+19_000_000)==19_000_000L && c.time(origin+20_000_000)==null)
            check(c.time(origin+40_000_000)==20_000_000L && c.time(origin+60_000_000)==null)
            val normal=RecordingAudioClock(); val output=mutableListOf<Float>()
            val samples=FloatArray(4800*2) { (it/2).toFloat() }
            normal.process(samples,origin-10_000_000,c) { pcm,from,count -> for (i in from*2 until (from+count)*2) output.add(pcm[i]) }
            check(normal.nextFrame==1920L && normal.insertedSilence==0L)
            check(output.first()==480f && output[1920]==2400f && output.last()==3359f)
        }
        test("microphone gaps become counted silence while source overlap is trimmed") {
            val c=RecordingClock(); val origin=1_000_000_000L; c.start(origin)
            val normal=RecordingAudioClock(); val output=mutableListOf<Float>()
            fun append(at: Long,value: Float)=normal.process(FloatArray(480*2){value},at,c) { pcm,from,count ->
                for (i in from*2 until (from+count)*2) output.add(pcm[i])
            }
            append(origin,.25f); append(origin+20_000_000,.5f)
            check(normal.nextFrame==1440L && normal.insertedSilence==480L)
            check(output.take(960).all { it==.25f } && output.subList(960,1920).all { it==0f })
            append(origin+25_000_000,.75f)
            check(normal.nextFrame==1680L && output.takeLast(480).all { it==.75f })
        }
        test("microphone clock correction bounds external-clock drift without changing silence or stereo gain") {
            for (ppm in listOf(-200.0,200.0)) {
                val clock=RecordingClock(); val origin=1_000_000_000L; clock.start(origin)
                val normal=RecordingAudioClock()
                val pcm=FloatArray(1024*2) { if (it%2==0) .3f else -.1f }
                repeat(2500) { i ->
                    val time=origin+(i*1024*1e9/48000*(1+ppm/1e6)).roundToLong()
                    normal.process(pcm,time,clock) { result,from,count ->
                        for (j in from until from+count) check(abs(result[j*2]-.3f)<1e-6 && abs(result[j*2+1]+.1f)<1e-6)
                    }
                }
                check(normal.maximumOffset<128 && normal.corrections!=0L && normal.insertedSilence==0L)
            }
        }
    }
}
