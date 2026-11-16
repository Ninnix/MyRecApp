#include "soft_image.h"

JNIEXPORT void JNICALL Java_org_myrec_app_Native_sixteenMm(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloatArray parameters, jlong frame) {
    (void)self;
    if (!isfinite(strength) || strength <= 0 || !parameters || (*env)->GetArrayLength(env,parameters) != 6) return;
    float p[6]; (*env)->GetFloatArrayRegion(env,parameters,0,6,p);
    if ((*env)->ExceptionCheck(env)) return;
    for (int j = 0; j < 6; j++) if (!isfinite(p[j])) return;
    for (int j = 0; j < 6; j++) p[j] = yr_clamp(p[j],0,j==1?2:1);
    strength = yr_clamp(strength,0,1);
    YrPixels image;
    if (!yr_lock(env,bitmap,&image)) return;
    SoftImage a = {fmaxf(2,image.info.width),fmaxf(2,image.info.height),NULL}, b = a;
    SoftImage original = {(int)image.info.width,(int)image.info.height,NULL};
    original.pixels = malloc((size_t)original.width*original.height*3*sizeof(uint16_t));
    if (!original.pixels) goto failed;
    /* Decode once; every resampled source texel has the same half-float value. */
    for (int y = 0; y < original.height; y++) for (int x = 0; x < original.width; x++) {
        float c[4]; yr_read(&image,x,y,c);
        for (int k = 0; k < 3; k++) original.pixels[((size_t)y*original.width+x)*3+k] = yr_half(c[k]);
    }
    if (p[2] > 0) {
        a.pixels = malloc((size_t)a.width*a.height*3*sizeof(uint16_t));
        b.pixels = malloc((size_t)b.width*b.height*3*sizeof(uint16_t));
        if (!a.pixels || !b.pixels) goto failed;
        soften(&original,&a,&b,1.5f);
    }
    float scale = fminf(image.info.width,image.info.height)/1080, sizes[] = {1,1.5f,2.2f};
    float gx = yr_noise((float)(uint32_t)frame*.13f,2,0,7)*p[3]*scale*2;
    float gy = yr_noise((float)(uint32_t)frame*.13f,9,0,3)*p[3]*scale*2;
    int mono = p[5] > .5f;
    YrGrainClock clock = yr_grain_clock((uint32_t)frame);
    for (unsigned y = 0; y < image.info.height; y++) for (unsigned x = 0; x < image.info.width; x++) {
        float c[4], out[3], soft[3], u = (x+.5f)/image.info.width, v = (y+.5f)/image.info.height;
        yr_read(&image,x,y,c);
        float px = (u-.5f)/1.006f+.5f+gx/image.info.width, py = (v-.5f)/1.006f+.5f+gy/image.info.height;
        soft_sample(&original,px*image.info.width-.5f,py*image.info.height-.5f,out);
        if (b.pixels) {
            soft_sample(&b,px*b.width-.5f,py*b.height-.5f,soft);
            for (int k = 0; k < 3; k++) out[k] += (soft[k]-out[k])*p[2];
        }
        for (int k = 0; k < 3; k++) out[k] /= 1+fmaxf(out[k]-.85f,0)*.15f;
        if (mono) { float l = yr_luma(out); for (int k = 0; k < 3; k++) out[k] = l; }
        yr_grain(out,x/(scale*sizes[(int)p[1]]),y/(scale*sizes[(int)p[1]]),&clock,p[0],mono);
        if (p[4] > .5f) {
            float dx = fabsf(u-.5f)-.465f, dy = fabsf(v-.5f)-.465f;
            float distance = hypotf(fmaxf(dx,0),fmaxf(dy,0))+fminf(fmaxf(dx,dy),0);
            float gate = 1-yr_smooth(.031f,.039f,distance);
            for (int k = 0; k < 3; k++) out[k] *= gate;
        }
        for (int k = 0; k < 3; k++) c[k] += (yr_clamp(out[k],0,1)-c[k])*strength;
        yr_write(&image,x,y,c);
    }
    goto done;
failed:
    {
        jclass type = (*env)->FindClass(env,"java/lang/OutOfMemoryError");
        if (type) (*env)->ThrowNew(env,type,"Cannot allocate 16mm working images");
    }
done:
    free(original.pixels); free(a.pixels); free(b.pixels);
    AndroidBitmap_unlockPixels(env,bitmap);
}
