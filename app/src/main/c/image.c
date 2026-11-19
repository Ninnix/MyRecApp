#include "quantize.h"
#include "pixels.h"
#include "stabilize.h"
#include <android/bitmap.h>
#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static float clampf(float x, float a, float b) { return fmaxf(a, fminf(b, x)); }
static unsigned char byte(float x) { return (unsigned char)clampf(x, 0, 255); }

JNIEXPORT void JNICALL Java_org_myrec_app_Native_legacyVisual(JNIEnv *env,jobject self,jobject bitmap,jint kind,jfloat strength,jfloat value) {
    (void)self;
    if (strength<=0 || (kind!=0 && value==0)) return;
    YrPixels image;if (!yr_lock(env,bitmap,&image)) return;
    int w=image.info.width,h=image.info.height;
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        if (kind==0 && y+.5f>=h*value && y+.5f<h*(1-value)) continue;
        float c[4];yr_read(&image,x,y,c);
        for (int k=0;k<3;k++) {
            if (kind==0) c[k]*=1-strength;
            else {
                float v=yr_srgb(c[k]);
                if (kind==1) v+=value*strength*80/255;
                else if (kind==2) v*=1+(k==0?1:k==2?-1:0)*value*strength*.2f;
                c[k]=yr_linear(yr_clamp(v,0,1));
            }
        }
        if (kind==0) c[3]+=(1-c[3])*strength;
        yr_write(&image,x,y,c);
    }
    AndroidBitmap_unlockPixels(env,bitmap);
}

