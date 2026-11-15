#ifndef MYREC_PIXELS_H
#define MYREC_PIXELS_H
#include "color_space.h"
#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static inline float yr_float(uint16_t h) {
#if defined(__aarch64__)
    __fp16 value; memcpy(&value, &h, 2); return (float)value;
#else
    uint32_t sign = (uint32_t)(h & 0x8000) << 16, exp = (h >> 10) & 31, mantissa = h & 1023, bits;
    if (exp == 0) {
        if (!mantissa) bits = sign;
        else {
            int shift = 0;
            while (!(mantissa & 1024)) { mantissa <<= 1; shift++; }
            bits = sign | (uint32_t)(113 - shift) << 23 | (mantissa & 1023) << 13;
        }
    } else bits = sign | (exp == 31 ? 255u : exp + 112) << 23 | mantissa << 13;
    float value; memcpy(&value, &bits, 4); return value;
#endif
}
static inline uint16_t yr_half(float value) {
    if (!isfinite(value)) value = 0;
    value = yr_clamp(value, -65504, 65504);
#if defined(__aarch64__)
    __fp16 half = (__fp16)value;
    uint16_t bits; memcpy(&bits, &half, 2); return bits;
#else
    uint32_t bits; memcpy(&bits, &value, 4);
    uint32_t sign = bits >> 16 & 0x8000, mantissa = bits & 0x7fffff;
    int exp = (int)(bits >> 23 & 255) - 127 + 15;
    if (exp <= 0) {
        if (exp < -10) return (uint16_t)sign;
        mantissa |= 0x800000;
        int shift = 14 - exp;
        uint32_t rounded = (mantissa + ((1u << (shift - 1)) - 1) + ((mantissa >> shift) & 1)) >> shift;
        return (uint16_t)(sign | rounded);
    }
    mantissa += 0xfff + ((mantissa >> 13) & 1);
    if (mantissa & 0x800000) { mantissa = 0; exp++; }
    return (uint16_t)(sign | (uint32_t)exp << 10 | mantissa >> 13);
#endif
}

typedef struct { AndroidBitmapInfo info; uint8_t *data; float linear[256]; } YrPixels;
static inline int yr_lock(JNIEnv *env, jobject bitmap, YrPixels *p) {
    int status = AndroidBitmap_getInfo(env, bitmap, &p->info);
    /* Android 10 exposes RGBA_F16 in Bitmap before libjnigraphics reports it. */
    if (status || p->info.format == ANDROID_BITMAP_FORMAT_NONE) {
        jclass bitmap_type = (*env)->GetObjectClass(env, bitmap);
        jclass config_type = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
        jmethodID get_config = (*env)->GetMethodID(env, bitmap_type, "getConfig", "()Landroid/graphics/Bitmap$Config;");
        jfieldID field = (*env)->GetStaticFieldID(env, config_type, "RGBA_F16", "Landroid/graphics/Bitmap$Config;");
        jobject config = (*env)->CallObjectMethod(env, bitmap, get_config);
        jobject fp16 = (*env)->GetStaticObjectField(env, config_type, field);
        if ((*env)->IsSameObject(env, config, fp16)) {
            p->info.format = ANDROID_BITMAP_FORMAT_RGBA_F16;
            p->info.width = (*env)->CallIntMethod(env, bitmap, (*env)->GetMethodID(env, bitmap_type, "getWidth", "()I"));
            p->info.height = (*env)->CallIntMethod(env, bitmap, (*env)->GetMethodID(env, bitmap_type, "getHeight", "()I"));
            p->info.stride = (*env)->CallIntMethod(env, bitmap, (*env)->GetMethodID(env, bitmap_type, "getRowBytes", "()I"));
            status = 0;
        }
    }
    if (status || (p->info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 && p->info.format != ANDROID_BITMAP_FORMAT_RGBA_F16)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (type) (*env)->ThrowNew(env, type, "Cannot access the working bitmap format");
        return 0;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, (void **)&p->data)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (type) (*env)->ThrowNew(env, type, "Cannot lock the working bitmap pixels");
        return 0;
    }
    for (int i = 0; i < 256; i++) p->linear[i] = yr_linear(i / 255.f);
    return 1;
}
static inline const uint8_t *yr_address(const YrPixels *p, int x, int y) {
    return p->data + (size_t)y * p->info.stride + x * (p->info.format == ANDROID_BITMAP_FORMAT_RGBA_F16 ? 8 : 4);
}
static inline void yr_read(const YrPixels *p, int x, int y, float c[4]) {
    const uint8_t *pixel = yr_address(p, x, y);
    if (p->info.format == ANDROID_BITMAP_FORMAT_RGBA_F16) {
        const uint16_t *v = (const uint16_t *)pixel;
        for (int i = 0; i < 4; i++) c[i] = yr_float(v[i]);
    } else {
        for (int i = 0; i < 3; i++) c[i] = p->linear[pixel[i]];
        c[3] = pixel[3] / 255.f;
    }
}
static inline void yr_write(const YrPixels *p, int x, int y, const float c[4]) {
    uint8_t *pixel = (uint8_t *)yr_address(p, x, y);
    if (p->info.format == ANDROID_BITMAP_FORMAT_RGBA_F16) {
        uint16_t *v = (uint16_t *)pixel;
        for (int i = 0; i < 4; i++) v[i] = yr_half(c[i]);
    } else {
        for (int i = 0; i < 3; i++) pixel[i] = yr_display(c[i]);
        pixel[3] = (uint8_t)lroundf(yr_clamp(c[3], 0, 1) * 255);
    }
}
static inline void yr_sample(const YrPixels *p, float x, float y, float c[4]) {
    x = yr_clamp(x, 0, p->info.width - 1); y = yr_clamp(y, 0, p->info.height - 1);
    int ix = (int)x, iy = (int)y, nx = ix < (int)p->info.width - 1 ? ix + 1 : ix, ny = iy < (int)p->info.height - 1 ? iy + 1 : iy;
    float a[4], b[4], d[4], e[4], dx = x - ix, dy = y - iy;
    yr_read(p, ix, iy, a); yr_read(p, nx, iy, b); yr_read(p, ix, ny, d); yr_read(p, nx, ny, e);
    for (int i = 0; i < 4; i++) c[i] = (1 - dy) * (a[i] + dx * (b[i] - a[i])) + dy * (d[i] + dx * (e[i] - d[i]));
}
static inline int yr_copy_pixels(const YrPixels *p, YrPixels *copy) {
    *copy = *p;
    copy->data = malloc((size_t)p->info.stride * p->info.height);
    if (!copy->data) return 0;
    memcpy(copy->data, p->data, (size_t)p->info.stride * p->info.height); return 1;
}
#endif
