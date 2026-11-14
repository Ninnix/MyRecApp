#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <onnxruntime_c_api.h>
#include <stdint.h>
#include <stdlib.h>

#define SIDE 192
#define AREA (SIDE * SIDE)

typedef struct {
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *session;
    OrtMemoryInfo *memory;
    OrtValue *input;
    float pixels[3 * AREA];
} Person;

static void failure(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type)
        (*env)->ThrowNew(env, type, message);
}

static int checked(JNIEnv *env, Person *p, OrtStatus *status) {
    if (!status)
        return 1;
    failure(env, p->api->GetErrorMessage(status));
    p->api->ReleaseStatus(status);
    return 0;
}

static void release(Person *p) {
    if (!p)
        return;
    if (p->input)
        p->api->ReleaseValue(p->input);
    if (p->memory)
        p->api->ReleaseMemoryInfo(p->memory);
    if (p->session)
        p->api->ReleaseSession(p->session);
    if (p->env)
        p->api->ReleaseEnv(p->env);
    free(p);
}

JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_personLoad(JNIEnv *env, jobject self,
                                                              jstring path) {
    (void)self;
    Person *p = calloc(1, sizeof(*p));
    if (!p) {
        failure(env, "Not enough memory for the person effect");
        return 0;
    }
    p->api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!p->api) {
        free(p);
        failure(env, "Person effect runtime is unavailable");
        return 0;
    }
    const OrtApi *api = p->api;
    OrtSessionOptions *options = NULL;
    const char *file = (*env)->GetStringUTFChars(env, path, NULL);
    if (!file) {
        release(p);
        return 0;
    }
    int64_t dims[] = {1, 3, SIDE, SIDE};
    int ok =
        checked(env, p, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "myrec-person", &p->env)) &&
        checked(env, p, api->CreateSessionOptions(&options)) &&
        checked(env, p, api->SetIntraOpNumThreads(options, 2)) &&
        checked(env, p, api->SetInterOpNumThreads(options, 1)) &&
        checked(env, p,
                api->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0")) &&
        checked(env, p, api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) &&
        checked(env, p, api->CreateSession(p->env, file, options, &p->session)) &&
        checked(env, p,
                api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &p->memory)) &&
        checked(env, p,
                api->CreateTensorWithDataAsOrtValue(
                    p->memory, p->pixels, sizeof(p->pixels), dims, 4,
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &p->input));
    (*env)->ReleaseStringUTFChars(env, path, file);
    if (options)
        api->ReleaseSessionOptions(options);
    if (!ok) {
        release(p);
        return 0;
    }
    return (jlong)(intptr_t)p;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_personFree(JNIEnv *env, jobject self,
                                                             jlong handle) {
    (void)env;
    (void)self;
    release((Person *)(intptr_t)handle);
}

static float clamp(float value, float low, float high) {
    return fmaxf(low, fminf(high, value));
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_personMask(JNIEnv *env, jobject self,
                                                                    jlong handle,
                                                                    jobject bitmap) {
    (void)self;
    Person *p = (Person *)(intptr_t)handle;
    if (!p) {
        failure(env, "Person effect is not ready");
        return NULL;
    }
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width < 1 || info.height < 1 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels)) {
        failure(env, "Cannot read the person effect frame");
        return NULL;
    }
    for (int y = 0; y < SIDE; y++)
        for (int x = 0; x < SIDE; x++) {
            float sx = clamp((x + .5f) * info.width / SIDE - .5f, 0, (float)info.width - 1);
            float sy = clamp((y + .5f) * info.height / SIDE - .5f, 0, (float)info.height - 1);
            int a = (int)sx, b = (int)sy;
            int c = a + 1 < (int)info.width ? a + 1 : a;
            int d = b + 1 < (int)info.height ? b + 1 : b;
            float fx = sx - a, fy = sy - b;
            for (int ch = 0; ch < 3; ch++) {
                float value = pixels[b * info.stride + a * 4 + ch] * (1 - fx) * (1 - fy) +
                              pixels[b * info.stride + c * 4 + ch] * fx * (1 - fy) +
                              pixels[d * info.stride + a * 4 + ch] * (1 - fx) * fy +
                              pixels[d * info.stride + c * 4 + ch] * fx * fy;
                p->pixels[ch * AREA + y * SIDE + x] = roundf(value) / 127.5f - 1;
            }
        }
    AndroidBitmap_unlockPixels(env, bitmap);
    const char *names[] = {"x"}, *outputs[] = {"save_infer_model/scale_0.tmp_1"};
    const OrtValue *inputs[] = {p->input};
    OrtValue *output = NULL;
    OrtTensorTypeAndShapeInfo *shape = NULL;
    jfloatArray result = NULL;
    size_t count = 0;
    float *mask;
    const OrtApi *api = p->api;
    if (!checked(env, p, api->Run(p->session, NULL, names, inputs, 1, outputs, 1, &output)) ||
        !checked(env, p, api->GetTensorTypeAndShape(output, &shape)) ||
        !checked(env, p, api->GetTensorShapeElementCount(shape, &count)))
        goto done;
    if (count != AREA * 2) {
        failure(env, "Unexpected person effect output");
        goto done;
    }
    if (!checked(env, p, api->GetTensorMutableData(output, (void **)&mask)))
        goto done;
    result = (*env)->NewFloatArray(env, AREA);
    if (result)
        (*env)->SetFloatArrayRegion(env, result, 0, AREA, mask + AREA);
