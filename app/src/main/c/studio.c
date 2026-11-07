#include "lava.h"
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

static void failure(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type)
        (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jboolean JNICALL Java_org_yourec_app_Native_studioAvailable(JNIEnv *env,
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

JNIEXPORT jlong JNICALL Java_org_yourec_app_Native_studioLoad(JNIEnv *env, jobject self,
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

JNIEXPORT jfloatArray JNICALL Java_org_yourec_app_Native_studioProcess(JNIEnv *env,
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

JNIEXPORT void JNICALL Java_org_yourec_app_Native_studioFree(JNIEnv *env, jobject self,
                                                             jlong handle) {
    (void)env;
    (void)self;
    yr_lava_close((YrLava *)(intptr_t)handle);
}
