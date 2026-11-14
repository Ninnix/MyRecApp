#include "pixels.h"

static float gray(const float p[4]) { return .299f * p[0] + .587f * p[1] + .114f * p[2]; }
static void encoded(const YrPixels *p, int x, int y, float c[4]) {
    yr_read(p, x, y, c);
    for (int i = 0; i < 3; i++) c[i] = yr_srgb(c[i]) * 255;
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_night(JNIEnv *env, jobject self,
    jobject bitmap, jfloat strength, jfloat cleanup) {
    (void)self;
    if (strength <= 0) return;
    YrPixels image, source;
    if (!yr_lock(env, bitmap, &image)) return;
    if (!yr_copy_pixels(&image, &source)) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    strength = yr_clamp(strength, 0, 1); cleanup = yr_clamp(cleanup, 0, 1);
    for (unsigned y = 0; y < image.info.height; y++) for (unsigned x = 0; x < image.info.width; x++) {
        float p[4]; encoded(&source, x, y, p);
        float sum[3] = {0}, weights = 0, center = gray(p), sigma = 12 + (1 - center / 255) * 22;
        if (cleanup > 0) for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            int xx = (int)x + dx, yy = (int)y + dy;
            if (xx < 0 || yy < 0 || xx >= (int)image.info.width || yy >= (int)image.info.height) continue;
            float q[4]; encoded(&source, xx, yy, q);
            float difference = (gray(q) - center) / sigma;
            float weight = 1 / ((1 + dx * dx + dy * dy) * (1 + difference * difference));
            for (int ch = 0; ch < 3; ch++) sum[ch] += q[ch] * weight;
            weights += weight;
        }
        for (int ch = 0; ch < 3; ch++) {
            float clean = weights > 0 ? p[ch] + cleanup * (sum[ch] / weights - p[ch]) : p[ch];
            float lifted = powf(yr_clamp(clean / 255, 0, 1), .65f) * 255;
            p[ch] = yr_linear(yr_clamp(p[ch] + strength * (lifted - p[ch]), 0, 255) / 255);
        }
        yr_write(&image, x, y, p);
    }
    free(source.data); AndroidBitmap_unlockPixels(env, bitmap);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_temporal(JNIEnv *env, jobject self,
    jobject bitmap, jobject previous, jfloatArray motion, jfloat strength) {
    (void)self;
    if (strength <= 0 || (*env)->GetArrayLength(env, motion) < 5) return;
    float transform[5]; (*env)->GetFloatArrayRegion(env, motion, 0, 5, transform);
    float a = transform[0], b = transform[1], tx = transform[2], ty = transform[3], determinant = a * a + b * b;
    if (transform[4] < 12 || determinant < .5f || determinant > 2) return;
    YrPixels image, source;
    if (!yr_lock(env, bitmap, &image)) return;
    if (!yr_lock(env, previous, &source)) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    if (image.info.width == source.info.width && image.info.height == source.info.height)
        for (unsigned y = 0; y < image.info.height; y++) for (unsigned x = 0; x < image.info.width; x++) {
            float xx = (a * (x - tx) + b * (y - ty)) / determinant, yy = (-b * (x - tx) + a * (y - ty)) / determinant;
            if (xx < 0 || yy < 0 || xx >= source.info.width - 1 || yy >= source.info.height - 1) continue;
            float p[4], q[4], difference = 0;
            yr_read(&image, x, y, p); yr_sample(&source, xx, yy, q);
            for (int ch = 0; ch < 3; ch++) difference = fmaxf(difference, fabsf(yr_srgb(q[ch]) - yr_srgb(p[ch])) * 255);
            float weight = yr_clamp(strength, 0, 1) * .45f * yr_clamp(1 - difference / 40, 0, 1);
            for (int ch = 0; ch < 3; ch++) p[ch] += weight * (q[ch] - p[ch]);
            yr_write(&image, x, y, p);
        }
    AndroidBitmap_unlockPixels(env, previous); AndroidBitmap_unlockPixels(env, bitmap);
}
