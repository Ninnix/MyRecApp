package org.myrec.app

import android.app.Service
import android.content.Intent
import android.os.*
import java.io.File

class PersonGpuService : Service() {
    private var handle=0L
    private fun asset(name: String,size: Long): File {
        val file=File(cacheDir,"gpu-$name")
        if (file.length()!=size) {
            val temporary=File(file.path+".tmp")
            assets.open("models/$name").use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(temporary.length()==size && temporary.renameTo(file))
        }
        return file
    }
    @Synchronized private fun mask(input: FloatArray): FloatArray {
        require(input.size==3*192*192)
        if (handle==0L) handle=Native.personGpuLoad(asset("person-preview.param",16535).path,asset("person-preview.bin",6131756).path)
        check(handle!=0L)
        return Native.personGpuMask(handle,input)
    }
    private val binder=object : Binder() {
        override fun onTransact(code: Int,data: Parcel,reply: Parcel?,flags: Int): Boolean {
            if (code!=PersonGpu.hello && code!=PersonGpu.infer) return super.onTransact(code,data,reply,flags)
            data.enforceInterface(PersonGpu.protocol)
            if (reply==null) return false
            if (code==PersonGpu.hello) { reply.writeNoException();reply.writeInt(Process.myPid());return true }
            val result=mask(data.createFloatArray() ?: error("Missing person input"))
            reply.writeNoException();reply.writeFloatArray(result);return true
        }
    }
    override fun onBind(intent: Intent)=binder
    @Synchronized override fun onDestroy() {
        if (handle!=0L) Native.personGpuFree(handle)
        handle=0;super.onDestroy()
    }
}
