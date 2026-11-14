#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static float clamp(float v, float lo, float hi) { return fminf(hi, fmaxf(lo, v)); }

static float reflect(float value, int length) {
    if (length <= 1) return 0;
    float edge = length - 1;
    value = fmodf(fabsf(value), 2 * edge);
    return value <= edge ? value : 2 * edge - value;
}

static void sample(const uint8_t *src, int stride, int width, int height, float x, float y,
                   float out[3]) {
    x = clamp(x, 0, width - 1);
    y = clamp(y, 0, height - 1);
    int ix = (int)x, iy = (int)y;
    int nx = ix + 1 < width ? ix + 1 : ix;
    int ny = iy + 1 < height ? iy + 1 : iy;
    float dx = x - ix, dy = y - iy;
    for (int c = 0; c < 3; c++)
        out[c] = (1 - dy) * ((1 - dx) * src[iy * stride + ix * 4 + c] +
                             dx * src[iy * stride + nx * 4 + c]) +
                 dy * ((1 - dx) * src[ny * stride + ix * 4 + c] +
                       dx * src[ny * stride + nx * 4 + c]);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_kaleidoscope(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jint segments,
    jfloat rotation, jfloat zoom, jfloat center_x, jfloat center_y) {
    (void)self;
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || strength <= 0 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels)) return;
    size_t bytes = (size_t)info.stride * info.height;
    uint8_t *source = malloc(bytes);
    if (!source) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    memcpy(source, pixels, bytes);
    const float pi = 3.14159265358979323846f;
    /* A period contains two mirrored sections. Keep the source wedge above
     * the center, where a centered subject is, rather than at the right edge. */
    float sector = 4 * pi / clamp((float)segments, 2, 24);
    float angle = rotation * pi / 180 - pi / 2 - sector / 4;
    /* Center locates the mirror junction; source framing stays fixed so moving
     * the junction does not pan the subject inside each mirrored section. */
    float half_w = (info.width - 1) * .5f, half_h = (info.height - 1) * .5f;
    float cx = half_w, cy = half_h * 1.5f;
    float ox = (info.width - 1) * clamp(center_x, 0, 1);
    float oy = (info.height - 1) * clamp(center_y, 0, 1);
    float turn = rotation * pi / 180, cs = cosf(turn), sn = sinf(turn);
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float dx = x - ox, dy = y - oy;
        float scale = fmaxf(.1f, zoom), sx, sy;
        if (segments == 4) {
            /* Four-way mirroring keeps complete subjects in a 2x2 layout. */
            float tile_scale = scale * (5.f / 3);
            sx = cx + (half_w - 2 * fabsf(dx * cs + dy * sn)) / tile_scale;
            sy = cy + (half_h * .5f - 2 * fabsf(dy * cs - dx * sn)) / tile_scale;
        } else {
            float radius = hypotf(dx, dy) / scale;
            float theta = fmodf(atan2f(dy, dx) - angle + 8 * pi, sector);
            theta = fminf(theta, sector - theta) + angle;
            sx = cx + radius * cosf(theta);
            sy = cy + radius * sinf(theta);
        }
        float rgb[3];
        sample(source, info.stride, info.width, info.height,
               reflect(sx, info.width), reflect(sy, info.height), rgb);
        uint8_t *out = pixels + y * info.stride + x * 4;
        for (int c = 0; c < 3; c++)
            out[c] = (uint8_t)clamp(out[c] + strength * (rgb[c] - out[c]), 0, 255);
    }
    free(source);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_rgbOffset(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloat distance, jfloat angle) {
    (void)self;
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (strength <= 0 || distance <= 0 || AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels)) return;
    size_t bytes = (size_t)info.stride * info.height;
    uint8_t *source = malloc(bytes);
    if (!source) { AndroidBitmap_unlockPixels(env, bitmap); return; }
    memcpy(source, pixels, bytes);
    float radius = fminf(info.width, info.height) * clamp(distance, 0, .25f);
    float dx = radius * cosf(angle * .01745329252f);
    float dy = radius * sinf(angle * .01745329252f);
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float magenta[3], green[3];
        sample(source, info.stride, info.width, info.height, x - dx, y - dy, magenta);
        sample(source, info.stride, info.width, info.height, x + dx, y + dy, green);
        uint8_t *out = pixels + y * info.stride + x * 4;
        for (int c = 0; c < 3; c++) {
            float value = c == 1 ? green[c] : magenta[c];
            out[c] = (uint8_t)clamp(out[c] + strength * (value - out[c]), 0, 255);
        }
    }
    free(source);
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_widescreen(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloat ratio,
    jint bars, jfloat flares, jint tint) {
    (void)self;
    AndroidBitmapInfo info;
    uint8_t *pixels;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || strength <= 0 ||
        AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels)) return;
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
                uint8_t *p = pixels + y * info.stride + x * 4;
                float value = fmaxf(0, ((.2126f*p[0]+.7152f*p[1]+.0722f*p[2]) / 255.f - .9f) / .1f);
                tail = tail * decay + value * (1 - decay);
                streak[x] = tail;
            }
            tail = 0;
            for (int x = (int)info.width - 1; x >= 0; x--) {
                uint8_t *p = pixels + y * info.stride + x * 4;
                float value = fmaxf(0, ((.2126f*p[0]+.7152f*p[1]+.0722f*p[2]) / 255.f - .9f) / .1f);
                tail = tail * decay + value * (1 - decay);
                for (int c = 0; c < 3; c++)
                    p[c] = (uint8_t)clamp(p[c] + strength * flares * 600 *
                                          (streak[x] + tail) * color[c], 0, 255);
            }
        }
        if (y < edge || y >= info.height - edge) for (unsigned x = 0; x < info.width; x++) {
            uint8_t *p = pixels + y * info.stride + x * 4;
            for (int c = 0; c < 3; c++) {
                float value = bars == 1 ? 255 : 0;
                p[c] = (uint8_t)clamp(p[c] + strength * (value - p[c]), 0, 255);
            }
        }
    }
    free(streak);
    AndroidBitmap_unlockPixels(env, bitmap);
}
