#include <android/bitmap.h>
#include <jni.h>
#include "pixels.h"

static void gamut(float c[3]) {
    float y = yr_clamp(yr_luma(c), 0, 1), amount = 1;
    for (int i = 0; i < 3; i++) {
        float delta = c[i] - y;
        if (delta > 0) amount = fminf(amount, (1 - y) / delta);
        else if (delta < 0) amount = fminf(amount, -y / delta);
    }
    for (int i = 0; i < 3; i++) c[i] = y + (c[i] - y) * fmaxf(0, amount);
}

static float shoulder(float value, float maximum) {
    if (maximum <= 1 || value <= .8f) return value;
    float a = (maximum - .8f) / .2f, t = (value - .8f) / (maximum - .8f);
    return .8f + .2f * a * t / (1 + (a - 1) * t);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_primaryColor(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloatArray parameters) {
    (void)self;
    if (strength <= 0 || !parameters || (*env)->GetArrayLength(env, parameters) != 12) return;
    float p[12];
    (*env)->GetFloatArrayRegion(env, parameters, 0, 12, p);
    if ((*env)->ExceptionCheck(env)) return;
    if (p[0] == 0 && p[1] == 1 && p[2] == 0 && p[3] == 0 && p[4] == 0 && p[5] == 0 &&
        p[7] == 0 && p[8] == 0 && p[9] == 1 && p[10] == 0 && p[11] == 0) return;
    YrPixels image;
    if (!yr_lock(env, bitmap, &image)) return;
    AndroidBitmapInfo info = image.info;
    float gains[3] = {exp2f(.45f * p[7] + .2f * p[8]), exp2f(-.2f * p[8]), exp2f(-.45f * p[7] + .2f * p[8])};
    float white = yr_luma(gains), exposure = exp2f(p[0]);
    for (int i = 0; i < 3; i++) gains[i] *= exposure / white;
    float angle = p[11] * .01745329252f, cs = cosf(angle), sn = sinf(angle);
    strength = yr_clamp(strength, 0, 1);
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float pixel[4]; yr_read(&image, x, y, pixel);
        float original[3], c[3];
        for (int i = 0; i < 3; i++) c[i] = (original[i] = pixel[i]) * gains[i];
        float before = yr_luma(c), v = yr_srgb(fmaxf(before, 0));
        v = shoulder(v, yr_srgb(exposure));
        if (p[1] != 1 && v > 0 && v < 1)
            v = 1 / (1 + powf(p[6] / (1 - p[6]), p[1] - 1) * powf((1 - v) / v, p[1]));
        v += p[2] * v * (1 - v) * (1 - v);
        v += p[3] * v * v * (1 - v);
        v += .12f * p[4] * powf(1 - v, 4) + .12f * p[5] * powf(v, 4);
        v = shoulder(v, 1 + .12f * p[5]);
        v = 1 - shoulder(1 - v, 1 - .12f * p[4]);
        float target = yr_linear(v);
        for (int i = 0; i < 3; i++) c[i] = before > 1e-7f ? c[i] * (target / before) : target;
        float perceptual[3], encoded[3], h[3];
        for (int i = 0; i < 3; i++) encoded[i] = fmaxf(c[i], 0);
        yr_oklab(encoded, perceptual);
        float chroma = hypotf(perceptual[1], perceptual[2]);
        for (int i = 0; i < 3; i++) encoded[i] = yr_srgb(encoded[i]);
        yr_hsv(encoded, h);
        float skin = yr_smooth(.025f, .055f, h[0]) * (1 - yr_smooth(.12f, .16f, h[0])) * yr_smooth(.08f, .2f, h[1]);
        float vibrance = p[10] * (1 - yr_clamp(chroma / .25f, 0, 1));
        if (vibrance > 0) vibrance *= 1 - .6f * skin;
        if (p[11] != 0 && chroma > 1e-5f) {
            float a = cs * perceptual[1] - sn * perceptual[2];
            perceptual[2] = sn * perceptual[1] + cs * perceptual[2];
            perceptual[1] = a;
            yr_unlab(perceptual, c);
            float scale = target / fmaxf(yr_luma(c), 1e-7f);
            for (int i = 0; i < 3; i++) c[i] *= scale;
        }
        gamut(c);
        float gain = p[9] * (1 + vibrance);
        if (gain > 1) {
            float ratio = 0;
            for (int i = 0; i < 3; i++) {
                float delta = c[i] - target;
                ratio = fmaxf(ratio, delta > 0 ? delta / fmaxf(1 - target, 1e-7f) : -delta / fmaxf(target, 1e-7f));
            }
            gain /= sqrtf(1 + (gain * gain - 1) * ratio * ratio);
        }
        for (int i = 0; i < 3; i++) c[i] = target + (c[i] - target) * gain;
        gamut(c);
        for (int i = 0; i < 3; i++) pixel[i] = original[i] + strength * (yr_clamp(c[i], 0, 1) - original[i]);
        yr_write(&image, x, y, pixel);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}
