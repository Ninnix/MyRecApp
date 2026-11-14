package org.myrec.app

import android.content.Context
import android.graphics.Bitmap
import java.io.File

object Person {
    private lateinit var context: Context
    private var handle = 0L

    @Synchronized
    fun configure(context: Context) {
        this.context = context.applicationContext
    }

    @Synchronized
    fun mask(bitmap: Bitmap): FloatArray {
        if (handle == 0L) {
            val model = File(context.cacheDir, "person-552d8a984054.onnx")
            if (model.length() != 6163938L) {
                val temporary = File(model.path + ".tmp")
                context.assets.open("models/person.onnx").use { source ->
                    temporary.outputStream().use { source.copyTo(it) }
                }
                check(temporary.length() == 6163938L && temporary.renameTo(model))
            }
            handle = Native.personLoad(model.path)
            check(handle != 0L)
        }
        return Native.personMask(handle, bitmap)
    }
}
