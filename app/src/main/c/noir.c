#include "grain.h"
JNIEXPORT void JNICALL Java_org_myrec_app_Native_noir(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloatArray parameters, jlong frame) {
    (void)self;
    if (!isfinite(strength) || strength <= 0 || !parameters || (*env)->GetArrayLength(env,parameters) != 4) return;
    float p[4]; (*env)->GetFloatArrayRegion(env,parameters,0,4,p);
    if ((*env)->ExceptionCheck(env)) return;
    for (int i = 0; i < 4; i++) if (!isfinite(p[i])) return;
    p[0] = yr_clamp(p[0],1,2); p[1] = yr_clamp(p[1],0,.3f);
    strength = yr_clamp(strength,0,1);
    YrPixels image;
    if (!yr_lock(env,bitmap,&image)) return;
    float scale = fminf(image.info.width,image.info.height) / 1080;
    YrGrainClock clock = yr_grain_clock((uint32_t)frame);
    for (unsigned y = 0; y < image.info.height; y++) for (unsigned x = 0; x < image.info.width; x++) {
        float c[4]; yr_read(&image,x,y,c);
        float l = yr_clamp(yr_srgb(.4f*c[0]+.5f*c[1]+.1f*c[2]),0,1);
        float a, b;
        if (p[0] == 1) { a = l; b = 1-l; }
        else if (p[0] == 1.5f) { a = l*sqrtf(l); b = (1-l)*sqrtf(1-l); }
        else if (p[0] == 2) { a = l*l; b = (1-l)*(1-l); }
        else { a = powf(l,p[0]); b = powf(1-l,p[0]); }
        l = a / fmaxf(a+b,1e-6f);
        l = yr_linear(l * (1-2*p[1]*(1-l)));
        float out[3] = {l,l,l};
        if (p[3] > .5f) {
            out[0] *= .94f + .06f*l; out[1] *= .97f + .03f*l;
        }
        if (p[2] > .5f) yr_grain(out,x/scale,y/scale,&clock,.25f,1);
        for (int k = 0; k < 3; k++) c[k] += (yr_clamp(out[k],0,1)-c[k])*strength;
        yr_write(&image,x,y,c);
    }
    AndroidBitmap_unlockPixels(env,bitmap);
}
