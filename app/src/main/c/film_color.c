#include <android/bitmap.h>
#include <jni.h>
#include "pixels.h"

static float mix(float a, float b, float t) { return a + (b - a) * t; }
static float hue_distance(float a, float b) { float d = fabsf(a - b); return fminf(d, 1 - d); }
static float contrast(float value, float gain) {
    value = yr_clamp(value, 0, 1);
    float a = powf(value, gain), b = powf(1 - value, gain);
    return a / fmaxf(a + b, 1e-6f);
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_filmColor(
    JNIEnv *env, jobject self, jobject bitmap, jint kernel, jfloat strength, jfloatArray parameters) {
    (void)self;
    const int counts[] = {3, 3, 1, 3, 4};
    if (kernel < 0 || kernel > 4 || strength <= 0 || !parameters ||
        (*env)->GetArrayLength(env, parameters) != counts[kernel]) return;
    float p[4] = {0};
    (*env)->GetFloatArrayRegion(env, parameters, 0, counts[kernel], p);
    if ((*env)->ExceptionCheck(env)) return;
    YrPixels image;
    if (!yr_lock(env, bitmap, &image)) return;
    AndroidBitmapInfo info = image.info;
    strength = yr_clamp(strength, 0, 1);
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float pixel[4]; yr_read(&image, x, y, pixel);
        float c[3], out[3], encoded[3], h[3];
        for (int i = 0; i < 3; i++) out[i] = c[i] = pixel[i];
        float luma = yr_luma(c), u = (x + .5f) / info.width, v = (y + .5f) / info.height;
        switch (kernel) {
        case 0: {
            const float tints[][3] = {{.243137f,.435294f,.658824f}, {.85098f,.643137f,.254902f},
                {.721569f,.262745f,.184314f}, {.431373f,.560784f,.305882f}, {.541176f,.415686f,.270588f}};
            int tint = (int)yr_clamp(p[0], 0, 4);
            float l = yr_linear(yr_clamp((yr_srgb(luma) - .5f) * p[2] + .5f, 0, 1));
            for (int i = 0; i < 3; i++) out[i] = p[1] < .5f ? l * yr_linear(tints[tint][i]) :
                mix(yr_linear(tints[tint][i]) * .15f, 1, yr_smooth(0, 1, l));
            break;
        }
        case 1: {
            for (int i = 0; i < 3; i++) encoded[i] = yr_srgb(c[i]);
            yr_hsv(encoded, h);
            float skin = yr_smooth(.03f,.055f,h[0]) * (1 - yr_smooth(.115f,.15f,h[0])) * yr_smooth(.12f,.3f,h[1]);
            for (int i = 0; i < 3; i++) out[i] = c[i] + (c[i] - (c[(i+1)%3] + c[(i+2)%3]) * .5f) * (p[0] - 1) * (1 - .65f * skin);
            float red = 1 - yr_smooth(15.f/360, 25.f/360, hue_distance(h[0], 0));
            out[1] -= red * p[1] * .15f * (1 - skin);
            out[2] -= red * p[1] * .15f * (1 - skin);
            float l = yr_luma(out), s = l * l * (3 - 2 * l);
            for (int i = 0; i < 3; i++) out[i] += (s - l) * .14f;
            out[0] *= 1 + p[2] * .06f; out[2] *= 1 - p[2] * .06f;
            float high = fmaxf(out[0], fmaxf(out[1], out[2]));
            if (high > .9f) for (int i = 0; i < 3; i++) out[i] = .9f + (out[i] - .9f) / (1 + fmaxf(0, high - .9f));
            for (int i = 0; i < 3; i++) out[i] = mix(out[i], c[i], skin * .35f);
            break;
        }
        case 2: {
            float bleached[3];
            for (int i = 0; i < 3; i++) {
                float overlay = luma < .5f ? 2 * c[i] * luma : 1 - 2 * (1 - c[i]) * (1 - luma);
                bleached[i] = mix(luma, overlay, .5f);
            }
            float gray = yr_luma(bleached);
            for (int i = 0; i < 3; i++) out[i] = mix(c[i], mix(gray, bleached[i], 1 - .3f * p[0]), p[0]);
            break;
        }
        case 3: {
            for (int i = 0; i < 3; i++) encoded[i] = yr_srgb(c[i]);
            yr_hsv(encoded, h);
            const float blue[] = {.55f,.7f,1};
            float sky = (1 - yr_smooth(.25f,.38f,v)) * yr_smooth(.5f,.85f,luma) *
                (1 - yr_smooth(.08f,.18f,hue_distance(h[0],.61f)));
            for (int i = 0; i < 3; i++) {
                out[i] = mix(luma, c[i], .4f) * exp2f(-3 * p[0]) * mix(1, blue[i], p[1]) * (1 - .5f * sky);
                if (p[2] > .5f) out[i] = mix(out[i], luma * exp2f(-.7f * p[0]), yr_smooth(.94f,.99f,luma) * .7f);
                out[i] *= 1 - .15f * yr_smooth(.2f,.75f,hypotf(u - .5f,v - .5f));
            }
            break;
        }
        case 4: {
            float perceptual = yr_srgb(luma), l = contrast(perceptual, p[0]);
            l = yr_linear(mix(l, yr_smooth(.35f,.65f,perceptual), .4f));
            float dx = (u - .5f) * 2, dy = (v - .5f) * 2;
            if (p[2] > .5f) dx *= (float)info.width / info.height;
            float iris = yr_clamp(1 - powf(yr_clamp(hypotf(dx,dy) / 1.25f, 0, 1), 2.5f), 0, 1);
            l *= mix(1, iris, p[1]);
            const float tint[] = {.82f,.84f,.52f};
            for (int i = 0; i < 3; i++) out[i] = l * (p[3] > .5f ? yr_linear(tint[i]) : 1);
            break;
        }
        }
        for (int i = 0; i < 3; i++) pixel[i] = mix(c[i], yr_clamp(out[i], 0, 1), strength);
        yr_write(&image, x, y, pixel);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
}
