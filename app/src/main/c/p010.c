#include <jni.h>
#include <stdint.h>
#include <string.h>

static int plane(JNIEnv *env,jobject buffer,int row,int stride,int w,int h) {
    return buffer && row>=((int64_t)w-1)*stride+2 && stride>=2 &&
        (*env)->GetDirectBufferAddress(env,buffer) &&
        (*env)->GetDirectBufferCapacity(env,buffer)>=(int64_t)(h-1)*row+(int64_t)(w-1)*stride+2;
}
JNIEXPORT jboolean JNICALL Java_org_myrec_app_Native_packP010(JNIEnv *env,jobject self,
    jobject y,jobject u,jobject v,jint yr,jint ur,jint vr,jint us,jint vs,
    jint width,jint height,jobject outY,jobject outUV) {
    (void)self;
    if (width<2 || height<2 || width>16384 || height>16384 || ((width|height)&1) ||
        !plane(env,y,yr,2,width,height) || !plane(env,u,ur,us,width/2,height/2) || !plane(env,v,vr,vs,width/2,height/2) ||
        !outY || !outUV || (*env)->GetDirectBufferCapacity(env,outY)<(int64_t)width*height*2 ||
        (*env)->GetDirectBufferCapacity(env,outUV)<(int64_t)width*height) return JNI_FALSE;
    uint8_t *a=(*env)->GetDirectBufferAddress(env,y),*b=(*env)->GetDirectBufferAddress(env,u),*c=(*env)->GetDirectBufferAddress(env,v);
    uint8_t *dy=(*env)->GetDirectBufferAddress(env,outY),*duv=(*env)->GetDirectBufferAddress(env,outUV);
    if (!dy || !duv) return JNI_FALSE;
    for (int j=0;j<height;j++) memcpy(dy+(size_t)j*width*2,a+(size_t)j*yr,(size_t)width*2);
    for (int j=0;j<height/2;j++) for (int i=0;i<width/2;i++) {
        size_t at=((size_t)j*width/2+i)*4;
        memcpy(duv+at,b+(size_t)j*ur+i*us,2);
        memcpy(duv+at+2,c+(size_t)j*vr+i*vs,2);
    }
    return JNI_TRUE;
}
