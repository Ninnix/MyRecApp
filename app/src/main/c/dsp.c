#include <dlfcn.h>
#include <jni.h>
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

typedef void *(*rn_create_fn)(void *);
typedef float (*rn_process_fn)(void *, float *, const float *);
typedef void (*rn_destroy_fn)(void *);
static rn_create_fn rn_create;
static rn_process_fn rn_process;
static rn_destroy_fn rn_destroy;
static pthread_once_t rn_once = PTHREAD_ONCE_INIT;

static void load_rnnoise(void) {
    void *lib = dlopen("librnnoise.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib)
        return;
    rn_create = (rn_create_fn)dlsym(lib, "rnnoise_create");
    rn_process = (rn_process_fn)dlsym(lib, "rnnoise_process_frame");
    rn_destroy = (rn_destroy_fn)dlsym(lib, "rnnoise_destroy");
    if (!rn_create || !rn_process || !rn_destroy) {
        rn_create = NULL;
        rn_process = NULL;
        rn_destroy = NULL;
        dlclose(lib);
    }
}

typedef struct {
    int rate;
    float in[2], hp[2], x1[2], x2[2], y1[2], y2[2], env, limiter_gain;
    void *rn;
    float rn_in[480], rn_out[480], dry[2][960];
    int dry_pos;
    int rn_pos;
} Audio;

JNIEXPORT jlong JNICALL Java_org_yourec_app_Native_audioCreate(JNIEnv *env, jobject self,
                                                               jint rate) {
    (void)env;
    (void)self;
    Audio *a = calloc(1, sizeof(*a));
    if (!a)
        return 0;
    a->rate = rate;
    a->limiter_gain = 1;
    pthread_once(&rn_once, load_rnnoise);
    if (rn_create && rate == 48000)
        a->rn = rn_create(NULL);
    return (jlong)(intptr_t)a;
}
JNIEXPORT jboolean JNICALL Java_org_yourec_app_Native_rnnoiseAvailable(JNIEnv *env,
                                                                       jobject self) {
    (void)env;
    (void)self;
    pthread_once(&rn_once, load_rnnoise);
    return rn_create != NULL;
}
JNIEXPORT void JNICALL Java_org_yourec_app_Native_audioDestroy(JNIEnv *env, jobject self,
                                                               jlong handle) {
    (void)env;
    (void)self;
    Audio *a = (Audio *)(intptr_t)handle;
    if (a && a->rn)
        rn_destroy(a->rn);
    free(a);
}
JNIEXPORT void JNICALL Java_org_yourec_app_Native_audioProcess(
    JNIEnv *env, jobject self, jlong handle, jfloatArray data, jint frames, jfloat gain,
    jfloat highpass, jfloat eq, jfloat frequency, jfloat compressor, jfloat gate,
    jfloat denoise, jfloat limiter) {
    (void)self;
    Audio *a = (Audio *)(intptr_t)handle;
    if (!a || frames < 0 || (*env)->GetArrayLength(env, data) / 2 < frames)
        return;
    float *p = (*env)->GetFloatArrayElements(env, data, NULL);
    if (!p)
        return;
    const float pi = 3.14159265358979323846f;
    float hp_alpha = expf(-2 * pi * fmaxf(highpass, 1) / a->rate);
    float omega = 2 * pi * fmaxf(20, fminf(frequency, a->rate * 0.45f)) / a->rate;
    float alpha = sinf(omega) / 1.4f;
    float amplitude = powf(10, eq / 40);
    float a0 = 1 + alpha / amplitude;
    float b0 = (1 + alpha * amplitude) / a0;
    float b1 = -2 * cosf(omega) / a0;
    float b2 = (1 - alpha * amplitude) / a0;
    float a1 = b1;
    float a2 = (1 - alpha / amplitude) / a0;
    for (int i = 0; i < frames; i++) {
        float input[2] = {p[2 * i], p[2 * i + 1]};
        if (denoise > 0 && a->rn) {
            a->rn_in[a->rn_pos] = (input[0] + input[1]) * 16384;
            float clean = a->rn_out[a->rn_pos] / 32768;
            for (int ch = 0; ch < 2; ch++) {
                float dry = a->dry[ch][a->dry_pos];
                a->dry[ch][a->dry_pos] = input[ch];
                input[ch] = dry + denoise * (clean - dry);
            }
            a->dry_pos = (a->dry_pos + 1) % 960;
            if (++a->rn_pos == 480) {
                rn_process(a->rn, a->rn_out, a->rn_in);
                a->rn_pos = 0;
            }
        }
        float peak = fmaxf(fabsf(input[0]), fabsf(input[1]));
        a->env += (peak > a->env ? 0.02f : 0.001f) * (peak - a->env);
        float comp = 1;
        if (compressor > 0 && a->env > 0.25f)
            comp = powf(a->env / 0.25f, -0.75f * compressor);
        float gate_gain = gate > 0 ? fminf(1, a->env / fmaxf(gate, 0.00001f)) : 1;
        float output[2];
        for (int ch = 0; ch < 2; ch++) {
            float x = input[ch];
            float h = hp_alpha * (a->hp[ch] + x - a->in[ch]);
            a->in[ch] = x;
            a->hp[ch] = h;
            if (highpass > 0)
                x = h;
            if (eq != 0) {
                float y =
                    b0 * x + b1 * a->x1[ch] + b2 * a->x2[ch] - a1 * a->y1[ch] - a2 * a->y2[ch];
                a->x2[ch] = a->x1[ch];
                a->x1[ch] = x;
                a->y2[ch] = a->y1[ch];
                a->y1[ch] = y;
                x = y;
            }
            x *= gain * comp * gate_gain;
            output[ch] = x;
        }
        peak = fmaxf(fabsf(output[0]), fabsf(output[1]));
        float target = peak > 0.95f ? 0.95f / peak : 1;
        if (target < a->limiter_gain)
            a->limiter_gain = target;
        else
            a->limiter_gain +=
                (1 - expf(-1.0f / (a->rate * 0.05f))) * (target - a->limiter_gain);
        float attenuation = 1 + limiter * (a->limiter_gain - 1);
        for (int ch = 0; ch < 2; ch++)
            p[2 * i + ch] = output[ch] * attenuation;
    }
    (*env)->ReleaseFloatArrayElements(env, data, p, 0);
}
