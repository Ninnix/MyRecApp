#ifndef MYREC_LOCAL_RECOVERY_H
#define MYREC_LOCAL_RECOVERY_H
#include "color_space.h"
#include <stdlib.h>

typedef struct { int width,window,count;float *rows;double *sum; } RecoveryRows;
static int recovery_rows(RecoveryRows *box,int width,int radius) {
    box->width=width;box->window=2*radius+1;box->count=0;
    box->rows=malloc((size_t)box->window*width*2*sizeof(float));
    box->sum=calloc((size_t)width*2,sizeof(double));
    return box->rows && box->sum;
}
static int recovery_add(RecoveryRows *box,const float *row,float *out) {
    float *old=box->rows+(size_t)(box->count%box->window)*box->width*2;
    for (int i=0;i<box->width*2;i++) {
        if (box->count>=box->window) box->sum[i]-=old[i];
        box->sum[i]+=row[i];old[i]=row[i];out[i]=(float)(box->sum[i]/box->window);
    }
    return ++box->count>=box->window;
}
static void recovery_horizontal(const float *row,float *out,int width,int radius) {
    for (int c=0;c<2;c++) {
        double sum=0;
        for (int x=-radius;x<=radius;x++) sum+=row[(int)yr_clamp(x,0,width-1)*2+c];
        for (int x=0;x<width;x++) {
            out[x*2+c]=(float)(sum/(2*radius+1));
            sum+=(double)row[(int)yr_clamp(x+radius+1,0,width-1)*2+c]-row[(int)yr_clamp(x-radius,0,width-1)*2+c];
        }
    }
}
static inline float recovery_view(float y) { return y<=.6f ? y : .6f+.4f*(y-.6f)/(y-.2f); }
static void recovery_reconstruct(float *rgb,const float *guide,int width,float strength) {
    for (int x=0;x<width;x++) {
        float *c=rgb+x*3,y=fmaxf(.000001f,yr_luma(c)),l=log2f(y),baseLog=guide[x*2]*l+guide[x*2+1],base=exp2f(baseLog);
        float adjusted=base<=.35f ? base : .35f+log1pf(12*(base-.35f))/12;
        float local=recovery_view(adjusted)*exp2f(l-baseLog);
        if (local>.9f) local=.9f+.1f*(local-.9f)/(local-.8f);
        float original=recovery_view(y),target=original+(local-original)*strength;
        float scene=target<=.6f ? target : .6f+.4f*(target-.6f)/fmaxf(.000001f,1-target);
        for (int k=0;k<3;k++) c[k]*=scene/y;
    }
}
/* Stream both clamped box means. Scratch grows with width and radius, not image area. */
static int local_recovery(float *rgb,int w,int h,float strength) {
    if (strength<=0) return 1;
    int radius=(int)fmaxf(1,roundf(60*fminf(w,h)/1080)),last=-1,coefficients=0,result=0;
    RecoveryRows moments={0},guide={0};
    float *storage=malloc((size_t)w*2*5*sizeof(float));
    int ready=recovery_rows(&moments,w,radius) && recovery_rows(&guide,w,radius) && storage;
    if (!ready) goto finish;
    float *row=storage,*horizontal=row+w*2,*coefficient=horizontal+w*2,*filtered=coefficient+w*2,*mean=filtered+w*2;
    for (int input=-radius;input<h+radius;input++) {
        int y=(int)yr_clamp(input,0,h-1);
        if (y!=last) {
            for (int x=0;x<w;x++) { float l=log2f(fmaxf(.000001f,yr_luma(rgb+((size_t)y*w+x)*3)));row[x*2]=l;row[x*2+1]=l*l; }
            recovery_horizontal(row,horizontal,w,radius);last=y;
        }
        if (!recovery_add(&moments,horizontal,coefficient)) continue;
        for (int x=0;x<w;x++) {
            float m=coefficient[x*2],variance=fmaxf(0,coefficient[x*2+1]-m*m),a=variance/(variance+.12f);
            coefficient[x*2]=a;coefficient[x*2+1]=m*(1-a);
        }
        recovery_horizontal(coefficient,filtered,w,radius);
        int copies=coefficients++==0 ? radius+1 : 1;
        for (int k=0;k<copies;k++) if (recovery_add(&guide,filtered,mean)) recovery_reconstruct(rgb+(size_t)result++*w*3,mean,w,strength);
    }
    for (int k=0;k<radius;k++) if (recovery_add(&guide,filtered,mean)) recovery_reconstruct(rgb+(size_t)result++*w*3,mean,w,strength);
finish:
    free(moments.rows);free(moments.sum);free(guide.rows);free(guide.sum);free(storage);return ready;
}
#endif
