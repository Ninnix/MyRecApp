#include "pixels.h"
#include <android/bitmap.h>
#include <jni.h>

static void sample(const float *table, int size, int one, const float domain[6], const float rgb[3], float out[3]) {
    float p[3], f[3]; int base[3];
    for (int i = 0; i < 3; i++) {
        p[i] = yr_clamp((rgb[i] - domain[i]) / (domain[i + 3] - domain[i]), 0, 1) * (size - 1);
        base[i] = (int)fminf(size - 2, floorf(p[i]));
        f[i] = p[i] - base[i];
        out[i] = 0;
    }
    if (one) {
        for (int i = 0; i < 3; i++) out[i] = table[base[i] * 3 + i] * (1 - f[i]) + table[(base[i] + 1) * 3 + i] * f[i];
        return;
    }
    for (int b = 0; b < 2; b++) for (int g = 0; g < 2; g++) for (int r = 0; r < 2; r++) {
        float weight = (r ? f[0] : 1 - f[0]) * (g ? f[1] : 1 - f[1]) * (b ? f[2] : 1 - f[2]);
        int at = ((base[2] + b) * size * size + (base[1] + g) * size + base[0] + r) * 3;
        for (int i = 0; i < 3; i++) out[i] += table[at + i] * weight;
    }
}

JNIEXPORT void JNICALL Java_org_myrec_app_Native_colorLut(JNIEnv *env, jobject self,
    jobject bitmap, jfloatArray entries, jint size, jboolean one, jfloatArray bounds,
    jint input, jfloat strength, jfloat exposure, jfloat highlights, jint source, jfloat warmth, jfloat tint) {
    (void)self;
    if (size < 2 || size > (one ? 16384 : 65) || input < 0 || input > 2 || source < 0 || source > 2 ||
        !isfinite(strength) || !isfinite(exposure) || !isfinite(highlights) || !isfinite(warmth) || !isfinite(tint) ||
        (*env)->GetArrayLength(env, entries) != (one ? size : size * size * size) * 3 ||
        (*env)->GetArrayLength(env, bounds) != 6) return;
    float domain[6]; (*env)->GetFloatArrayRegion(env, bounds, 0, 6, domain);
    for (int i = 0; i < 3; i++) if (!(domain[i + 3] > domain[i])) return;
    float *table = (*env)->GetFloatArrayElements(env, entries, NULL);
    if (!table) return;
    YrPixels image;
    if (!yr_lock(env, bitmap, &image)) {
        (*env)->ReleaseFloatArrayElements(env, entries, table, JNI_ABORT); return;
    }
    AndroidBitmapInfo info = image.info;
    const float matrices[2][9] = {
        {.6274038959f, .3292830384f, .0433130657f, .0690972894f, .9195403951f, .0113623156f, .0163914389f, .0880133079f, .8955952532f},
        {.6081040100f, .2593533922f, .1325425978f, .0623155336f, .8046088497f, .1330756167f, .0311326147f, .1337558451f, .8351115402f}
    };
    const float to709[2][9] = {
        {1.6604910021f, -.5876411388f, -.0728498633f, -.1245504745f, 1.1328998971f, -.0083494226f, -.0181507634f, -.1005788980f, 1.1187296614f},
        {1.7072803771f, -.5190199199f, -.1882604573f, -.1250107461f, 1.3146623656f, -.1896516194f, -.0436243333f, -.1912143718f, 1.2348387051f}
    };
    float gain = exp2f(yr_clamp(exposure, -4, 4)); strength = yr_clamp(strength, 0, 1);
    highlights = yr_clamp(highlights, -1, 1);
    warmth = yr_clamp(warmth, -1, 1); tint = yr_clamp(tint, -1, 1);
    float balance[3] = {exp2f(.45f * warmth + .2f * tint), exp2f(-.2f * tint), exp2f(-.45f * warmth + .2f * tint)};
    float white = yr_luma(balance);
    for (int i = 0; i < 3; i++) balance[i] /= white;
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float pixel[4]; yr_read(&image, x, y, pixel);
        float c[3], codes[3], mapped[3];
        for (int i = 0; i < 3; i++) c[i] = pixel[i];
        if (source) {
            float camera[3];
            for (int i = 0; i < 3; i++) {
                float code = image.info.format == ANDROID_BITMAP_FORMAT_RGBA_F16 ? pixel[i] : yr_srgb(pixel[i]);
                camera[i] = code >= .20855531595f ? exp2f((code - .69336945f) / .08550479f) - .00964052f
                    : sqrtf(fmaxf(0, code) / 47.28711236f) - .05641088f;
            }
            for (int i = 0; i < 3; i++) {
                const float *row = to709[source - 1] + i * 3;
                c[i] = row[0] * camera[0] + row[1] * camera[1] + row[2] * camera[2];
            }
        }
        for (int i = 0; i < 3; i++) c[i] *= gain * balance[i];
        float luma = fmaxf(0, yr_luma(c)), delta = fmaxf(0, luma - .35f);
        if (highlights != 0 && delta > 0) {
            float k = fmaxf(.000001f, -highlights * 6);
            float target = .35f + (highlights < 0 ? log1pf(k * delta) / k : delta * (1 + 2 * highlights * delta / (delta + .35f)));
            for (int i = 0; i < 3; i++) c[i] *= target / luma;
        }
        float viewed[3];
        for (int i = 0; i < 3; i++) viewed[i] = c[i];
        if (source) {
            float y = fmaxf(0, yr_luma(c)), excess = fmaxf(0, y - .6f);
            float mapped = y <= .6f ? y : .6f + .4f * excess / (excess + .4f);
            float chroma[3], fit = 1;
            for (int i = 0; i < 3; i++) {
                viewed[i] *= y > .000001f ? mapped / y : 1;
                chroma[i] = viewed[i] - mapped;
                if (chroma[i] > .000001f) fit = fminf(fit, (1 - mapped) / chroma[i]);
                if (chroma[i] < -.000001f) fit = fminf(fit, -mapped / chroma[i]);
            }
            for (int i = 0; i < 3; i++) viewed[i] = yr_clamp(mapped + chroma[i] * fit, 0, 1);
        }
        for (int i = 0; i < 3; i++) {
            if (!input) codes[i] = yr_srgb(viewed[i]);
            else {
                const float *row = matrices[input - 1] + i * 3;
                float v = row[0] * c[0] + row[1] * c[1] + row[2] * c[2];
                codes[i] = v >= .01f ? log2f(v + .00964052f) * .08550479f + .69336945f
                    : 47.28711236f * powf(fmaxf(0, v + .05641088f), 2);
            }
        }
        if (strength > 0) sample(table, size, one, domain, codes, mapped);
        for (int i = 0; i < 3; i++) pixel[i] = viewed[i] + strength * (strength > 0 ? yr_linear(mapped[i]) - viewed[i] : 0);
        yr_write(&image, x, y, pixel);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    (*env)->ReleaseFloatArrayElements(env, entries, table, JNI_ABORT);
}
