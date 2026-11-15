package org.myrec.app

import android.app.Instrumentation
import android.opengl.EGL14
import android.opengl.GLES20
import java.io.File
import org.json.JSONObject

object HdrGraphicsTests {
    fun run(i: Instrumentation, test: (String, () -> Unit) -> Unit) {
        val egl = HdrGraphics.HLG
        val gl = "${HdrGraphics.YUV} ${HdrGraphics.EXTERNAL}"
        test("camera and encoder support cannot bypass a missing HDR graphics extension") {
            val missing = HdrGraphics.evaluate(egl,HdrGraphics.EXTERNAL,true,"reported driver")
            check(missing.reason != null && missing.missing == HdrGraphics.YUV)
            check(HdrGraphics.evaluate(egl,"${HdrGraphics.YUV}_other ${HdrGraphics.EXTERNAL}",true,"").reason != null)
            check(HdrGraphics.evaluate(egl,gl,false,"").reason != null)
            check(HdrGraphics.evaluate("",gl,true,"").reason != null)
            check(HdrGraphics.evaluate(egl,HdrGraphics.YUV,true,"").reason != null)
            check(HdrGraphics.evaluate(egl,gl,true,"").reason == null)
        }
        test("HDR capability probing preserves an existing EGL context and its framebuffer") {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),0))
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            check(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
                EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
            val config = checkNotNull(configs[0])
            val context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
            val surface = EGL14.eglCreatePbufferSurface(display,config,
                intArrayOf(EGL14.EGL_WIDTH,2,EGL14.EGL_HEIGHT,2,EGL14.EGL_NONE),0)
            try {
                check(EGL14.eglMakeCurrent(display,surface,surface,context))
                GLES20.glClearColor(.25f,.5f,.75f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                val support = HdrGraphics.support
                check(EGL14.eglGetCurrentContext() == context)
                check(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == surface)
                val pixel = java.nio.ByteBuffer.allocateDirect(4)
                GLES20.glReadPixels(0,0,1,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixel)
                check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                check(kotlin.math.abs((pixel.get(1).toInt() and 255)-128) <= 2)
                File(i.targetContext.getExternalFilesDir(null),"hdr-graphics.json").writeText(
                    JSONObject().put("renderer",support.renderer).put("reason",support.reason)
                        .put("missing",support.missing).toString(2))
            } finally {
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display,surface); EGL14.eglDestroyContext(display,context)
                EGL14.eglTerminate(display)
            }
        }
    }
}
