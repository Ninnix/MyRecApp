package org.myrec.app

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class P010Texture(width: Int,height: Int): AutoCloseable {
    val reader=if (android.os.Build.VERSION.SDK_INT>=33) ImageReader.newInstance(width,height,ImageFormat.YCBCR_P010,3)
        else error("Ten-bit camera buffers require Android 13")
    private val y=ByteBuffer.allocateDirect(width*height*2).order(ByteOrder.nativeOrder())
    private val uv=ByteBuffer.allocateDirect(width*height).order(ByteOrder.nativeOrder())
    private class Planes {
        val textures=IntArray(2).also { GLES20.glGenTextures(2,it,0) }
        var width=0
        var height=0
    }
    private val slots=mutableMapOf<Int,Planes>()
    fun upload(frame: Image,transform: FloatArray,slot: Int) {
        require(frame.format==reader.imageFormat && frame.width*frame.height*2<=y.capacity()) { "The camera did not deliver ten-bit P010" }
        val planes=frame.planes
        require(planes.size>=2)
        val u=planes[1].buffer.slice()
        val v=if (planes.size>=3) planes[2].buffer.slice() else u.duplicate().apply { position(2) }.slice()
        check(Native.packP010(planes[0].buffer.slice(),u,v,planes[0].rowStride,planes[1].rowStride,
            if (planes.size>=3) planes[2].rowStride else planes[1].rowStride,planes[1].pixelStride,
            if (planes.size>=3) planes[2].pixelStride else 4,frame.width,frame.height,y,uv)) { "Cannot access the camera’s ten-bit planes" }
        val target=slots.getOrPut(slot,::Planes)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT,2)
        for (index in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,target.textures[index])
            for (key in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,key,GLES20.GL_NEAREST)
            for (key in intArrayOf(GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,key,GLES20.GL_CLAMP_TO_EDGE)
            if (target.width!=frame.width || target.height!=frame.height) GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D,0,if (index==0) GLES30.GL_R16UI else GLES30.GL_RG16UI,
                frame.width/(index+1),frame.height/(index+1),0,if (index==0) GLES30.GL_RED_INTEGER else GLES30.GL_RG_INTEGER,GLES20.GL_UNSIGNED_SHORT,null)
            GLES30.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,frame.width/(index+1),frame.height/(index+1),
                if (index==0) GLES30.GL_RED_INTEGER else GLES30.GL_RG_INTEGER,GLES20.GL_UNSIGNED_SHORT,if (index==0) y else uv)
        }
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "The graphics driver cannot upload ten-bit camera samples" }
        target.width=frame.width;target.height=frame.height
        val crop=frame.cropRect
        Matrix.setIdentityM(transform,0)
        Matrix.translateM(transform,0,crop.left.toFloat()/frame.width,crop.bottom.toFloat()/frame.height,0f)
        Matrix.scaleM(transform,0,crop.width().toFloat()/frame.width,-crop.height().toFloat()/frame.height,1f)
    }
    fun bind(program: Int,slot: Int) {
        val textures=slots.getValue(slot).textures
        for (index in 0..1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0+index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[index])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,if (index==0) "planeY" else "planeUV"),index)
        }
    }
    override fun close() {
        reader.close()
        slots.values.forEach { GLES20.glDeleteTextures(2,it.textures,0) }
        slots.clear()
    }
    companion object {
        val shader="""
            uniform highp usampler2D planeY;uniform highp usampler2D planeUV;
            vec3 p010(vec2 uv){
                vec2 ys=vec2(textureSize(planeY,0));vec2 cs=vec2(textureSize(planeUV,0));
                vec2 p=clamp(uv*ys-.5,vec2(0.),ys-1.);ivec2 i=ivec2(floor(p));vec2 f=fract(p);ivec2 yn=ivec2(ys)-1;
                float y=mix(mix(float(texelFetch(planeY,i,0).r>>6u),float(texelFetch(planeY,min(i+ivec2(1,0),yn),0).r>>6u),f.x),
                    mix(float(texelFetch(planeY,min(i+ivec2(0,1),yn),0).r>>6u),float(texelFetch(planeY,min(i+ivec2(1,1),yn),0).r>>6u),f.x),f.y);
                p=clamp(uv*cs-.5,vec2(0.),cs-1.);i=ivec2(floor(p));f=fract(p);ivec2 cn=ivec2(cs)-1;
                vec2 c=mix(mix(vec2(texelFetch(planeUV,i,0).rg>>6u),vec2(texelFetch(planeUV,min(i+ivec2(1,0),cn),0).rg>>6u),f.x),
                    mix(vec2(texelFetch(planeUV,min(i+ivec2(0,1),cn),0).rg>>6u),vec2(texelFetch(planeUV,min(i+ivec2(1,1),cn),0).rg>>6u),f.x),f.y);
                return vec3(y,c)/1023.;
            }
        """.trimIndent()
    }
}
