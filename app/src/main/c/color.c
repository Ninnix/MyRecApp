#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static float limit(float v, float lo, float hi) { return fmaxf(lo, fminf(hi, v)); }
static uint8_t byte(float v) { return (uint8_t)limit(roundf(v), 0, 255); }
static float luma(const uint8_t *p) { return .299f * p[0] + .587f * p[1] + .114f * p[2]; }

JNIEXPORT void JNICALL Java_org_yourec_app_Native_colorCorrect(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloat exposure,
    jfloat contrast, jfloat saturation, jfloat temperature, jfloat tint) {
    (void)self;
    if (strength <= 0 ||
        (exposure == 0 && contrast == 1 && saturation == 1 && temperature == 0 && tint == 0))
        return;
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels))
        return;
    float gain = exp2f(limit(exposure, -5, 5) / 2.2f);
    float white[3] = {1 + temperature * .2f, 1 + tint * .15f, 1 - temperature * .2f};
    strength = limit(strength, 0, 1);
    for (unsigned y = 0; y < info.height; y++)
        for (unsigned x = 0; x < info.width; x++) {
            uint8_t *p = pixels + y * info.stride + x * 4;
            float rgb[3];
            for (int ch = 0; ch < 3; ch++)
                rgb[ch] = (p[ch] * gain * white[ch] - 127.5f) * contrast + 127.5f;
            float gray = .299f * rgb[0] + .587f * rgb[1] + .114f * rgb[2];
            for (int ch = 0; ch < 3; ch++) {
                float corrected = gray + (rgb[ch] - gray) * saturation;
                p[ch] = byte(p[ch] + strength * (corrected - p[ch]));
            }
        }
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_yourec_app_Native_night(JNIEnv *env, jobject self,
                                                        jobject bitmap, jfloat strength,
                                                        jfloat cleanup) {
    (void)self;
    if (strength <= 0)
        return;
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels))
        return;
    size_t size = (size_t)info.stride * info.height;
    uint8_t *source = malloc(size);
    if (!source) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    memcpy(source, pixels, size);
    strength = limit(strength, 0, 1);
    cleanup = limit(cleanup, 0, 1);
    uint8_t lifted[256];
    for (int i = 0; i < 256; i++)
        lifted[i] = byte(255 * powf(i / 255.0f, .65f));
    for (unsigned y = 0; y < info.height; y++)
        for (unsigned x = 0; x < info.width; x++) {
            const uint8_t *p = source + y * info.stride + x * 4;
            float sum[3] = {0}, weights = 0, center = luma(p);
            float sigma = 12 + (1 - center / 255) * 22;
            if (cleanup > 0)
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = (int)x + dx, yy = (int)y + dy;
                        if (xx < 0 || yy < 0 || xx >= (int)info.width || yy >= (int)info.height)
                            continue;
                        const uint8_t *q = source + yy * info.stride + xx * 4;
                        float difference = (luma(q) - center) / sigma;
                        float weight =
                            1 / ((1 + dx * dx + dy * dy) * (1 + difference * difference));
                        for (int ch = 0; ch < 3; ch++)
                            sum[ch] += q[ch] * weight;
                        weights += weight;
                    }
            uint8_t *out = pixels + y * info.stride + x * 4;
            for (int ch = 0; ch < 3; ch++) {
                float clean =
                    weights > 0 ? p[ch] + cleanup * (sum[ch] / weights - p[ch]) : p[ch];
                out[ch] = byte(p[ch] + strength * (lifted[byte(clean)] - p[ch]));
            }
        }
    free(source);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_yourec_app_Native_temporal(JNIEnv *env, jobject self,
                                                           jobject bitmap, jobject previous,
                                                           jfloatArray motion,
                                                           jfloat strength) {
    (void)self;
    if (strength <= 0 || (*env)->GetArrayLength(env, motion) < 5)
        return;
    float transform[5];
    (*env)->GetFloatArrayRegion(env, motion, 0, 5, transform);
    float a = transform[0], b = transform[1], tx = transform[2], ty = transform[3];
    float determinant = a * a + b * b;
    if (transform[4] < 12 || determinant < .5f || determinant > 2)
        return;
    AndroidBitmapInfo info, old;
    uint8_t *pixels, *source;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        AndroidBitmap_getInfo(env, previous, &old) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || old.format != info.format ||
        info.width != old.width || info.height != old.height ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels))
        return;
    if (AndroidBitmap_lockPixels(env, previous, (void **)&source)) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    for (unsigned y = 0; y < info.height; y++)
        for (unsigned x = 0; x < info.width; x++) {
            float xx = (a * (x - tx) + b * (y - ty)) / determinant;
            float yy = (-b * (x - tx) + a * (y - ty)) / determinant;
            if (xx < 0 || yy < 0 || xx >= old.width - 1 || yy >= old.height - 1)
                continue;
            int ix = (int)xx, iy = (int)yy;
            float fx = xx - ix, fy = yy - iy, rgb[3], difference = 0;
            uint8_t *p = pixels + y * info.stride + x * 4;
            const uint8_t *q = source + iy * old.stride + ix * 4;
            for (int ch = 0; ch < 3; ch++) {
                rgb[ch] = q[ch] * (1 - fx) * (1 - fy) + q[ch + 4] * fx * (1 - fy) +
                          q[old.stride + ch] * (1 - fx) * fy + q[old.stride + ch + 4] * fx * fy;
                difference = fmaxf(difference, fabsf(rgb[ch] - p[ch]));
            }
            // Reject moving subjects even when the background transform is reliable.
            float weight = limit(strength, 0, 1) * .45f * limit(1 - difference / 40, 0, 1);
            for (int ch = 0; ch < 3; ch++)
                p[ch] = byte(p[ch] + weight * (rgb[ch] - p[ch]));
        }
    AndroidBitmap_unlockPixels(env, previous);
    AndroidBitmap_unlockPixels(env, bitmap);
}
