#include "pixels.h"
#include <android/bitmap.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

static float at(const float *p, int w, int h, int channels, float x, float y, int c) {
    x = yr_clamp(x, 0, w - 1); y = yr_clamp(y, 0, h - 1);
    int a = (int)x, b = (int)y, d = a < w - 1 ? a + 1 : a, e = b < h - 1 ? b + 1 : b;
    float dx = x - a, dy = y - b;
    return (p[(b * w + a) * channels + c] * (1 - dx) + p[(b * w + d) * channels + c] * dx) * (1 - dy) +
        (p[(e * w + a) * channels + c] * (1 - dx) + p[(e * w + d) * channels + c] * dx) * dy;
}

static int gaussian(float *p, int w, int h, float sigma) {
    int radius = (int)ceilf(3 * sigma);
    if (radius < 1) return 1;
    float *weights = malloc((size_t)(radius + 1) * sizeof(float));
    float *tmp = malloc((size_t)w * h * 4 * sizeof(float));
    if (!weights || !tmp) { free(weights); free(tmp); return 0; }
    float total = 0;
    for (int i = 0; i <= radius; i++) { weights[i] = expf(-.5f * i * i / (sigma * sigma)); total += weights[i] * (i ? 2 : 1); }
    for (int i = 0; i <= radius; i++) weights[i] /= total;
    for (int pass = 0; pass < 2; pass++) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) for (int c = 0; c < 4; c++) {
            float value = 0;
            for (int i = -radius; i <= radius; i++) {
                int xx = pass ? x : (int)yr_clamp(x + i, 0, w - 1);
                int yy = pass ? (int)yr_clamp(y + i, 0, h - 1) : y;
                value += p[(yy * w + xx) * 4 + c] * weights[abs(i)];
            }
            tmp[(y * w + x) * 4 + c] = value;
        }
        memcpy(p, tmp, (size_t)w * h * 4 * sizeof(float));
    }
    free(weights); free(tmp); return 1;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_dolly(JNIEnv *env, jobject self,
    jobject bitmap, jfloatArray matte, jfloat scale, jfloat target_x, jfloat target_y, jfloat strength) {
    (void)self;
    if ((*env)->GetArrayLength(env, matte) != 192 * 192 || !isfinite(scale) || scale < 1 || scale > 1.5f ||
        !isfinite(target_x) || !isfinite(target_y) || !isfinite(strength)) return;
    YrPixels image;
    AndroidBitmapInfo info;
    int locked = 0;
    if (AndroidBitmap_getInfo(env, bitmap, &info)) return;
    float *mask = (*env)->GetFloatArrayElements(env, matte, NULL);
    if (!mask) return;
    float *layers[24] = {0}; int widths[24], heights[24], levels = 1;
    int w = info.width / 4, h = info.height / 4;
    if (w < 2) w = 2;
    if (h < 2) h = 2;
    widths[0] = w; heights[0] = h;
    layers[0] = calloc((size_t)w * h * 4, sizeof(float));
    if (!layers[0] || !yr_lock(env, bitmap, &image)) goto done;
    locked = 1;
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
        float u = (x + .5f) / w, v = (y + .5f) / h;
        int sx = (int)yr_clamp(u * info.width, 0, info.width - 1), sy = (int)yr_clamp(v * info.height, 0, info.height - 1);
        float pixel[4]; yr_read(&image, sx, sy, pixel);
        float weight = 1 - yr_clamp(at(mask, 192, 192, 1, u * 192 - .5f, v * 192 - .5f, 0), 0, 1);
        float *p = layers[0] + (y * w + x) * 4;
        for (int c = 0; c < 3; c++) p[c] = pixel[c] * weight;
        p[3] = weight;
    }
    if (!gaussian(layers[0], w, h, fmaxf(.5f, 4.f * fminf(info.width, info.height) / 1080))) goto done;
    while ((w > 2 || h > 2) && levels < 24) {
        int nw = w / 2, nh = h / 2;
        if (nw < 2) nw = 2;
        if (nh < 2) nh = 2;
        float *layer = calloc((size_t)nw * nh * 4, sizeof(float));
        if (!layer) goto done;
        layers[levels] = layer; widths[levels] = nw; heights[levels] = nh;
        for (int y = 0; y < nh; y++) for (int x = 0; x < nw; x++) for (int c = 0; c < 4; c++)
            layer[(y * nw + x) * 4 + c] = at(layers[levels - 1], w, h, 4, (x + .5f) * w / nw - .5f, (y + .5f) * h / nh - .5f, c);
        levels++;
        if (!gaussian(layer, nw, nh, 2)) goto done;
        w = nw; h = nh;
    }
    // Resolve broad unknown regions first, then restore local background detail.
    for (int i = levels - 1; i >= 0; i--) {
        w = widths[i]; h = heights[i]; float *p = layers[i];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            float *value = p + (y * w + x) * 4;
            if (value[3] > .001f) for (int c = 0; c < 3; c++) value[c] /= value[3];
            else if (i < levels - 1) for (int c = 0; c < 3; c++) value[c] = at(layers[i + 1], widths[i + 1], heights[i + 1], 4,
                (x + .5f) * widths[i + 1] / w - .5f, (y + .5f) * heights[i + 1] / h - .5f, c);
            else {
                int sx = (int)yr_clamp((x + .5f) * info.width / w, 0, info.width - 1), sy = (int)yr_clamp((y + .5f) * info.height / h, 0, info.height - 1);
                float pixel[4]; yr_read(&image, sx, sy, pixel);
                for (int c = 0; c < 3; c++) value[c] = pixel[c];
            }
            value[3] = 1;
        }
    }
    strength = yr_clamp(strength, 0, 1); target_x = yr_clamp(target_x, 0, 1); target_y = yr_clamp(target_y, 0, 1);
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float u = (x + .5f) / info.width, v = (y + .5f) / info.height;
        float a = yr_clamp(at(mask, 192, 192, 1, u * 192 - .5f, v * 192 - .5f, 0), 0, 1);
        float bu = (u - target_x) / scale + target_x, bv = (v - target_y) / scale + target_y;
        float pixel[4]; yr_read(&image, x, y, pixel);
        for (int c = 0; c < 3; c++) {
            float original = pixel[c], bg = at(layers[0], widths[0], heights[0], 4,
                bu * widths[0] - .5f, bv * heights[0] - .5f, c);
            pixel[c] = original + strength * (1 - a) * (bg - original);
        }
        yr_write(&image, x, y, pixel);
    }
done:
    if (locked) AndroidBitmap_unlockPixels(env, bitmap);
    for (int i = 0; i < 24; i++) free(layers[i]);
    (*env)->ReleaseFloatArrayElements(env, matte, mask, JNI_ABORT);
}
