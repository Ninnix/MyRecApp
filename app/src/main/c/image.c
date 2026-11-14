#include "quantize.h"
#include "stabilize.h"
#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static float clampf(float x, float a, float b) { return fmaxf(a, fminf(b, x)); }
static unsigned char byte(float x) { return (unsigned char)clampf(x, 0, 255); }
static float luma(const uint8_t *p) { return p[0] * 0.299f + p[1] * 0.587f + p[2] * 0.114f; }

static void blur(uint8_t *p, int w, int h, int stride, int radius) {
    if (radius < 1)
        return;
    size_t size = (size_t)stride * h;
    uint8_t *tmp = malloc(size);
    if (!tmp)
        return;
    memcpy(tmp, p, size);
    for (int pass = 0; pass < 2; pass++) {
        int lines = pass ? w : h, count = pass ? h : w;
        for (int line = 0; line < lines; line++) {
            int sum[3] = {0};
            for (int i = -radius; i <= radius; i++) {
                int v = i < 0 ? 0 : i >= count ? count - 1 : i;
                const uint8_t *q = tmp + (pass ? v * stride + line * 4 : line * stride + v * 4);
                for (int ch = 0; ch < 3; ch++)
                    sum[ch] += q[ch];
            }
            for (int i = 0; i < count; i++) {
                uint8_t *out = p + (pass ? i * stride + line * 4 : line * stride + i * 4);
                for (int ch = 0; ch < 3; ch++)
                    out[ch] = (uint8_t)(sum[ch] / (radius * 2 + 1));
                int left = i - radius, right = i + radius + 1;
                if (left < 0)
                    left = 0;
                if (right >= count)
                    right = count - 1;
                const uint8_t *a =
                    tmp + (pass ? left * stride + line * 4 : line * stride + left * 4);
                const uint8_t *b =
                    tmp + (pass ? right * stride + line * 4 : line * stride + right * 4);
                for (int ch = 0; ch < 3; ch++)
                    sum[ch] += b[ch] - a[ch];
            }
        }
        memcpy(tmp, p, size);
    }
    free(tmp);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_image(JNIEnv *env, jobject self,
                                                        jobject bitmap, jint kind,
                                                        jfloat amount, jfloat param,
                                                        jfloat smoothing, jboolean dither,
                                                        jint palette, jboolean monochrome) {
    (void)self;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return;
    uint8_t *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels))
        return;
    amount = clampf(amount, 0, 1);
    if (amount == 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    if (kind == 5 && smoothing > 0)
        blur(pixels, (int)info.width, (int)info.height, (int)info.stride, (int)(smoothing * 3));
    YrQuantizer quantizer;
    if (kind == 5) {
        if (param < 0)
            palette = 1, param = 5;
        yr_quantizer_init(&quantizer, pixels, (int)info.width, (int)info.height,
                          (int)info.stride, (int)param, palette, monochrome);
    }
    if (kind == 14)
        blur(pixels, (int)info.width, (int)info.height, (int)info.stride,
             1 + (int)(amount * 12));
    else
        for (unsigned y = 0; y < info.height; y++)
            for (unsigned x = 0; x < info.width; x++) {
                uint8_t *p = pixels + y * info.stride + x * 4;
                float r = p[0], g = p[1], b = p[2], l = luma(p), rr = r, gg = g, bb = b;
                switch (kind) {
                case 1:
                    rr = gg = bb = l;
                    break;
                case 2:
                    rr = r * 1.08f + 8;
                    gg = g * 1.02f + 2;
                    bb = b * 0.90f;
                    break;
                case 3:
                    rr = r * 0.91f;
                    gg = g * 1.02f;
                    bb = b * 1.12f + 5;
                    break;
                case 4:
                    rr = (r - 128) * 1.18f + 132;
                    gg = (g - 128) * 1.07f + 128;
                    bb = (b - 128) * 0.92f + 117;
                    break;
                case 5: {
                    int levels = quantizer.count;
                    if (dither) {
                        static const int bayer[16] = {0, 8,  2, 10, 12, 4, 14, 6,
                                                      3, 11, 1, 9,  15, 7, 13, 5};
                        float noise = ((float)bayer[(y % 4) * 4 + x % 4] / 16 - 0.5f) * 255 /
                                      (levels - 1);
                        r = clampf(r + noise, 0, 255);
                        g = clampf(g + noise, 0, 255);
                        b = clampf(b + noise, 0, 255);
                    }
                    int key = ((int)r >> 3) * 1024 + ((int)g >> 3) * 32 + ((int)b >> 3);
                    int index = quantizer.nearest[key];
                    rr = quantizer.colors[index][0];
                    gg = quantizer.colors[index][1];
                    bb = quantizer.colors[index][2];
                    break;
                }
                case 6:
                    rr = (r + l) * 0.65f;
                    gg = (g + l) * 0.65f;
                    bb = (b + l) * 0.65f;
                    break;
                case 7:
                    rr = r * 0.45f;
                    gg = g * 0.58f;
                    bb = b * 0.82f;
                    break;
                case 8:
                    rr = l * 1.15f;
                    gg = l * 0.95f;
                    bb = l * 0.70f;
                    break;
                case 9:
                    rr = 255 - r;
                    gg = 255 - g;
                    bb = 255 - b;
                    break;
                case 10: {
                    float dx = (float)x / info.width - 0.5f, dy = (float)y / info.height - 0.5f;
                    float v = clampf(1 - (dx * dx + dy * dy) * 1.8f, 0, 1);
                    rr = r * v;
                    gg = g * v;
                    bb = b * v;
                    break;
                }
                case 11:
                    rr = (r - 128) * 1.6f + 128;
                    gg = (g - 128) * 1.6f + 128;
                    bb = (b - 128) * 1.6f + 128;
                    break;
                case 12:
                    rr = l + (r - l) * 1.8f;
                    gg = l + (g - l) * 1.8f;
                    bb = l + (b - l) * 1.8f;
                    break;
                case 15: {
                    float saturation = fmaxf(r, fmaxf(g, b)) - fminf(r, fminf(g, b));
                    if (r < g * 1.15f || r < b * 1.15f || saturation < 30)
                        rr = gg = bb = l;
                    break;
                }
                case 16:
                    rr = (r - 24) * 1.18f + (r - g) * .10f;
                    gg = (g - 20) * 1.12f;
                    bb = (b - 22) * 1.12f + (b - r) * .05f;
                    break;
                case 13: {
                    float grain = (float)((x * 374761393u + y * 668265263u +
                                           (unsigned)param * 1274126177u) %
                                          37) -
                                  18;
                    rr = r + grain;
                    gg = g + grain;
                    bb = b + grain;
                    break;
                }
                default:
                    break;
                }
                p[0] = byte(r + (rr - r) * amount);
                p[1] = byte(g + (gg - g) * amount);
                p[2] = byte(b + (bb - b) * amount);
            }
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_blend(JNIEnv *env, jobject self, jobject a,
                                                        jobject b, jobject out, jint kind,
                                                        jfloat progress, jfloat amount,
                                                        jfloat angle, jboolean soft) {
    (void)self;
    AndroidBitmapInfo ai, bi, oi;
    if (AndroidBitmap_getInfo(env, a, &ai) || AndroidBitmap_getInfo(env, b, &bi) ||
        AndroidBitmap_getInfo(env, out, &oi))
        return;
    if (ai.width != bi.width || ai.width != oi.width || ai.height != bi.height ||
        ai.height != oi.height)
        return;
    uint8_t *ap = NULL, *bp = NULL, *op = NULL;
    if (AndroidBitmap_lockPixels(env, a, (void **)&ap))
        return;
    if (AndroidBitmap_lockPixels(env, b, (void **)&bp)) {
        AndroidBitmap_unlockPixels(env, a);
        return;
    }
    if (AndroidBitmap_lockPixels(env, out, (void **)&op)) {
        AndroidBitmap_unlockPixels(env, a);
        AndroidBitmap_unlockPixels(env, b);
        return;
    }
    float t = clampf(progress, 0, 1);
    float cosine = cosf(angle * 3.14159265f / 180), sine = sinf(angle * 3.14159265f / 180);
    float extent = fabsf(cosine) * oi.width + fabsf(sine) * oi.height;
    for (unsigned y = 0; y < oi.height; y++)
        for (unsigned x = 0; x < oi.width; x++) {
            uint8_t *pa = ap + y * ai.stride + x * 4, *pb = bp + y * bi.stride + x * 4,
                    *po = op + y * oi.stride + x * 4;
            float mix = t, flash = 0;
            switch (kind) {
            case 1: {
                float position =
                    ((x - oi.width / 2.f) * cosine + (y - oi.height / 2.f) * sine) / extent +
                    .5f;
                mix = soft ? clampf((t - position) * 30 + .5f, 0, 1) : (t >= position ? 1 : 0);
                break;
            }
            case 2:
                mix = clampf((t - fabsf(2 * (float)x / oi.width - 1)) * 30 + 0.5f, 0, 1);
                break;
            case 3:
                mix = clampf((t - (amount < 0.5f ? 1 - luma(pa) / 255 : luma(pa) / 255)) * 8 +
                                 0.5f,
                             0, 1);
                break;
            case 4:
                flash = sinf(t * 3.14159265f);
                mix = t < 0.5f ? 0 : 1;
                break;
            case 5:
                flash = powf(fabsf(sinf(t * 3.14159265f * 6)), 8) * 0.9f;
                mix = t < 0.5f ? 0 : 1;
                break;
            case 6:
                mix = t < 0.5f ? 0 : 1;
                break;
            case 8: {
                int shift = (int)(t * oi.width);
                unsigned sx = (x + (unsigned)shift) % oi.width;
                if (x + (unsigned)shift < oi.width) {
                    pa = ap + y * ai.stride + sx * 4;
                    mix = 0;
                } else {
                    pb = bp + y * bi.stride + sx * 4;
                    mix = 1;
                }
                break;
            }
            default:
                break;
            }
            if (t <= 0)
                mix = 0;
            if (t >= 1)
                mix = 1;
            for (int c = 0; c < 3; c++)
                po[c] = byte((pa[c] * (1 - mix) + pb[c] * mix) * (1 - flash) + 255 * flash);
            po[3] = 255;
        }
    if (kind == 7)
        blur(op, (int)oi.width, (int)oi.height, (int)oi.stride,
             (int)(sinf(t * 3.14159265f) * amount * 22));
    AndroidBitmap_unlockPixels(env, a);
    AndroidBitmap_unlockPixels(env, b);
    AndroidBitmap_unlockPixels(env, out);
}

// Edge differences ignore gradual exposure changes and prefer stable geometry.
JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_motion(JNIEnv *env, jobject self,
                                                                jobject a, jobject b) {
    (void)self;
    float result[2] = {0};
    AndroidBitmapInfo ai, bi;
    uint8_t *ap = NULL, *bp = NULL;
    if (AndroidBitmap_getInfo(env, a, &ai) || AndroidBitmap_getInfo(env, b, &bi) ||
        ai.width != bi.width || ai.height != bi.height)
        goto done;
    if (AndroidBitmap_lockPixels(env, a, (void **)&ap))
        goto done;
    if (AndroidBitmap_lockPixels(env, b, (void **)&bp)) {
        AndroidBitmap_unlockPixels(env, a);
        goto done;
    }
    double best = 1e30;
    int w = (int)ai.width, h = (int)ai.height, step = w / 96;
    if (step < 1)
        step = 1;
    for (int dy = -12; dy <= 12; dy += 2)
        for (int dx = -12; dx <= 12; dx += 2) {
            double score = 0;
            for (int y = 16; y < h - 16; y += step)
                for (int x = 16; x < w - 16; x += step) {
                    const uint8_t *p = ap + y * ai.stride + x * 4,
                                  *q = bp + (y + dy) * bi.stride + (x + dx) * 4;
                    float ea = luma(p + 4) - luma(p), eb = luma(q + 4) - luma(q);
                    score += fabsf(ea - eb);
                }
            if (score < best) {
                best = score;
                result[0] = (float)dx;
                result[1] = (float)dy;
            }
        }
    AndroidBitmap_unlockPixels(env, a);
    AndroidBitmap_unlockPixels(env, b);
done:;
    jfloatArray out = (*env)->NewFloatArray(env, 2);
    if (out)
        (*env)->SetFloatArrayRegion(env, out, 0, 2, result);
    return out;
}

static float hdr_linear(float value, int transfer) {
    value = fminf(fmaxf(value, 0), 1);
    if (transfer == 7) return (value <= .5f ? value*value/3 :
        (expf((value-.55991073f)/.17883277f)+.28466892f)/12)*3;
    float p = powf(value, 1/78.84375f);
    return powf(fmaxf(p-.8359375f,0)/(18.8515625f-18.6875f*p),1/.1593017578125f)*49.261084f;
}

static float display_srgb(float value) {
    value = fmaxf(value,0);
    return value <= .0031308f ? 12.92f*value : 1.055f*powf(value,1/2.4f)-.055f;
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_yuv(JNIEnv *env, jobject self, jobject ybuf,
                                                      jobject ubuf, jobject vbuf, jint ys,
                                                      jint us, jint vs, jint up, jint vp,
                                                      jint left, jint top, jint width,
                                                      jint height, jint rotation, jint standard,
                                                      jboolean full, jobject bitmap, jint depth, jint transfer, jint primaries) {
    (void)self;
    uint8_t *yp = (*env)->GetDirectBufferAddress(env, ybuf);
    uint8_t *ub = (*env)->GetDirectBufferAddress(env, ubuf);
    uint8_t *vb = (*env)->GetDirectBufferAddress(env, vbuf);
    jlong yn = (*env)->GetDirectBufferCapacity(env, ybuf),
          un = (*env)->GetDirectBufferCapacity(env, ubuf),
          vn = (*env)->GetDirectBufferCapacity(env, vbuf);
    AndroidBitmapInfo info;
    if (!yp || !ub || !vb || width <= 0 || height <= 0 ||
        AndroidBitmap_getInfo(env, bitmap, &info))
        return;
    uint8_t *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, (void **)&pixels))
        return;
    int rotated = rotation % 180 != 0;
    int rw = rotated ? height : width, rh = rotated ? width : height;
    int rc = standard == 1 ? 459 : 409, guc = standard == 1 ? 55 : 100,
        gvc = standard == 1 ? 136 : 208, bc = standard == 1 ? 541 : 516;
    if (full) {
        rc = standard == 1 ? 403 : 359;
        guc = standard == 1 ? 48 : 88;
        gvc = standard == 1 ? 120 : 183;
        bc = standard == 1 ? 475 : 454;
    }
    for (unsigned y = 0; y < info.height; y++)
        for (unsigned x = 0; x < info.width; x++) {
            int dx = (int)((uint64_t)x * rw / info.width),
                dy = (int)((uint64_t)y * rh / info.height);
            int sx = dx, sy = dy;
            if (rotation == 90) {
                sx = dy;
                sy = height - 1 - dx;
            } else if (rotation == 180) {
                sx = width - 1 - dx;
                sy = height - 1 - dy;
            } else if (rotation == 270) {
                sx = width - 1 - dy;
                sy = dx;
            }
            sx += left;
            sy += top;
            jlong yi = (jlong)sy * ys + sx*(depth == 10 ? 2 : 1), ui = (jlong)(sy / 2) * us + (sx / 2) * up,
                  vi = (jlong)(sy / 2) * vs + (sx / 2) * vp;
            uint8_t *p = pixels + y * info.stride + x * 4;
            if (yi+(depth == 10) >= yn || ui+(depth == 10) >= un || vi+(depth == 10) >= vn || yi < 0 || ui < 0 || vi < 0) {
                p[0] = p[1] = p[2] = 0;
                p[3] = 255;
                continue;
            }
            if(depth == 10 || transfer == 6 || transfer == 7 || standard == 6) {
                float yv = depth == 10 ? (yp[yi] | yp[yi+1]<<8)/64.f : yp[yi];
                float u = depth == 10 ? (ub[ui] | ub[ui+1]<<8)/64.f : ub[ui];
                float v = depth == 10 ? (vb[vi] | vb[vi+1]<<8)/64.f : vb[vi];
                float scale = depth == 10 ? 4 : 1;
                yv = full ? yv/(256*scale-1) : (yv-16*scale)/(219*scale);
                u = (u-128*scale)/((full ? 256*scale-1 : 224*scale));
                v = (v-128*scale)/((full ? 256*scale-1 : 224*scale));
                float kr = standard == 6 ? .2627f : standard == 1 ? .2126f : .299f;
                float kb = standard == 6 ? .0593f : standard == 1 ? .0722f : .114f;
                float r = yv+2*(1-kr)*v, b = yv+2*(1-kb)*u;
                float g = (yv-kr*r-kb*b)/(1-kr-kb);
                if(transfer == 6 || transfer == 7) {
                    r = hdr_linear(r,transfer); g = hdr_linear(g,transfer); b = hdr_linear(b,transfer);
                    if(primaries == 2) {
                        float nr = 1.224745f*r-.224904f*g;
                        float ng = -.042058f*r+1.042081f*g;
                        b = -.019642f*r-.078655f*g+1.098537f*b; r = nr; g = ng;
                    } else if(standard == 6 && primaries != 1) {
                        float nr = 1.6605f*r-.5876f*g-.0728f*b;
                        float ng = -.1246f*r+1.1329f*g-.0083f*b;
                        b = -.0182f*r-.1006f*g+1.1187f*b; r = nr; g = ng;
                    }
                    float scale = 1+fmaxf(0,.2126f*r+.7152f*g+.0722f*b);
                    r = display_srgb(r/scale); g = display_srgb(g/scale); b = display_srgb(b/scale);
                }
                p[0] = byte(r*255); p[1] = byte(g*255); p[2] = byte(b*255); p[3] = 255;
                continue;
            }
            int l = full ? yp[yi] * 256 : (yp[yi] > 16 ? yp[yi] - 16 : 0) * 298;
            int u = ub[ui] - 128, v = vb[vi] - 128;
            p[0] = byte((float)((l + rc * v + 128) >> 8));
            p[1] = byte((float)((l - guc * u - gvc * v + 128) >> 8));
            p[2] = byte((float)((l + bc * u + 128) >> 8));
            p[3] = 255;
        }
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_similarity(JNIEnv *env, jobject self,
                                                                    jobject a, jobject b) {
    (void)self;
    float transform[5] = {1, 0, 0, 0, 0};
    AndroidBitmapInfo ai, bi;
    uint8_t *ap = NULL, *bp = NULL;
    if (AndroidBitmap_getInfo(env, a, &ai) || AndroidBitmap_getInfo(env, b, &bi) ||
        ai.width != bi.width || ai.height != bi.height)
        goto done;
    if (AndroidBitmap_lockPixels(env, a, (void **)&ap))
        goto done;
    if (AndroidBitmap_lockPixels(env, b, (void **)&bp)) {
        AndroidBitmap_unlockPixels(env, a);
        goto done;
    }
    uint8_t *gray = malloc((size_t)ai.width * ai.height * 2);
    if (gray) {
        for (unsigned y = 0; y < ai.height; y++)
            for (unsigned x = 0; x < ai.width; x++) {
                gray[y * ai.width + x] = byte(luma(ap + y * ai.stride + x * 4));
                gray[ai.width * ai.height + y * ai.width + x] =
                    byte(luma(bp + y * bi.stride + x * 4));
            }
        yr_similarity(gray, gray + ai.width * ai.height, (int)ai.width, (int)ai.height,
                      transform);
        free(gray);
    }
    AndroidBitmap_unlockPixels(env, a);
    AndroidBitmap_unlockPixels(env, b);
done:;
    jfloatArray out = (*env)->NewFloatArray(env, 5);
    if (out)
        (*env)->SetFloatArrayRegion(env, out, 0, 5, transform);
    return out;
}
