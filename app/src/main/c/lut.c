#include "pixels.h"
#include "local_recovery.h"
#include <android/bitmap.h>
#include <jni.h>

static void sample(const float *table, int size, int one, const float domain[6], const float rgb[3], float out[3],int extrapolate) {
    float p[3], f[3]; int base[3];
    for (int i = 0; i < 3; i++) {
        p[i]=(rgb[i]-domain[i])/(domain[i+3]-domain[i]);
        if (!extrapolate) p[i]=yr_clamp(p[i],0,1);
        p[i]*=size-1;
        base[i]=(int)yr_clamp(floorf(p[i]),0,size-2);
        f[i] = p[i] - base[i];
        out[i] = 0;
    }
    if (one) {
        for (int i = 0; i < 3; i++) out[i] = table[base[i] * 3 + i] * (1 - f[i]) + table[(base[i] + 1) * 3 + i] * f[i];
        return;
    }
    /* Nested interpolation avoids cancellation when HDR codes extend past the cube. */
    if (extrapolate) {
        for (int i=0;i<3;i++) {
            float edge[4];
            for (int b=0;b<2;b++) for (int g=0;g<2;g++) {
                int at=((base[2]+b)*size*size+(base[1]+g)*size+base[0])*3+i;
                edge[b*2+g]=table[at]+(table[at+3]-table[at])*f[0];
            }
            float low=edge[0]+(edge[1]-edge[0])*f[1],high=edge[2]+(edge[3]-edge[2])*f[1];
            out[i]=low+(high-low)*f[2];
        }
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
    jint input, jfloat strength, jfloat exposure, jfloat highlights, jint source, jfloat warmth, jfloat tint,jfloat recovery,jfloatArray adaptation,jboolean extrapolate) {
    (void)self;
    if (size < 2 || size > (one ? 16384 : 65) || input < 0 || input > 3 || source < 0 || source > 3 ||
        !isfinite(recovery) || !isfinite(strength) || !isfinite(exposure) || !isfinite(highlights) || !isfinite(warmth) || !isfinite(tint) ||
        (*env)->GetArrayLength(env, entries) != (one ? size : size * size * size) * 3 ||
        (*env)->GetArrayLength(env, bounds) != 6) return;
    if (!source && strength<=0 && exposure==0 && highlights==0 && warmth==0 && tint==0 && !adaptation) return;
    float domain[6]; (*env)->GetFloatArrayRegion(env, bounds, 0, 6, domain);
    for (int i = 0; i < 3; i++) if (!(domain[i + 3] > domain[i])) return;
    float *table = (*env)->GetFloatArrayElements(env, entries, NULL);
    if (!table) return;
    YrPixels image;
    if (!yr_lock(env, bitmap, &image)) {
        (*env)->ReleaseFloatArrayElements(env, entries, table, JNI_ABORT); return;
    }
    AndroidBitmapInfo info = image.info;
    float matrix[9];int adapt=adaptation!=NULL;
    if (adapt) {
        if ((*env)->GetArrayLength(env,adaptation)!=9) goto finish;
        (*env)->GetFloatArrayRegion(env,adaptation,0,9,matrix);
        for (int i=0;i<9;i++) if (!isfinite(matrix[i])) goto finish;
    }
    recovery=source ? yr_clamp(recovery,0,1) : 0;
    float *working=recovery>0 ? malloc((size_t)info.width*info.height*3*sizeof(float)) : NULL;
    if (recovery>0 && !working) {
        jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
        (*env)->ThrowNew(env,type,"Not enough memory for local highlight recovery");goto finish;
    }
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
    for (int pass=0;pass<(working ? 2 : 1);pass++) {
    for (unsigned y = 0; y < info.height; y++) for (unsigned x = 0; x < info.width; x++) {
        float pixel[4]; yr_read(&image, x, y, pixel);
        float c[3], codes[3], mapped[3];
        if (pass==1) memcpy(c,working+((size_t)y*info.width+x)*3,sizeof(c));
        else {
        for (int i = 0; i < 3; i++) c[i] = pixel[i];
        if (source) {
            float camera[3];
            for (int i = 0; i < 3; i++) {
                float code = image.info.format == ANDROID_BITMAP_FORMAT_RGBA_F16 ? pixel[i] : yr_srgb(pixel[i]);
                camera[i] = source==3 ? (expf((code-.614f)/.139f)-.019f)*.460771875f : code >= .20855531595f ? exp2f((code - .69336945f) / .08550479f) - .00964052f
                    : sqrtf(fmaxf(0, code) / 47.28711236f) - .05641088f;
            }
            for (int i = 0; i < 3; i++) {
                const float *row = to709[source==3 ? 0 : source-1] + i * 3;
                c[i] = row[0] * camera[0] + row[1] * camera[1] + row[2] * camera[2];
            }
        }
        if (adapt) {
            float mapped[3];
            for (int i=0;i<3;i++) mapped[i]=matrix[i*3]*c[0]+matrix[i*3+1]*c[1]+matrix[i*3+2]*c[2];
            for (int i=0;i<3;i++) c[i]=mapped[i]*gain;
        } else for (int i = 0; i < 3; i++) c[i] *= gain * balance[i];
        float luma = fmaxf(0, yr_luma(c)), delta = fmaxf(0, luma - .35f);
        if (highlights != 0 && delta > 0) {
            float k = fmaxf(.000001f, -highlights * 6);
            float target = .35f + (highlights < 0 ? log1pf(k * delta) / k : delta * (1 + 2 * highlights * delta / (delta + .35f)));
            for (int i = 0; i < 3; i++) c[i] *= target / luma;
        }
        }
        if (pass==0 && working) { memcpy(working+((size_t)y*info.width+x)*3,c,sizeof(c));continue; }
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
                const float *row = matrices[input==3 ? 0 : input-1] + i * 3;
                float v = row[0] * c[0] + row[1] * c[1] + row[2] * c[2];
                codes[i] = input==3 ? .139f*logf(fmaxf(.000001f,v/.460771875f+.019f))+.614f : v >= .01f ? log2f(v + .00964052f) * .08550479f + .69336945f
                    : 47.28711236f * powf(fmaxf(0, v + .05641088f), 2);
            }
        }
        if (strength > 0) sample(table, size, one, domain, codes, mapped,extrapolate);
        for (int i = 0; i < 3; i++) pixel[i] = viewed[i] + strength * (strength > 0 ? yr_linear(mapped[i]) - viewed[i] : 0);
        yr_write(&image, x, y, pixel);
    }
    if (pass==0 && working && !local_recovery(working,info.width,info.height,recovery)) {
        jclass type=(*env)->FindClass(env,"java/lang/IllegalStateException");
        (*env)->ThrowNew(env,type,"Not enough memory for local highlight recovery");break;
    }
    }
    free(working);
finish:
    AndroidBitmap_unlockPixels(env, bitmap);
    (*env)->ReleaseFloatArrayElements(env, entries, table, JNI_ABORT);
}
