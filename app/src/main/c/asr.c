#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>

static void *library;
static void *(*load_model)(const char *, int);
static void (*free_model)(void *);
static char *(*transcribe)(void *, const float *, int, const char *);
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void init(void) {
    library = dlopen("libqwen_asr.so", RTLD_NOW | RTLD_LOCAL);
    if (!library)
        return;
    load_model = (void *(*)(const char *, int))dlsym(library, "myrec_qwen_load");
    free_model = (void (*)(void *))dlsym(library, "myrec_qwen_free");
    transcribe = (char *(*)(void *, const float *, int, const char *))dlsym(library, "myrec_qwen_transcribe");
    if (!load_model || !free_model || !transcribe) {
        dlclose(library);
        library = NULL;
    }
}
JNIEXPORT jboolean JNICALL Java_org_myrec_app_Native_asrAvailable(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    pthread_once(&once, init);
    return library != NULL;
}
JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_asrLoad(JNIEnv *env, jobject self, jstring path,
                                                           jint threads) {
    (void)self;
    pthread_once(&once, init);
    if (!library)
        return 0;
    const char *dir = (*env)->GetStringUTFChars(env, path, NULL);
    if (!dir)
        return 0;
    void *ctx = load_model(dir, threads);
    (*env)->ReleaseStringUTFChars(env, path, dir);
    return (jlong)(intptr_t)ctx;
}
JNIEXPORT jbyteArray JNICALL Java_org_myrec_app_Native_asrTranscribe(JNIEnv *env, jobject self,
                                                                      jlong handle,
                                                                      jfloatArray audio,
                                                                      jstring language) {
    (void)self;
    void *ctx = (void *)(intptr_t)handle;
    if (!ctx || !library)
        return NULL;
    int n = (*env)->GetArrayLength(env, audio);
    if (n <= 0 || n > 16000 * 12) return NULL;
    float *samples = malloc((size_t)n * sizeof(float));
    if (!samples)
        return NULL;
    (*env)->GetFloatArrayRegion(env, audio, 0, n, samples);
    if ((*env)->ExceptionCheck(env)) { free(samples); return NULL; }
    for (int i = 0; i < n; i++) if (!isfinite(samples[i])) { free(samples); return NULL; }
    const char *lang = language ? (*env)->GetStringUTFChars(env, language, NULL) : NULL;
    if (language && !lang) { free(samples); return NULL; }
    char *text = transcribe(ctx, samples, n, lang);
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    free(samples);
    if (!text)
        return NULL;
    size_t len = 0;
    while (text[len])
        len++;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out)
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)text);
    free(text);
    return out;
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_asrFree(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    if (handle && library)
        free_model((void *)(intptr_t)handle);
}
