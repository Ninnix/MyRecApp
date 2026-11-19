package org.myrec.app

import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.opengl.GLES30.GL_RGB10_A2
import android.opengl.GLES30.GL_UNSIGNED_INT_2_10_10_10_REV
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal class CameraFrameCopy(private val hdr: Boolean): AutoCloseable {
    class Texture: AutoCloseable {
        var name=0
        var width=0
        var height=0

        override fun close() {
            if (name!=0) glDeleteTextures(1,intArrayOf(name),0)
        }
    }

    private val program=cameraProgram(
        """
        ${if (hdr) "#version 300 es" else ""}
        ${if (hdr) "in" else "attribute"} vec2 position;
        ${if (hdr) "out" else "varying"} vec2 v;
        uniform mat4 transform;
        void main(){gl_Position=vec4(position,0.,1.);v=(transform*vec4(position*.5+.5,0.,1.)).xy;}
        """.trimIndent(),
        if (hdr) """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            #extension GL_EXT_YUV_target : require
            precision highp float;
            uniform highp __samplerExternal2DY2YEXT image;
            in vec2 v; out vec4 color;
            void main(){color=vec4(texture(image,v).xyz,1.);}
        """.trimIndent() else """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES image;
            varying vec2 v;
            void main(){gl_FragColor=texture2D(image,v);}
        """.trimIndent(),
    )
    private val names=IntArray(1)
    private val framebuffer=names.also { glGenFramebuffers(1,it,0) }[0]
    private val position=glGetAttribLocation(program,"position")
    private val transform=glGetUniformLocation(program,"transform")
    private val image=glGetUniformLocation(program,"image")
    private val vertices=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0)
    }

    fun store(source: Int, target: Texture, width: Int, height: Int, matrix: FloatArray) {
        val turned=abs(matrix[1])>abs(matrix[0])
        val w=if (turned) height else width
        val h=if (turned) width else height
        glActiveTexture(GL_TEXTURE0)
        if (target.name==0) {
            glGenTextures(1,names,0)
            target.name=names[0]
            glBindTexture(GL_TEXTURE_2D,target.name)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
        }
        val resize=target.width!=w || target.height!=h
        if (resize) {
            glBindTexture(GL_TEXTURE_2D,target.name)
            glTexImage2D(GL_TEXTURE_2D,0,if (hdr) GL_RGB10_A2 else GL_RGBA,w,h,0,GL_RGBA,
                if (hdr) GL_UNSIGNED_INT_2_10_10_10_REV else GL_UNSIGNED_BYTE,null)
        }
        glBindFramebuffer(GL_FRAMEBUFFER,framebuffer)
        try {
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,target.name,0)
            if (resize) {
                check(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE) { "Cannot retain a camera frame" }
                target.width=w; target.height=h
            }
            glViewport(0,0,w,h)
            glUseProgram(program)
            glUniformMatrix4fv(transform,1,false,matrix,0)
            glUniform1i(image,0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES,source)
            glVertexAttribPointer(position,2,GL_FLOAT,false,0,vertices)
            glEnableVertexAttribArray(position)
            glDisable(GL_DITHER)
            glDrawArrays(GL_TRIANGLE_STRIP,0,4)
            glEnable(GL_DITHER)
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER,0)
        }
    }

    override fun close() {
        glDeleteFramebuffers(1,intArrayOf(framebuffer),0)
        glDeleteProgram(program)
    }
}

internal fun cameraProgram(vertex: String, fragment: String): Int {
    fun shader(type: Int, source: String): Int {
        val id=glCreateShader(type)
        glShaderSource(id,source)
        glCompileShader(id)
        val status=IntArray(1)
        glGetShaderiv(id,GL_COMPILE_STATUS,status,0)
        check(status[0]!=0) { glGetShaderInfoLog(id) }
        return id
    }
    val v=shader(GL_VERTEX_SHADER,vertex)
    val f=shader(GL_FRAGMENT_SHADER,fragment)
    val program=glCreateProgram()
    glAttachShader(program,v)
    glAttachShader(program,f)
    glLinkProgram(program)
    glDeleteShader(v)
    glDeleteShader(f)
    val status=IntArray(1)
    glGetProgramiv(program,GL_LINK_STATUS,status,0)
    check(status[0]!=0) { glGetProgramInfoLog(program) }
    return program
}
