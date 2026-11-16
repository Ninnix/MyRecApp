#ifndef MYREC_SOFT_IMAGE_H
#define MYREC_SOFT_IMAGE_H
#include "grain.h"
typedef struct { int width, height; uint16_t *pixels; } SoftImage;

static void soft_sample(const SoftImage *p, float x, float y, float c[3]) {
    x = yr_clamp(x,0,p->width-1); y = yr_clamp(y,0,p->height-1);
    int ix = (int)x, iy = (int)y, nx = ix+1 < p->width ? ix+1 : ix, ny = iy+1 < p->height ? iy+1 : iy;
    float dx = x-ix, dy = y-iy;
    for (int k = 0; k < 3; k++) {
        float a = yr_float(p->pixels[((size_t)iy*p->width+ix)*3+k]);
        float b = yr_float(p->pixels[((size_t)iy*p->width+nx)*3+k]);
        float d = yr_float(p->pixels[((size_t)ny*p->width+ix)*3+k]);
        float e = yr_float(p->pixels[((size_t)ny*p->width+nx)*3+k]);
        c[k] = yr_float(yr_half((1-dy)*(a+dx*(b-a))+dy*(d+dx*(e-d))));
    }
}
static void soft_line(const SoftImage *p, int x, int y, float offset, int vertical, float c[3]) {
    float point = yr_clamp((vertical?y:x)+offset,0,(vertical?p->height:p->width)-1);
    int first = (int)point, second = first+1 < (vertical?p->height:p->width) ? first+1 : first;
    const uint16_t *a = p->pixels+((size_t)(vertical?first:y)*p->width+(vertical?x:first))*3;
    const uint16_t *b = p->pixels+((size_t)(vertical?second:y)*p->width+(vertical?x:second))*3;
    for (int k = 0; k < 3; k++) {
        float lo = yr_float(a[k]), hi = yr_float(b[k]);
        c[k] = yr_float(yr_half(lo+(hi-lo)*(point-first)));
    }
}
static void soften(const SoftImage *source, SoftImage *a, SoftImage *b, float softness) {
    float sigma = fmaxf(.25f,softness*fminf(source->width,source->height)/1080*a->width/source->width);
    int radius = (int)fminf(48,ceilf(sigma*3));
    float raw[49], weight[25], offset[25], total = 0;
    for (int j = 0; j <= radius; j++) { raw[j] = expf(-(float)(j*j)/(2*sigma*sigma)); total += raw[j]*(j?2:1); }
    int count = (radius+1)/2;
    for (int j = 0; j < count; j++) {
        int index = j*2+1;
        float next = index+1 <= radius ? raw[index+1] : 0;
        weight[j] = (raw[index]+next)/total; offset[j] = index+next/(raw[index]+next);
    }
    for (int pass = 0; pass < 2; pass++) {
        SoftImage *out = pass ? b : a;
        for (int y = 0; y < out->height; y++) for (int x = 0; x < out->width; x++) {
            float c[3], sum[3];
            float sx = (x+.5f)*source->width/out->width-.5f, sy = (y+.5f)*source->height/out->height-.5f;
            if (pass) soft_line(a,x,y,0,1,c);
            else if (source->width == out->width && source->height == out->height) soft_line(source,x,y,0,0,c);
            else soft_sample(source,sx,sy,c);
            for (int k = 0; k < 3; k++) sum[k] = c[k]*raw[0]/total;
            for (int j = 0; j < count; j++) for (int sign = -1; sign <= 1; sign += 2) {
                if (pass) soft_line(a,x,y,sign*offset[j],1,c);
                else if (source->width == out->width && source->height == out->height) soft_line(source,x,y,sign*offset[j],0,c);
                else soft_sample(source,sx+sign*offset[j]*source->width/out->width,sy,c);
                for (int k = 0; k < 3; k++) sum[k] += c[k]*weight[j];
            }
            for (int k = 0; k < 3; k++) out->pixels[((size_t)y*out->width+x)*3+k] = yr_half(sum[k]);
        }
    }
}
#endif
