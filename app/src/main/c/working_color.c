#include "pixels.h"

JNIEXPORT void JNICALL Java_org_myrec_app_Native_workingToneMap(JNIEnv *env, jobject self, jobject bitmap) {
    (void)self; YrPixels p;
    if (!yr_lock(env, bitmap, &p)) return;
    for (unsigned y = 0; y < p.info.height; y++) for (unsigned x = 0; x < p.info.width; x++) {
        float c[4]; yr_read(&p, x, y, c); yr_tone(c); yr_write(&p, x, y, c);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_workingDisplay(JNIEnv *env, jobject self,
    jobject bitmap, jobject output, jboolean map) {
    (void)self; YrPixels p, q;
    if (!yr_lock(env, bitmap, &p)) return;
    if (!yr_lock(env, output, &q)) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    if (p.info.width == q.info.width && p.info.height == q.info.height)
        for (unsigned y = 0; y < p.info.height; y++) for (unsigned x = 0; x < p.info.width; x++) {
            float c[4]; yr_read(&p, x, y, c); if (map) yr_tone(c); yr_write(&q, x, y, c);
        }
    AndroidBitmap_unlockPixels(env, output); AndroidBitmap_unlockPixels(env, bitmap);
}

#include <android/native_window_jni.h>
#include <android/data_space.h>
JNIEXPORT jboolean JNICALL Java_org_myrec_app_Native_hlgSurface(JNIEnv *env, jobject self, jobject surface) {
    (void)self;
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;
    int result = ANativeWindow_setBuffersDataSpace(window, ADATASPACE_BT2020_HLG);
    ANativeWindow_release(window);
    return result == 0;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_workingAverage(JNIEnv *env, jobject self,
    jobject bitmap, jobject other, jfloat weight) {
    (void)self; YrPixels p, q;
    if (!yr_lock(env, bitmap, &p)) return;
    if (!yr_lock(env, other, &q)) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    if (p.info.width == q.info.width && p.info.height == q.info.height)
        for (unsigned y = 0; y < p.info.height; y++) for (unsigned x = 0; x < p.info.width; x++) {
            float c[4], d[4]; yr_read(&p, x, y, c); yr_read(&q, x, y, d);
            for (int i = 0; i < 4; i++) c[i] += yr_clamp(weight, 0, 1) * (d[i] - c[i]);
            yr_write(&p, x, y, c);
        }
    AndroidBitmap_unlockPixels(env, other); AndroidBitmap_unlockPixels(env, bitmap);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_workingNegative(JNIEnv *env, jobject self,
    jobject bitmap, jfloat strength) {
    (void)self; YrPixels p;
    if (strength <= 0 || !yr_lock(env, bitmap, &p)) return;
    for (unsigned y = 0; y < p.info.height; y++) for (unsigned x = 0; x < p.info.width; x++) {
        float c[4]; yr_read(&p, x, y, c);
        for (int i = 0; i < 3; i++) c[i] += yr_clamp(strength, 0, 1) * (yr_linear(1 - yr_srgb(yr_clamp(c[i], 0, 1))) - c[i]);
        yr_write(&p, x, y, c);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}
