package org.myrec.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import java.nio.ByteBuffer
import kotlin.math.*

/** Read the encoder geometry without reading or replacing its full-size buffer. */
class CameraReadback {
    private var texture=0
    private var buffer=0
    private var width=0
    private var height=0
    private var bytes=ByteBuffer.allocateDirect(0)
    fun read(w: Int,h: Int,rotation: Int,draw: (Int,Int)->Unit): Bitmap {
        val scale=min(1.0,512.0/max(w,h));val sw=max(2,(w*scale).roundToInt());val sh=max(2,(h*scale).roundToInt())
        val binding=IntArray(1);val viewport=IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,binding,0);GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0)
        try {
            if (width!=sw || height!=sh) {
                close();width=sw;height=sh;bytes=ByteBuffer.allocateDirect(sw*sh*4)
                val ids=IntArray(1);GLES20.glGenTextures(1,ids,0);texture=ids[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,sw,sh,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null)
                GLES20.glGenFramebuffers(1,ids,0);buffer=ids[0]
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,buffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)==GLES20.GL_FRAMEBUFFER_COMPLETE) { "Cannot read recorded mask source" }
            draw(sw,sh);bytes.clear()
            GLES20.glReadPixels(0,0,sw,sh,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
            check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "Cannot read recorded mask pixels" }
            return bitmap(bytes,sw,sh,rotation)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,binding[0]);GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3])
        }
    }
    fun close() {
        if (texture!=0) GLES20.glDeleteTextures(1,intArrayOf(texture),0)
        if (buffer!=0) GLES20.glDeleteFramebuffers(1,intArrayOf(buffer),0)
        texture=0;buffer=0;width=0;height=0
    }
    companion object {
        fun bitmap(bytes: ByteBuffer,width: Int,height: Int,rotation: Int=0): Bitmap {
            val input=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                bytes.rewind();input.copyPixelsFromBuffer(bytes)
                val transform=Matrix().apply { setScale(1f,-1f);postRotate(rotation.toFloat()) }
                return Bitmap.createBitmap(input,0,0,width,height,transform,false)
            } finally { input.recycle() }
        }
    }
}
