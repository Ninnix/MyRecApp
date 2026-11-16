#include "pixels.h"
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

typedef struct { int width, height; float *pixels; } Light;

static Light light(int width, int height) {
    return (Light){width, height, malloc((size_t)width * height * 3 * sizeof(float))};
}
static void sample(const Light *image, float x, float y, float c[3]) {
    x = yr_clamp(x, 0, image->width - 1); y = yr_clamp(y, 0, image->height - 1);
    int ix = (int)x, iy = (int)y;
    int nx = ix + 1 < image->width ? ix + 1 : ix, ny = iy + 1 < image->height ? iy + 1 : iy;
    const float *a = image->pixels + ((size_t)iy * image->width + ix) * 3;
    const float *b = image->pixels + ((size_t)iy * image->width + nx) * 3;
    const float *d = image->pixels + ((size_t)ny * image->width + ix) * 3;
    const float *e = image->pixels + ((size_t)ny * image->width + nx) * 3;
    float dx = x - ix, dy = y - iy;
    for (int i = 0; i < 3; i++) c[i] = (1 - dy) * (a[i] + dx * (b[i] - a[i])) + dy * (d[i] + dx * (e[i] - d[i]));
}
static void store(Light *image, int x, int y, const float c[3]) {
    float *p = image->pixels + ((size_t)y * image->width + x) * 3;
    for (int i = 0; i < 3; i++) p[i] = yr_float(yr_half(c[i]));
}
static void highlights(const YrPixels *input, Light *output,float low,float high) {
    int sx = ((int)input->info.width + output->width - 1) / output->width;
    int sy = ((int)input->info.height + output->height - 1) / output->height;
    int integral = input->info.width % output->width == 0 && input->info.height % output->height == 0;
    float rx = (float)input->info.width / output->width, ry = (float)input->info.height / output->height;
    for (int y = 0; y < output->height; y++) for (int x = 0; x < output->width; x++) {
        float total[3] = {0};
        for (int j = 0; j < sy; j++) for (int i = 0; i < sx; i++) {
            float c[4];
            if (integral) yr_read(input, x * sx + i, y * sy + j, c);
            else yr_sample(input, (x + (i + .5f) / sx) * rx - .5f, (y + (j + .5f) / sy) * ry - .5f, c);
            float amount = yr_smooth(low, high, yr_luma(c));
            for (int k = 0; k < 3; k++) total[k] += c[k] * amount / (sx * sy);
        }
        store(output, x, y, total);
    }
}
static void reduce(const Light *input, Light *output) {
    int sx = (input->width + output->width - 1) / output->width;
    int sy = (input->height + output->height - 1) / output->height;
    float rx = (float)input->width / output->width, ry = (float)input->height / output->height;
    for (int y = 0; y < output->height; y++) for (int x = 0; x < output->width; x++) {
        float total[3] = {0};
        for (int j = 0; j < sy; j++) for (int i = 0; i < sx; i++) {
            float c[3]; sample(input, (x + (i + .5f) / sx) * rx - .5f, (y + (j + .5f) / sy) * ry - .5f, c);
            for (int k = 0; k < 3; k++) total[k] += c[k] / (sx * sy);
        }
        store(output, x, y, total);
    }
}
static Light blur_axis(const Light *input, double requested_sigma,int horizontal_only) {
    double scale = fmin(1, 14 / fmax(.25, requested_sigma));
    int width = fmax(2, (int)(input->width * scale)), height = fmax(2, (int)(input->height * scale));
    Light reduced = {0}, a = light(width, height), b = light(width, height);
    if (!a.pixels || !b.pixels) goto failed;
    const Light *source = input;
    if (width < input->width || height < input->height) {
        reduced = light(width, height);
        if (!reduced.pixels) goto failed;
        reduce(input, &reduced); source = &reduced;
    }
    float sigma = fmaxf(.25f, (float)(requested_sigma * width / input->width));
    int radius = (int)fminf(48, ceilf(sigma * 3));
    float weights[49], total = 0;
    for (int i = 0; i <= radius; i++) {
        weights[i] = expf(-(float)(i * i) / (2 * sigma * sigma));
        total += weights[i] * (i ? 2 : 1);
    }
    for (int i = 0; i <= radius; i++) weights[i] /= total;
    for (int pass = 0; pass < 2; pass++) {
        Light *output = pass ? &b : &a;
        int vertical=pass && !horizontal_only;
        for (int y = 0; y < height; y++) for (int x = 0; x < width;) {
#if defined(__aarch64__)
            /* Four interleaved RGB pixels share the same kernel and half-float stores. */
            if (x + 4 <= width && (vertical || (x >= radius && x + 3 + radius < width))) {
                float32x4_t c0 = vdupq_n_f32(0), c1 = c0, c2 = c0;
                for (int i = -radius; i <= radius; i++) {
                    int sy = vertical ? (y + i < 0 ? 0 : y + i >= height ? height - 1 : y + i) : y;
                    const float *p = source->pixels + ((size_t)sy * width + x + (vertical ? 0 : i)) * 3;
                    float weight = weights[abs(i)];
                    c0 = vmlaq_n_f32(c0, vld1q_f32(p), weight);
                    c1 = vmlaq_n_f32(c1, vld1q_f32(p + 4), weight);
                    c2 = vmlaq_n_f32(c2, vld1q_f32(p + 8), weight);
                }
                float *p = output->pixels + ((size_t)y * width + x) * 3;
                vst1q_f32(p, vcvt_f32_f16(vcvt_f16_f32(c0)));
                vst1q_f32(p + 4, vcvt_f32_f16(vcvt_f16_f32(c1)));
                vst1q_f32(p + 8, vcvt_f32_f16(vcvt_f16_f32(c2)));
                x += 4; continue;
            }
#endif
            float c[3] = {0};
            for (int i = -radius; i <= radius; i++) {
                int sx = vertical ? x : (int)yr_clamp(x + i, 0, width - 1);
                int sy = vertical ? (int)yr_clamp(y + i, 0, height - 1) : y;
                const float *p = source->pixels + ((size_t)sy * width + sx) * 3;
                float weight = weights[abs(i)];
                for (int k = 0; k < 3; k++) c[k] += p[k] * weight;
            }
            store(output, x, y, c);
            x++;
        }
        source = &a;
    }
    free(reduced.pixels); free(a.pixels); return b;
failed:
    free(reduced.pixels); free(a.pixels); free(b.pixels); return (Light){0};
}

