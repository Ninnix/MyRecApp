#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "pixels.h"

static float clamp(float v, float lo, float hi) { return fminf(hi, fmaxf(lo, v)); }

JNIEXPORT void JNICALL Java_org_myrec_app_Native_rgbChannels(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength,
    jfloat red_x, jfloat red_y, jfloat green_x, jfloat green_y, jfloat blue_x, jfloat blue_y) {
    (void)self;
    if (strength <= 0 || (red_x == 0 && red_y == 0 && green_x == 0 && green_y == 0 &&
                         blue_x == 0 && blue_y == 0)) return;
    YrPixels image, source;
    if (!yr_lock(env, bitmap, &image)) return;
    if (!yr_copy_pixels(&image, &source)) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    AndroidBitmapInfo info = image.info;
    float reach = .12f * fminf(info.width, info.height);
    float dx[3] = {red_x * reach, green_x * reach, blue_x * reach};
    float dy[3] = {red_y * reach, green_y * reach, blue_y * reach};
    strength = clamp(strength, 0, 1);
    /* Sampling and intensity both operate in the reference's linear BT.709
     * working space. Interpolating display values would darken channel edges. */
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float out[4], sampled[4]; yr_read(&image, x, y, out);
        for (int c = 0; c < 3; c++) {
            yr_sample(&source, x - dx[c], y - dy[c], sampled);
            out[c] += strength * (sampled[c] - out[c]);
        }
        yr_write(&image, x, y, out);
    }
    free(source.data);
    AndroidBitmap_unlockPixels(env, bitmap);
}

static float reflected_uv(float value) {
    return 1 - fabsf(2 * (value * .5f - floorf(value * .5f)) - 1);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_kaleidoscope(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jint divisions,
    jfloat center_x, jfloat center_y, jfloat split_angle, jfloat pan_x, jfloat pan_y, jfloat source_angle) {
    (void)self;
    YrPixels image, source;
    if (!yr_lock(env, bitmap, &image)) return;
    if (!yr_copy_pixels(&image, &source)) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    AndroidBitmapInfo info = image.info;
    const float pi = 3.14159265358979323846f, turn = 2 * pi;
    int count = (int)clamp((float)divisions, 2, 9);
    float span = turn / count, axis = split_angle * pi / 180 - pi / 2;
    float angle = -source_angle * pi / 180, cs = cosf(angle), sn = sinf(angle);
    strength = clamp(strength, 0, 1);
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float u = (x + .5f) / info.width, v = (y + .5f) / info.height;
        float dx = (u - center_x) * info.width, dy = (v - center_y) * info.height;
        if (dx * dx + dy * dy >= 1e-8f) {
            float relative = (atan2f(dy, dx) - axis) / turn;
            float theta = (relative - floorf(relative)) * turn;
            int sector = (int)fminf(theta / span, count - 1);
            if (sector != 0) {
                float local = theta - sector * span;
                if (count % 2 == 0 && sector % 2 != 0) local = span - local;
                float mapped = axis + local, radius = hypotf(dx, dy);
                u = center_x + radius * cosf(mapped) / info.width;
                v = center_y + radius * sinf(mapped) / info.height;
            }
        }
        if (source_angle != 0) {
            float rx = (u - center_x) * info.width, ry = (v - center_y) * info.height;
            u = center_x + (cs * rx - sn * ry) / info.width;
            v = center_y + (sn * rx + cs * ry) / info.height;
        }
        u -= pan_x; v -= pan_y;
        if (u < 0 || u > 1 || v < 0 || v > 1) { u = reflected_uv(u); v = reflected_uv(v); }
        float out[4], sampled[4]; yr_read(&image, x, y, out);
        for (int c = 0; c < 3; c++) {
            yr_sample(&source, u * info.width - .5f, v * info.height - .5f, sampled);
            out[c] += strength * (sampled[c] - out[c]);
        }
        yr_write(&image, x, y, out);
    }
    free(source.data);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_widescreen(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloat ratio,
    jint bars, jfloat flares, jint tint) {
    (void)self;
    YrPixels image;
    if (strength <= 0 || !yr_lock(env, bitmap, &image)) return;
    AndroidBitmapInfo info = image.info;
    float content = fminf(info.height, info.width / fmaxf(1, ratio));
    float edge = (info.height - content) / 2;
    float color[3] = {(tint >> 16 & 255) / 255.f, (tint >> 8 & 255) / 255.f,
                      (tint & 255) / 255.f};
    float *streak = calloc(info.width, sizeof(float));
    float decay = expf(-1.f / fmaxf(1, info.width * .055f));
    for (unsigned y = 0; y < info.height; y++) {
        if (streak && flares > 0 && y >= edge && y < info.height - edge) {
            float tail = 0;
            for (unsigned x = 0; x < info.width; x++) {
                float p[4]; yr_read(&image, x, y, p);
                for (int c = 0; c < 3; c++) p[c] = yr_srgb(p[c]) * 255;
                float value = fmaxf(0, ((.2126f*p[0]+.7152f*p[1]+.0722f*p[2]) / 255.f - .9f) / .1f);
                tail = tail * decay + value * (1 - decay);
                streak[x] = tail;
            }
            tail = 0;
            for (int x = (int)info.width - 1; x >= 0; x--) {
                float p[4]; yr_read(&image, x, y, p);
                for (int c = 0; c < 3; c++) p[c] = yr_srgb(p[c]) * 255;
                float value = fmaxf(0, ((.2126f*p[0]+.7152f*p[1]+.0722f*p[2]) / 255.f - .9f) / .1f);
                tail = tail * decay + value * (1 - decay);
                for (int c = 0; c < 3; c++)
                    p[c] = clamp(p[c] + strength * flares * 600 *
                                          (streak[x] + tail) * color[c], 0, 255);
                for (int c = 0; c < 3; c++) p[c] = yr_linear(p[c] / 255);
                yr_write(&image, x, y, p);
            }
        }
        if (y < edge || y >= info.height - edge) for (unsigned x = 0; x < info.width; x++) {
            float p[4]; yr_read(&image, x, y, p);
            for (int c = 0; c < 3; c++) p[c] = yr_srgb(p[c]) * 255;
            for (int c = 0; c < 3; c++) {
                float value = bars == 1 ? 255 : 0;
                p[c] = clamp(p[c] + strength * (value - p[c]), 0, 255);
            }
            for (int c = 0; c < 3; c++) p[c] = yr_linear(p[c] / 255);
            yr_write(&image, x, y, p);
        }
    }
    free(streak);
    AndroidBitmap_unlockPixels(env, bitmap);
}
