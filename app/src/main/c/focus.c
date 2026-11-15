#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>

static float smooth(float low, float high, float value) {
    float t = fminf(1, fmaxf(0, (value - low) / (high - low)));
    return t * t * (3 - 2 * t);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_focusPeaking(JNIEnv *env, jobject self,
                                                            jobject bitmap) {
    (void)self;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0 ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || !info.width || !info.height) return;
    size_t count = (size_t)info.width * info.height;
    if (count > SIZE_MAX / (2 * sizeof(float))) return;
    float *light = malloc(count * 2 * sizeof(float));
    if (!light) return;
    uint8_t *data;
    if (AndroidBitmap_lockPixels(env, bitmap, (void **)&data) < 0) { free(light); return; }
    float *edge = light + count;
    int w = info.width, h = info.height;
    // The upstream focus aid measures display-space detail, not linear scene light.
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        uint8_t *p = data + (size_t)y * info.stride + x * 4;
        light[(size_t)y * w + x] = (.2126f * p[0] + .7152f * p[1] + .0722f * p[2]) / 255;
    }
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        size_t at = (size_t)y * w + x;
        float l = light[(size_t)y * w + (x ? x - 1 : 0)];
        float r = light[(size_t)y * w + (x + 1 < w ? x + 1 : x)];
        float t = light[(size_t)(y ? y - 1 : 0) * w + x];
        float b = light[(size_t)(y + 1 < h ? y + 1 : y) * w + x];
        float center = light[at];
        float detail = fmaxf(fabsf(l + r - 2 * center), fabsf(t + b - 2 * center));
        edge[at] = smooth(.065f, .16f, hypotf(r - l, b - t)) * smooth(.017f, .06f, detail);
    }
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        size_t at = (size_t)y * w + x;
        float strength = fmaxf(edge[at], .9f * fmaxf(
            edge[(size_t)y * w + (x + 1 < w ? x + 1 : x)],
            edge[(size_t)(y + 1 < h ? y + 1 : y) * w + x]));
        uint8_t *p = data + (size_t)y * info.stride + x * 4;
        const float red[3] = {255, .045f * 255, .025f * 255};
        for (int c = 0; c < 3; c++) p[c] = (uint8_t)lroundf(p[c] + (red[c] - p[c]) * strength);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    free(light);
}