static Light blur(const Light *input,double sigma) { return blur_axis(input,sigma,0); }

JNIEXPORT void JNICALL Java_org_myrec_app_Native_halation(
    JNIEnv *env, jobject self, jobject bitmap, jfloat strength, jfloatArray parameters) {
    (void)self;
    if (!isfinite(strength) || strength <= 0 || !parameters || (*env)->GetArrayLength(env, parameters) != 4) return;
    float p[4]; (*env)->GetFloatArrayRegion(env, parameters, 0, 4, p);
    if ((*env)->ExceptionCheck(env)) return;
    for (int i = 0; i < 4; i++) if (!isfinite(p[i])) return;
    for (int i = 0; i < 3; i++) p[i] = yr_clamp(p[i], 0, 1);
    strength = yr_clamp(strength, 0, 1);
    YrPixels image;
    if (!yr_lock(env, bitmap, &image)) return;
    Light small = {0}, glow = {0}, halo = {0}, extra = {0};
    if (p[0] > 0 || p[1] > 0) {
        small = light(fmaxf(2, image.info.width / 4), fmaxf(2, image.info.height / 4));
        if (!small.pixels) goto failed;
        /* Threshold before reducing so one-pixel highlights survive their subcell phase. */
        highlights(&image, &small,.65f,.85f);
        if (p[0] > 0) {
            glow = blur(&small, image.info.width * .01 / 4);
            extra = blur(&small, image.info.width * .04 / 4);
            if (!glow.pixels || !extra.pixels) goto failed;
        }
        if (p[1] > 0) { halo = blur(&small, image.info.width * .006 / 4); if (!halo.pixels) goto failed; }
    }
    const float colors[][3] = {{1,1,1}, {1,.8f,.6f}, {.65f,.8f,1}}, red[3] = {1,.25f,.1f};
    const float *flash = colors[(int)yr_clamp(p[3], 0, 2)];
    for (unsigned y = 0; y < image.info.height; y++) for (unsigned x = 0; x < image.info.width; x++) {
        float c[4], g[3] = {0}, h[3] = {0}, e[3] = {0};
        yr_read(&image, x, y, c);
        float u = (x + .5f) / image.info.width, v = (y + .5f) / image.info.height;
        if (glow.pixels) {
            sample(&glow, u * glow.width - .5f, v * glow.height - .5f, g);
            sample(&extra, u * extra.width - .5f, v * extra.height - .5f, e);
        }
        if (halo.pixels) sample(&halo, u * halo.width - .5f, v * halo.height - .5f, h);
        float shadow = p[2] * .08f * (1 - yr_luma(c));
        for (int k = 0; k < 3; k++) {
            float out = c[k] + (.65f * g[k] + .35f * e[k]) * p[0] + h[0] * red[k] * p[1] + flash[k] * shadow;
            c[k] += (yr_clamp(out, 0, 1) - c[k]) * strength;
        }
        yr_write(&image, x, y, c);
    }
    goto done;
failed:
    {
        jclass type = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (type) (*env)->ThrowNew(env, type, "Cannot allocate halation working images");
    }
done:
    free(small.pixels); free(glow.pixels); free(halo.pixels); free(extra.pixels);
    AndroidBitmap_unlockPixels(env, bitmap);
}

