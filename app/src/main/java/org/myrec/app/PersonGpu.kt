package org.myrec.app

import android.content.*
import android.graphics.Bitmap
import android.os.*
import java.io.Closeable
import java.util.concurrent.*

/** The camera worker owns this client. A driver failure retires its process
 * and leaves the original CPU model available to the same preview pipeline. */
class PersonGpu(context: Context) : Closeable {
    private val context=context.applicationContext
    private class Connection : ServiceConnection {
        val ready=CountDownLatch(1)
        val started=SystemClock.uptimeMillis()
        @Volatile var remote: IBinder?=null
        @Volatile var pid=0
        override fun onServiceConnected(name: ComponentName,service: IBinder) { remote=service;ready.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { remote=null;ready.countDown() }
        override fun onBindingDied(name: ComponentName) { remote=null;ready.countDown() }
        override fun onNullBinding(name: ComponentName) { ready.countDown() }
    }
    private var connection: Connection?=null
    private var worker: ExecutorService?=null
    private var input: FloatArray?=null
    private var initialized=false
    private var warmup: Future<FloatArray>?=null
    private var warmupAt=0L
    @Volatile var active=false;private set
    companion object {
        internal const val protocol="org.myrec.app.person"
        internal const val hello=IBinder.FIRST_CALL_TRANSACTION
        internal const val infer=hello+1
        @Volatile private var unavailable=false
    }
    internal val processId get()=connection?.pid ?: 0

    fun mask(bitmap: Bitmap): FloatArray? {
        if (unavailable) return null
        try {
            val state=connection ?: Connection().also {
                connection=it
                check(context.bindService(Intent(context,PersonGpuService::class.java),it,Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
            }
            if (state.ready.count>0) {
                check(SystemClock.uptimeMillis()-state.started<3000)
                return null
            }
            val remote=state.remote ?: error("GPU person service disconnected")
            val pixels=input ?: FloatArray(3*192*192).also { input=it }
            val executor=worker ?: Executors.newSingleThreadExecutor().also { worker=it }
            fun request()=executor.submit<FloatArray> {
                val data=Parcel.obtain();val reply=Parcel.obtain()
                try {
                    if (state.pid==0) {
                        data.writeInterfaceToken(protocol)
                        check(remote.transact(hello,data,reply,0));reply.readException();state.pid=reply.readInt()
                        data.setDataSize(0);data.setDataPosition(0);reply.setDataSize(0);reply.setDataPosition(0)
                    }
                    data.writeInterfaceToken(protocol);data.writeFloatArray(pixels)
                    check(remote.transact(infer,data,reply,0));reply.readException()
                    reply.createFloatArray()?.takeIf { it.size==192*192 } ?: error("Invalid GPU person result")
                } finally { data.recycle();reply.recycle() }
            }
            // Warm-up results belong to an old frame. Keep CPU rendering until
            // shaders are ready, then request a mask for the current input.
            if (!initialized) {
                val pending=warmup
                if (pending==null) {
                    Native.personInput(bitmap,pixels);warmupAt=SystemClock.uptimeMillis();warmup=request();return null
                }
                if (!pending.isDone) {
                    check(SystemClock.uptimeMillis()-warmupAt<3000);return null
                }
                pending.get();warmup=null;initialized=true
            }
            Native.personInput(bitmap,pixels)
            val pending=request()
            val result=try { pending.get(500,TimeUnit.MILLISECONDS) }
                catch (error: Exception) { pending.cancel(true);throw error }
            initialized=true;active=true
            return result
        } catch (_: Exception) {
            unavailable=true;active=false
            connection?.pid?.takeIf { it>0 && it!=Process.myPid() }?.let { runCatching { Process.killProcess(it) } }
            close();return null
        }
    }
    override fun close() {
        connection?.let { runCatching { context.unbindService(it) } };connection=null
        warmup?.cancel(true);warmup=null
        worker?.shutdownNow();worker=null;active=false;initialized=false
    }
}