static void blur_image(YrPixels *p, int radius) {
    if (radius < 1) return;
    int w = p->info.width, h = p->info.height;
    float *tmp = malloc((size_t)w * h * 3 * sizeof(float));
    if (!tmp) return;
    float divisor = 1.f / (radius * 2 + 1);
    for (int y = 0; y < h; y++) {
        float sum[3] = {0};
        for (int i = -radius; i <= radius; i++) { float c[4]; yr_read(p, (int)yr_clamp(i, 0, w - 1), y, c); for (int ch = 0; ch < 3; ch++) sum[ch] += c[ch]; }
        for (int x = 0; x < w; x++) {
            for (int ch = 0; ch < 3; ch++) tmp[(y * w + x) * 3 + ch] = sum[ch] * divisor;
            float a[4], b[4]; yr_read(p, (int)yr_clamp(x - radius, 0, w - 1), y, a); yr_read(p, (int)yr_clamp(x + radius + 1, 0, w - 1), y, b);
            for (int ch = 0; ch < 3; ch++) sum[ch] += b[ch] - a[ch];
        }
    }
    for (int x = 0; x < w; x++) {
        float sum[3] = {0};
        for (int i = -radius; i <= radius; i++) for (int ch = 0; ch < 3; ch++) sum[ch] += tmp[((int)yr_clamp(i, 0, h - 1) * w + x) * 3 + ch];
        for (int y = 0; y < h; y++) {
            float c[4]; yr_read(p, x, y, c);
            for (int ch = 0; ch < 3; ch++) {
                c[ch] = sum[ch] * divisor;
                sum[ch] += tmp[((int)yr_clamp(y + radius + 1, 0, h - 1) * w + x) * 3 + ch] - tmp[((int)yr_clamp(y - radius, 0, h - 1) * w + x) * 3 + ch];
            }
            yr_write(p, x, y, c);
        }
    }
    free(tmp);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_image(JNIEnv *env, jobject self,
                                                        jobject bitmap, jint kind,
                                                        jfloat amount, jfloat param,
                                                        jfloat smoothing, jboolean dither,
                                                        jint palette, jboolean monochrome) {
    (void)self;
    YrPixels image;
    if (!yr_lock(env, bitmap, &image)) return;
    AndroidBitmapInfo info = image.info;
    amount = clampf(amount, 0, 1);
    if (amount == 0) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return;
    }
    if (kind == 5 && smoothing > 0)
        blur_image(&image, (int)(smoothing * 3));
    YrQuantizer quantizer;
    if (kind == 5) {
        if (param < 0) palette = 1, param = 5;
        uint8_t *analysis = malloc((size_t)info.width * info.height * 4);
        if (!analysis) { AndroidBitmap_unlockPixels(env, bitmap); return; }
        for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
            float c[4]; yr_read(&image, x, y, c);
            for (int ch = 0; ch < 3; ch++) analysis[(y * info.width + x) * 4 + ch] = yr_display(c[ch]);
            analysis[(y * info.width + x) * 4 + 3] = 255;
        }
        yr_quantizer_init(&quantizer, analysis, info.width, info.height, info.width * 4, (int)param, palette, monochrome);
        free(analysis);
    }
    if (kind == 14)
        blur_image(&image, 1 + (int)(amount * 12));
    else
        for (unsigned y = 0; y < info.height; y++)
            for (unsigned x = 0; x < info.width; x++) {
                float p[4]; yr_read(&image, x, y, p);
                float r = yr_clamp(yr_srgb(p[0]) * 255, 0, 255), g = yr_clamp(yr_srgb(p[1]) * 255, 0, 255), b = yr_clamp(yr_srgb(p[2]) * 255, 0, 255);
                float l = .299f * r + .587f * g + .114f * b, rr = r, gg = g, bb = b;
                switch (kind) {
                case 1:
                    rr = gg = bb = l;
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
                p[0] = yr_linear(clampf(r + (rr - r) * amount, 0, 255) / 255);
                p[1] = yr_linear(clampf(g + (gg - g) * amount, 0, 255) / 255);
                p[2] = yr_linear(clampf(b + (bb - b) * amount, 0, 255) / 255);
                yr_write(&image, x, y, p);
            }
    AndroidBitmap_unlockPixels(env, bitmap);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_blend(JNIEnv *env, jobject self, jobject a,
                                                        jobject b, jobject out, jint kind,
                                                        jfloat progress, jfloat amount,
                                                        jfloat angle, jboolean soft) {
    (void)self;
    YrPixels first, second, output;
    if (!yr_lock(env, a, &first)) return;
    if (!yr_lock(env, b, &second)) { AndroidBitmap_unlockPixels(env, a); return; }
    if (!yr_lock(env, out, &output)) { AndroidBitmap_unlockPixels(env, a); AndroidBitmap_unlockPixels(env, b); return; }
    AndroidBitmapInfo oi = output.info;
    if (first.info.width != oi.width || second.info.width != oi.width || first.info.height != oi.height || second.info.height != oi.height) goto done;
    float t = clampf(progress, 0, 1);
    float cosine = cosf(angle * 3.14159265f / 180), sine = sinf(angle * 3.14159265f / 180);
    float extent = fabsf(cosine) * oi.width + fabsf(sine) * oi.height;
    for (unsigned y = 0; y < oi.height; y++)
        for (unsigned x = 0; x < oi.width; x++) {
            float pa[4], pb[4], po[4] = {0, 0, 0, 1};
            yr_read(&first, x, y, pa); yr_read(&second, x, y, pb);
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
                mix = clampf((t - (amount < 0.5f ? 1 - yr_luma(pa) : yr_luma(pa))) * 8 +
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
                    yr_read(&first, sx, y, pa);
                    mix = 0;
                } else {
                    yr_read(&second, sx, y, pb);
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
                po[c] = (pa[c] * (1 - mix) + pb[c] * mix) * (1 - flash) + flash;
            yr_write(&output, x, y, po);
        }
    if (kind == 7)
        blur_image(&output, (int)(sinf(t * 3.14159265f) * amount * 22));
done:
    AndroidBitmap_unlockPixels(env, a);
    AndroidBitmap_unlockPixels(env, b);
    AndroidBitmap_unlockPixels(env, out);
}

static float hdr_linear(float value, int transfer) {
    value = fminf(fmaxf(value, 0), 1);
    if (transfer == 7) return (value <= .5f ? value*value/3 :
        (expf((value-.55991073f)/.17883277f)+.28466892f)/12);
    float p = powf(value, 1/78.84375f);
    return powf(fmaxf(p-.8359375f,0)/(18.8515625f-18.6875f*p),1/.1593017578125f)*49.261084f;
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
    YrPixels image;
    if (!yp || !ub || !vb || width <= 0 || height <= 0 || !yr_lock(env, bitmap, &image)) return;
    AndroidBitmapInfo info = image.info;
    uint8_t *pixels = image.data;
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
            int fp16 = info.format == ANDROID_BITMAP_FORMAT_RGBA_F16;
            uint8_t *p = pixels + y * info.stride + x * (fp16 ? 8 : 4);
            if (yi+(depth == 10) >= yn || ui+(depth == 10) >= un || vi+(depth == 10) >= vn || yi < 0 || ui < 0 || vi < 0) {
                if (fp16) { uint16_t *v = (uint16_t *)p; v[0] = v[1] = v[2] = 0; v[3] = yr_half(1); }
                else { p[0] = p[1] = p[2] = 0; p[3] = 255; }
                continue;
            }
            if(fp16 || depth == 10 || transfer == 6 || transfer == 7 || standard == 6) {
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
                    if (transfer == 7) {
                        float luminance = primaries == 1 ? .2126f * r + .7152f * g + .0722f * b :
                            primaries == 2 ? .2289746f * r + .6917385f * g + .0792869f * b : .2627f * r + .6780f * g + .0593f * b;
                        float gain = powf(fmaxf(0, luminance), .2f) * (1000.f / 203);
                        r *= gain; g *= gain; b *= gain;
                    }
                    if(primaries == 2) {
                        float nr = 1.224745f*r-.224904f*g;
                        float ng = -.042058f*r+1.042081f*g;
                        b = -.019642f*r-.078655f*g+1.098537f*b; r = nr; g = ng;
                    } else if(standard == 6 && primaries != 1) {
                        float nr = 1.6605f*r-.5876f*g-.0728f*b;
                        float ng = -.1246f*r+1.1329f*g-.0083f*b;
                        b = -.0182f*r-.1006f*g+1.1187f*b; r = nr; g = ng;
                    }
                    if (!fp16) {
                        float c[4] = {r, g, b, 1}; yr_tone(c);
                        r = yr_srgb(c[0]); g = yr_srgb(c[1]); b = yr_srgb(c[2]);
                    }
                } else if (fp16 && transfer != -1) {
                    r = yr_linear(r); g = yr_linear(g); b = yr_linear(b);
                }
                if (fp16) {
                    uint16_t *v = (uint16_t *)p;
                    v[0] = yr_half(r); v[1] = yr_half(g); v[2] = yr_half(b); v[3] = yr_half(1);
                    continue;
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
                                                                    jobject a, jobject b, jboolean camera_motion) {
    (void)self;
    float transform[5] = {1, 0, 0, 0, 0};
    YrPixels first, second;
    if (!yr_lock(env, a, &first)) goto done;
    if (!yr_lock(env, b, &second)) { AndroidBitmap_unlockPixels(env, a); goto done; }
    AndroidBitmapInfo ai = first.info;
    if (ai.width == second.info.width && ai.height == second.info.height) {
        uint8_t *gray = malloc((size_t)ai.width * ai.height * 2);
        if (gray) {
            for (unsigned y = 0; y < ai.height; y++) for (unsigned x = 0; x < ai.width; x++) {
                float c[4], d[4]; yr_read(&first, x, y, c); yr_read(&second, x, y, d);
                for (int i = 0; i < 3; i++) { c[i] = yr_srgb(c[i]); d[i] = yr_srgb(d[i]); }
                gray[y * ai.width + x] = byte(255 * (.299f*c[0] + .587f*c[1] + .114f*c[2]));
                gray[ai.width * ai.height + y * ai.width + x] = byte(255 * (.299f*d[0] + .587f*d[1] + .114f*d[2]));
            }
            if (camera_motion)
                yr_camera_similarity(gray, gray + ai.width * ai.height, ai.width, ai.height, transform);
            else
                yr_similarity(gray, gray + ai.width * ai.height, ai.width, ai.height, transform);
            free(gray);
        }
    }
    AndroidBitmap_unlockPixels(env, a); AndroidBitmap_unlockPixels(env, b);
done:;
    jfloatArray out = (*env)->NewFloatArray(env, 5);
    if (out)
        (*env)->SetFloatArrayRegion(env, out, 0, 5, transform);
    return out;
}
