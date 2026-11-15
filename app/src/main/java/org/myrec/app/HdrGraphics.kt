package org.myrec.app

import android.opengl.EGL14
import android.opengl.GLES20
import android.util.Log

internal object HdrGraphics {
    const val YUV = "GL_EXT_YUV_target"
    const val EXTERNAL = "GL_OES_EGL_image_external_essl3"
    const val HLG = "EGL_EXT_gl_colorspace_bt2020_hlg"

    data class Support(val reason: String?, val renderer: String, val missing: String?)

    fun evaluate(egl: String, gl: String, tenBit: Boolean, renderer: String): Support {
        fun has(list: String, name: String) = name in list.split(' ')
        val missing = when {
            !has(egl, HLG) -> HLG
            !tenBit -> "10-bit EGL surface"
            !has(gl, YUV) -> YUV
            !has(gl, EXTERNAL) -> EXTERNAL
            else -> null
        }
        return Support(if (missing == null) null else
            "HDR is unavailable with this graphics driver. Use SDR.", renderer, missing)
    }

    // Probe the same format as the compositor, before closing a working SDR camera.
    val support: Support by lazy {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        var initialized = false
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
            initialized = true
            val egl = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE,10,EGL14.EGL_GREEN_SIZE,10,EGL14.EGL_BLUE_SIZE,10,
                EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RENDERABLE_TYPE,0x40,0x3142,1,EGL14.EGL_NONE),
                0,configs,0,1,count,0))
            val config = configs[0]
            if (config == null || count[0] == 0) evaluate(egl,"",false,"")
            else {
                context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE),0)
                surface = EGL14.eglCreatePbufferSurface(display,config,
                    intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
                check(context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display,surface,surface,context))
                evaluate(egl,GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty(),true,
                    GLES20.glGetString(GLES20.GL_RENDERER).orEmpty())
            }
        } catch (e: Exception) {
            Log.w("MyRec-HDR","Cannot probe HDR graphics",e)
            Support("HDR is unavailable with this graphics driver. Use SDR.","","EGL initialization")
        } finally {
            if (initialized) {
                if (previousDisplay != EGL14.EGL_NO_DISPLAY)
                    EGL14.eglMakeCurrent(previousDisplay,previousDraw,previousRead,previousContext)
                else EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context)
                EGL14.eglTerminate(display)
            }
        }.also { result ->
            Log.i("MyRec-HDR","Graphics: ${result.renderer}; missing: ${result.missing ?: "none"}")
        }
    }
}