done:
    if (shape)
        api->ReleaseTensorTypeAndShapeInfo(shape);
    if (output)
        api->ReleaseValue(output);
    return result;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_isolate(JNIEnv *env, jobject self,
                                                          jobject bitmap, jfloat strength,
                                                          jfloat hue, jfloat tolerance,
                                                          jfloat softness, jfloatArray person) {
    (void)self;
    if (strength <= 0)
        return;
    if (person && (*env)->GetArrayLength(env, person) != AREA)
        return;
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels))
        return;
    float *mask = person ? (*env)->GetFloatArrayElements(env, person, NULL) : NULL;
    if (person && !mask) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    strength = clamp(strength, 0, 1);
    softness = clamp(softness, 0, 1);
    for (unsigned y = 0; y < info.height; y++)
        for (unsigned x = 0; x < info.width; x++) {
            uint8_t *p = pixels + y * info.stride + x * 4;
            float gray = .299f * p[0] + .587f * p[1] + .114f * p[2];
            float keep;
            if (mask) {
                float sx = clamp((x + .5f) * SIDE / info.width - .5f, 0, SIDE - 1);
                float sy = clamp((y + .5f) * SIDE / info.height - .5f, 0, SIDE - 1);
                int a = (int)sx, b = (int)sy, c = a < SIDE - 1 ? a + 1 : a,
                    d = b < SIDE - 1 ? b + 1 : b;
                float fx = sx - a, fy = sy - b;
                keep = mask[b * SIDE + a] * (1 - fx) * (1 - fy) +
                       mask[b * SIDE + c] * fx * (1 - fy) + mask[d * SIDE + a] * (1 - fx) * fy +
                       mask[d * SIDE + c] * fx * fy;
                keep = clamp((keep - .5f) / fmaxf(.001f, softness) + .5f, 0, 1);
            } else {
                float high = fmaxf(p[0], fmaxf(p[1], p[2])),
                      low = fminf(p[0], fminf(p[1], p[2]));
                float delta = high - low, angle = 0;
                if (delta > .001f) {
                    if (high == p[0])
                        angle = 60 * (p[1] - p[2]) / delta;
                    else if (high == p[1])
                        angle = 120 + 60 * (p[2] - p[0]) / delta;
                    else
                        angle = 240 + 60 * (p[0] - p[1]) / delta;
                }
                float distance = fabsf(remainderf(angle - hue, 360));
                float edge = fmaxf(.01f, softness * 60);
                keep = clamp((tolerance + edge - distance) / (edge * 2), 0, 1) *
                       clamp(delta / 20, 0, 1);
            }
            keep = keep * keep * (3 - 2 * keep);
            for (int ch = 0; ch < 3; ch++)
                p[ch] = (uint8_t)clamp(roundf(p[ch] + strength * (gray - p[ch]) * (1 - keep)),
                                       0, 255);
        }
    if (mask)
        (*env)->ReleaseFloatArrayElements(env, person, mask, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, bitmap);
}
