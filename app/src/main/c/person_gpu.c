#include <stdio.h>
#include <c_api.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <string.h>

#define AREA (192*192)
static void failure(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env,type,message);
}

JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_personGpuLoad(JNIEnv *env,jobject self,jstring param,jstring weights) {
    (void)self;
    ncnn_net_t net=ncnn_net_create();ncnn_option_t options=ncnn_option_create();
    if (!net || !options) goto failed;
    ncnn_option_set_num_threads(options,1);
    ncnn_option_set_use_vulkan_compute(options,1);
    ncnn_option_set_use_fp16_packed(options,0);
    ncnn_option_set_use_fp16_storage(options,0);
    ncnn_option_set_use_fp16_arithmetic(options,0);
    ncnn_option_set_use_bf16_storage(options,0);
    ncnn_net_set_option(net,options);ncnn_option_destroy(options);options=NULL;
    ncnn_net_set_vulkan_device(net,0);
    const char *path=(*env)->GetStringUTFChars(env,param,NULL);
    if (!path) goto failed;
    int error=ncnn_net_load_param(net,path);(*env)->ReleaseStringUTFChars(env,param,path);
    if (error) goto failed;
    path=(*env)->GetStringUTFChars(env,weights,NULL);if (!path) goto failed;
    error=ncnn_net_load_model(net,path);(*env)->ReleaseStringUTFChars(env,weights,path);
    if (error || !ncnn_option_get_use_vulkan_compute(ncnn_net_get_option(net))) goto failed;
    return (jlong)(intptr_t)net;
failed:
    if (options) ncnn_option_destroy(options);
    if (net) ncnn_net_destroy(net);
    if (!(*env)->ExceptionCheck(env)) failure(env,"GPU person inference is unavailable");
    return 0;
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_personGpuMask(JNIEnv *env,jobject self,jlong handle,jfloatArray values) {
    (void)self;
    if (!handle || (*env)->GetArrayLength(env,values)!=3*AREA) { failure(env,"Invalid GPU person input");return NULL; }
    ncnn_mat_t input=ncnn_mat_create_3d(192,192,3,NULL),output=NULL;
    ncnn_extractor_t extractor=NULL;jfloatArray result=NULL;
    if (!input || !ncnn_mat_get_data(input)) goto done;
    for (int c=0;c<3;c++) (*env)->GetFloatArrayRegion(env,values,c*AREA,AREA,ncnn_mat_get_channel_data(input,c));
    if ((*env)->ExceptionCheck(env)) goto done;
    extractor=ncnn_extractor_create((ncnn_net_t)(intptr_t)handle);if (!extractor) goto done;
    if (ncnn_extractor_input(extractor,"x",input) || ncnn_extractor_extract(extractor,"save_infer_model/scale_0.tmp_1",&output)) goto done;
    if (!output || ncnn_mat_get_w(output)!=192 || ncnn_mat_get_h(output)!=192 ||
        ncnn_mat_get_c(output)!=2 || ncnn_mat_get_elempack(output)!=1 || ncnn_mat_get_elemsize(output)!=sizeof(float)) goto done;
    float *mask=ncnn_mat_get_channel_data(output,1);if (!mask) goto done;
    for (int i=0;i<AREA;i++) {
        if (!isfinite(mask[i]) || mask[i]<0 || mask[i]>1) goto done;
        if (mask[i]<1e-20f) mask[i]=0;
    }
    result=(*env)->NewFloatArray(env,AREA);if (result) (*env)->SetFloatArrayRegion(env,result,0,AREA,mask);
done:
    if (output) ncnn_mat_destroy(output);
    if (extractor) ncnn_extractor_destroy(extractor);
    if (input) ncnn_mat_destroy(input);
    if (!result && !(*env)->ExceptionCheck(env)) failure(env,"Cannot compute the GPU person mask");
    return result;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_personGpuFree(JNIEnv *env,jobject self,jlong handle) {
    (void)env;(void)self;if (handle) ncnn_net_destroy((ncnn_net_t)(intptr_t)handle);
}