static Light pixels(const YrPixels *image,int width,int height) {
    Light full=light(image->info.width,image->info.height);
    if (!full.pixels) return full;
    for (int y=0;y<full.height;y++) for (int x=0;x<full.width;x++) {
        float c[4];yr_read(image,x,y,c);store(&full,x,y,c);
    }
    if (width==full.width && height==full.height) return full;
    Light result=light(width,height);
    if (result.pixels) reduce(&full,&result);
    free(full.pixels);return result;
}
static void frame_failure(JNIEnv *env) {
    jclass type=(*env)->FindClass(env,"java/lang/OutOfMemoryError");
    if (type) (*env)->ThrowNew(env,type,"Cannot allocate frame effect images");
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_frameBlur(JNIEnv *env,jobject self,jobject bitmap,jfloat strength,jfloat sigma) {
    (void)self;
    if (strength<=0 || sigma<=.001f) return;
    YrPixels image;if (!yr_lock(env,bitmap,&image)) return;
    Light original=pixels(&image,fmaxf(2,image.info.width),fmaxf(2,image.info.height)),soft={0};
    if (original.pixels) soft=blur(&original,sigma);
    if (!soft.pixels) frame_failure(env);
    else for (unsigned y=0;y<image.info.height;y++) for (unsigned x=0;x<image.info.width;x++) {
        float c[4],b[3];yr_read(&image,x,y,c);
        sample(&soft,(x+.5f)*soft.width/image.info.width-.5f,(y+.5f)*soft.height/image.info.height-.5f,b);
        for (int k=0;k<3;k++) c[k]+=(b[k]-c[k])*strength;
        yr_write(&image,x,y,c);
    }
    free(original.pixels);free(soft.pixels);AndroidBitmap_unlockPixels(env,bitmap);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_widescreenFrame(JNIEnv *env,jobject self,jobject bitmap,jfloat strength,jint ratio,jboolean blur_fill,jfloat flares,jint color) {
    (void)self;
    if (strength<=0) return;
    YrPixels image;if (!yr_lock(env,bitmap,&image)) return;
    int w=image.info.width,h=image.info.height,qw=fmaxf(2,w/4),qh=fmaxf(2,h/4);
    Light small={0},streak={0},background={0},reduced={0};
    if (flares>0) {
        small=light(qw,qh);if (!small.pixels) goto failed;
        highlights(&image,&small,.85f,.95f);
        streak=blur_axis(&small,(double)w/24/4,1);if (!streak.pixels) goto failed;
    }
    if (blur_fill) {
        reduced=pixels(&image,qw,qh);if (!reduced.pixels) goto failed;
        background=blur(&reduced,12.0*fminf(w,h)/1080*qw/w);if (!background.pixels) goto failed;
    }
    const float ratios[]={2.39f,2.20f,1.85f,1.33f},colors[][3]={{.35f,.6f,1},{1,.6f,.25f},{1,1,1}};
    float frame=ratios[(int)yr_clamp(ratio,0,3)],aspect=(float)w/h;
    const float *tint=colors[(int)yr_clamp(color,0,2)];
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        float u=(x+.5f)/w,v=(y+.5f)/h,c[4],out[3],s[3]={0};yr_read(&image,x,y,c);
        int bar=aspect<frame?fabsf(v-.5f)>.5f*aspect/frame:fabsf(u-.5f)>.5f*frame/aspect;
        if (bar) {
            if (background.pixels) sample(&background,u*background.width-.5f,v*background.height-.5f,s);
            for (int k=0;k<3;k++) out[k]=s[k]*.6f;
        } else {
            if (streak.pixels) sample(&streak,u*streak.width-.5f,v*streak.height-.5f,s);
            for (int k=0;k<3;k++) out[k]=c[k]+s[k]*tint[k]*flares;
        }
        for (int k=0;k<3;k++) c[k]+=(yr_clamp(out[k],0,1)-c[k])*strength;
        if (bar) c[3]+=(1-c[3])*strength;
        yr_write(&image,x,y,c);
    }
    goto done;
failed:frame_failure(env);
done:
    free(small.pixels);free(streak.pixels);free(background.pixels);free(reduced.pixels);
    AndroidBitmap_unlockPixels(env,bitmap);
}
