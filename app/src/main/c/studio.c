#include "lava.h"
#include "neural.h"
#include "inference.h"
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

static void failure(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type)
        (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jstring JNICALL Java_org_myrec_app_Native_inferenceVersion(JNIEnv *env,jobject self) {
    (void)self;void *library=dlopen("libonnxruntime.so",RTLD_NOW|RTLD_LOCAL);
    if (!library) return (*env)->NewStringUTF(env,"unavailable");
    const OrtApiBase *(*base)(void)=(const OrtApiBase *(*)(void))dlsym(library,"OrtGetApiBase");
    jstring value=(*env)->NewStringUTF(env,base ? base()->GetVersionString() : "unavailable");
    dlclose(library);return value;
}

JNIEXPORT jboolean JNICALL Java_org_myrec_app_Native_studioAvailable(JNIEnv *env,
                                                                      jobject self) {
    (void)env;
    (void)self;
    void *library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
    if (!library)
        return JNI_FALSE;
    int ready = dlsym(library, "OrtGetApiBase") != NULL;
    dlclose(library);
    return ready;
}

JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_studioLoad(JNIEnv *env, jobject self,
                                                              jstring path, jint threads) {
    (void)self;
    const char *directory = (*env)->GetStringUTFChars(env, path, NULL);
    if (!directory)
        return 0;
    char error[512];
    YrLava *state = yr_lava_open("libonnxruntime.so", directory, threads, error, sizeof(error));
    (*env)->ReleaseStringUTFChars(env, path, directory);
    if (!state)
        failure(env, error);
    return (jlong)(intptr_t)state;
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_studioProcess(JNIEnv *env,
                                                                       jobject self,
                                                                       jlong handle,
                                                                       jfloatArray input) {
    (void)self;
    YrLava *state = (YrLava *)(intptr_t)handle;
    if (!state) {
        failure(env, "Studio audio is not ready");
        return NULL;
    }
    jsize count = (*env)->GetArrayLength(env, input);
    if (count < 2048 || count > 48000 * 30) {
        failure(env, "Invalid studio audio block");
        return NULL;
    }
    float *samples = malloc((size_t)count * sizeof(float) * 2);
    if (!samples) {
        failure(env, "Not enough memory to prepare studio audio");
        return NULL;
    }
    (*env)->GetFloatArrayRegion(env, input, 0, count, samples);
    jfloatArray output = NULL;
    if (yr_lava_process(state, samples, samples + count, count)) {
        output = (*env)->NewFloatArray(env, count);
        if (output)
            (*env)->SetFloatArrayRegion(env, output, 0, count, samples + count);
    } else
        failure(env, yr_lava_error(state));
    free(samples);
    return output;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_studioFree(JNIEnv *env, jobject self,
                                                             jlong handle) {
    (void)env;
    (void)self;
    yr_lava_close((YrLava *)(intptr_t)handle);
}

JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_neuralLoad(JNIEnv *env, jobject self,
    jstring path, jint kind, jint threads) {
    (void)self;
    const char *directory = (*env)->GetStringUTFChars(env, path, NULL);
    if (!directory) return 0;
    char error[512];
    YrNeural *state = yr_neural_open("libonnxruntime.so", directory, kind, threads, error, sizeof(error));
    (*env)->ReleaseStringUTFChars(env, path, directory);
    if (!state) failure(env, error);
    return (jlong)(intptr_t)state;
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_neuralProcess(JNIEnv *env,
    jobject self, jlong handle, jfloatArray input) {
    (void)self;
    YrNeural *state = (YrNeural *)(intptr_t)handle;
    jsize count = (*env)->GetArrayLength(env, input);
    if (!state || count < 480 || count > 48000 * 30) {
        failure(env, "Invalid restoration audio block"); return NULL;
    }
    float *samples = malloc((size_t)count * sizeof(float) * 2);
    if (!samples) { failure(env, "Not enough memory for audio restoration"); return NULL; }
    (*env)->GetFloatArrayRegion(env, input, 0, count, samples);
    jfloatArray output = NULL;
    if (yr_neural_process(state, samples, samples + count, count)) {
        output = (*env)->NewFloatArray(env, count);
        if (output) (*env)->SetFloatArrayRegion(env, output, 0, count, samples + count);
    } else failure(env, yr_neural_error(state));
    free(samples);
    return output;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_neuralFree(JNIEnv *env, jobject self,
    jlong handle) {
    (void)env; (void)self;
    yr_neural_close((YrNeural *)(intptr_t)handle);
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_neuralHop(JNIEnv *env,jobject self,jlong handle,jfloatArray input) {
    (void)self;YrNeural *state=(YrNeural *)(intptr_t)handle;
    int count=(*env)->GetArrayLength(env,input);float samples[480],wet[1440];
    if (!state || (count!=0 && count!=480)) { failure(env,"Invalid noise reduction hop");return NULL; }
    if (count) (*env)->GetFloatArrayRegion(env,input,0,480,samples);
    int n=count ? yr_neural_hop(state,samples,wet) : yr_neural_flush(state,wet);
    if (n<0) { failure(env,yr_neural_error(state));return NULL; }
    jfloatArray output=(*env)->NewFloatArray(env,n);
    if (output && n) (*env)->SetFloatArrayRegion(env,output,0,n,wet);
    return output;
}

JNIEXPORT jbyteArray JNICALL Java_org_myrec_app_Native_neuralState(JNIEnv *env,jobject self,jlong handle,jbyteArray input) {
    (void)self;YrNeural *state=(YrNeural *)(intptr_t)handle;size_t size=yr_neural_state(state,NULL,0,0);
    if (!size || size>65536) { failure(env,"Invalid noise reduction state");return NULL; }
    unsigned char bytes[65536];
    if (input) {
        if ((*env)->GetArrayLength(env,input)!=(jsize)size) { failure(env,"Invalid noise reduction checkpoint");return NULL; }
        (*env)->GetByteArrayRegion(env,input,0,(jsize)size,(jbyte *)bytes);
    }
    if (!yr_neural_state(state,bytes,size,input!=NULL)) { failure(env,"Invalid noise reduction checkpoint");return NULL; }
    jbyteArray output=(*env)->NewByteArray(env,(jsize)size);
    if (output) (*env)->SetByteArrayRegion(env,output,0,(jsize)size,(jbyte *)bytes);
    return output;
}

JNIEXPORT jint JNICALL Java_org_myrec_app_Native_neuralStereo(JNIEnv *env,jobject self,jlong left,jlong right,jobject input,jboolean drain,jobject output) {
    (void)self;YrNeural *states[]={(YrNeural *)(intptr_t)left,(YrNeural *)(intptr_t)right};
    float *dry=(*env)->GetDirectBufferAddress(env,input),*wet=(*env)->GetDirectBufferAddress(env,output);
    if (!states[0] || !states[1] || !dry || !wet || (*env)->GetDirectBufferCapacity(env,input)<480*8 || (*env)->GetDirectBufferCapacity(env,output)<1440*8) {
        failure(env,"Invalid stereo noise reduction buffers");return -1;
    }
    int count=-1;float mono[480],result[1440];
    for (int ch=0;ch<2;ch++) {
        if (!drain) for (int j=0;j<480;j++) mono[j]=dry[j*2+ch];
        int n=drain ? yr_neural_flush(states[ch],result) : yr_neural_hop(states[ch],mono,result);
        if (n<0) { failure(env,yr_neural_error(states[ch]));return -1; }
        if (ch && count!=n) { failure(env,"Noise reduction channel clocks differ");return -1; }
        count=n;for (int j=0;j<n;j++) wet[j*2+ch]=result[j];
    }
    return count;
}
