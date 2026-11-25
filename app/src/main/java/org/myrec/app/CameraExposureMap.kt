package org.myrec.app

import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

internal class CameraExposureMap : AutoCloseable {
    private val width = 32
    private val height = 18
    private val framebuffer = IntArray(1).also { glGenFramebuffers(1,it,0) }
    private var externalProgram = 0
    private var retainedProgram = 0
    private val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f))
        position(0)
    }

    class History : AutoCloseable {
        internal val buffers = IntArray(2)
        internal var current = 0
        internal var timestamp = Long.MIN_VALUE

        val texture get() = if (timestamp == Long.MIN_VALUE) 0 else buffers[current]

        fun reset() { timestamp = Long.MIN_VALUE }
        override fun close() { glDeleteTextures(buffers.size,buffers,0) }
    }

    fun update(history: History, image: Int, retained: Boolean, time: Long) {
        val elapsed = time - history.timestamp
        val previous = history.texture
        if (previous != 0 && elapsed in 0 until 80_000_000L) return
        val blend = if (previous == 0 || elapsed !in 1..500_000_000L) 1f
                    else (1.0 - exp(-elapsed / 220_000_000.0)).toFloat()
        val target = 1 - history.current
        val program = if (retained) {
            if (retainedProgram == 0) retainedProgram = program(true)
            retainedProgram
        } else {
            if (externalProgram == 0) externalProgram = program(false)
            externalProgram
        }
        val sourceTarget = if (retained) GL_TEXTURE_2D else GL_TEXTURE_EXTERNAL_OES

        glActiveTexture(GL_TEXTURE1)
        if (history.buffers[0] == 0) {
            glGenTextures(history.buffers.size,history.buffers,0)
            for (texture in history.buffers) {
                glBindTexture(GL_TEXTURE_2D,texture)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,width,height,0,GL_RGBA,GL_UNSIGNED_BYTE,null)
            }
        }

        glBindFramebuffer(GL_FRAMEBUFFER,framebuffer[0])
        try {
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,history.buffers[target],0)
            if (previous == 0)
                check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "Cannot render exposure map" }
            glViewport(0,0,width,height)
            glUseProgram(program)
            glBindTexture(GL_TEXTURE_2D,history.buffers[history.current])
            glUniform1i(glGetUniformLocation(program,"history"),1)
            glUniform1f(glGetUniformLocation(program,"blend"),blend)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(sourceTarget,image)
            glUniform1i(glGetUniformLocation(program,"image"),0)
            val position = glGetAttribLocation(program,"position")
            glVertexAttribPointer(position,2,GL_FLOAT,false,0,vertices)
            glEnableVertexAttribArray(position)
            glDrawArrays(GL_TRIANGLE_STRIP,0,4)
            history.current = target
            history.timestamp = time
        } finally {
            glActiveTexture(GL_TEXTURE0)
            glBindFramebuffer(GL_FRAMEBUFFER,0)
        }
    }

    override fun close() {
        glDeleteFramebuffers(1,framebuffer,0)
        glDeleteProgram(externalProgram)
        glDeleteProgram(retainedProgram)
    }

    private fun program(retained: Boolean) = cameraProgram(
        """
        attribute vec2 position;
        varying vec2 uv;
        void main() {
            gl_Position = vec4(position,0.,1.);
            uv = position*.5+.5;
        }
        """.trimIndent(),
        """
        ${if (retained) "" else "#extension GL_OES_EGL_image_external : require"}
        precision highp float;
        varying vec2 uv;
        uniform ${if (retained) "sampler2D" else "samplerExternalOES"} image;
        uniform sampler2D history;
        uniform float blend;
        float luma(vec2 p) {
            return dot(texture2D(image,p).rgb,vec3(.2126,.7152,.0722));
        }
        void main() {
            vec2 x = vec2(.045,0.);
            vec2 y = vec2(0.,.07);
            float local = (4.*luma(uv) + 2.*(luma(uv-x) + luma(uv+x) + luma(uv-y) + luma(uv+y))
                         + luma(uv-x-y) + luma(uv+x-y) + luma(uv-x+y) + luma(uv+x+y)) / 16.;
            float filtered = blend < 1. ? mix(texture2D(history,uv).r,local,blend) : local;
            gl_FragColor = vec4(filtered,local,0.,1.);
        }
        """.trimIndent()
    )
}
