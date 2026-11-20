#include "pixels.h"

typedef struct { int x,y,w,h; } Bounds;
static Bounds visible(const YrPixels *image) {
    int width=image->info.width,height=image->info.height;
    int left=width,top=height,right=-1,bottom=-1;
    for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
        const uint8_t *pixel=yr_address(image,x,y);
        if (image->info.format==ANDROID_BITMAP_FORMAT_RGBA_F16 ? ((const uint16_t *)pixel)[3]!=0 : pixel[3]!=0) {
            if (x<left) left=x;
            if (x>right) right=x;
            if (y<top) top=y;
            bottom=y;
        }
    }
    return left<=right ? (Bounds){left,top,right-left+1,bottom-top+1} : (Bounds){0,0,width,height};
}

static void framed(const YrPixels *image,Bounds b,float u,float v,float rw,float rh,const float *framing,float out[4]) {
    float aspect=(float)b.w/b.h,wanted=rw/rh;
    float cw=fminf(1,wanted/aspect)/framing[2],ch=fminf(1,aspect/wanted)/framing[2];
    float x=b.x+(yr_clamp(framing[0]-cw/2,0,1-cw)+u*cw)*b.w-.5f;
    float y=b.y+(yr_clamp(framing[1]-ch/2,0,1-ch)+v*ch)*b.h-.5f;
    yr_sample(image,yr_clamp(x,b.x,b.x+b.w-1),yr_clamp(y,b.y,b.y+b.h-1),out);
}
typedef struct { float x,y,weight; } ApertureTap;
static void optical(const YrPixels *image,float x,float y,const ApertureTap *taps,float out[4]) {
    for (int k=0;k<4;k++) out[k]=0;
    for (int i=0;i<48;i++) {
        float c[4];yr_sample(image,x+taps[i].x,y+taps[i].y,c);
        for (int k=0;k<4;k++) out[k]+=c[k]*taps[i].weight;
    }
    /* Forty-eight aperture taps, nine on the slightly emphasized outer ring. */
    for (int k=0;k<4;k++) out[k]/=48+9*.12f;
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_transition(JNIEnv *env,jobject self,jobject first,jobject second,jobject result,jint kind,jfloat progress,jfloat amount,jfloat angle,jboolean soft,jfloatArray parameters) {
    (void)self;
    if (!parameters || (*env)->GetArrayLength(env,parameters)!=14 || !isfinite(progress)) return;
    float p[14];(*env)->GetFloatArrayRegion(env,parameters,0,14,p);for (int i=0;i<14;i++) if (!isfinite(p[i])) return;
    YrPixels a,b,out;int la=0,lb=0,lo=0;
    if (!yr_lock(env,first,&a)) return;la=1;
    if (!yr_lock(env,second,&b)) goto done;lb=1;
    if (!yr_lock(env,result,&out)) goto done;lo=1;
    if (a.info.width!=b.info.width || a.info.width!=out.info.width || a.info.height!=b.info.height || a.info.height!=out.info.height) goto done;
    float t=yr_clamp(progress,0,1),e=t*t*(3-2*t),w=out.info.width,h=out.info.height;
    float cs=cosf(angle*.01745329252f),sn=sinf(angle*.01745329252f),span=fabsf(cs)*w+fabsf(sn)*h;
    Bounds area[2];
    if (kind==2 && t>0 && t<1) { area[0]=visible(&a);area[1]=visible(&b); }
    ApertureTap taps[48];
    float radius=kind==7?fminf(w,h)*.05f*amount*sinf(t*3.14159265359f):0;
    if (radius>=.35f) for (int i=0;i<48;i++) {
        float r=radius*sqrtf((i+.5f)/48),angle=i*2.39996323f;
        taps[i]=(ApertureTap){cosf(angle)*r,sinf(angle)*r,r>radius*.9f?1.12f:1};
    }
    for (unsigned y=0;y<out.info.height;y++) for (unsigned x=0;x<out.info.width;x++) {
        float c[4],d[4],color[4]={0,0,0,1},mix=e;
        yr_read(&a,x,y,c);yr_read(&b,x,y,d);
        if (kind==2) {
            float bg[4];yr_read(&out,x,y,bg);
            for (int k=0;k<3;k++) { c[k]+=bg[k]*(1-c[3]);d[k]+=bg[k]*(1-d[3]); }
            c[3]=d[3]=1;
        }
        if (t<=0) { yr_write(&out,x,y,c);continue; }if (t>=1) { yr_write(&out,x,y,d);continue; }
        if (kind==1) {
            float feather=soft?fmaxf(1,span*.035f):.5f;
            float distance=(span+2*feather)*e-span/2-feather,at=(x+.5f-w/2)*cs+(y+.5f-h/2)*sn;
            mix=yr_clamp((distance+feather-at)/(2*feather),0,1);
        } else if (kind==2) {
            float u=(x+.5f)/w,v=(y+.5f)/h,divider=yr_clamp(p[1],.15f,.85f),point=p[0]>.5f?v:u;
            int side=point>=divider,flip=p[2]>.5f;
            int index=side!=flip;
            const YrPixels *image=index?&b:&a;const float *f=p+(index?6:3);
            if (p[0]>.5f) framed(image,area[index],u,(v-(side?divider:0))/(side?1-divider:divider),w,h*(side?1-divider:divider),f,color);
            else framed(image,area[index],(u-(side?divider:0))/(side?1-divider:divider),v,w*(side?1-divider:divider),h,f,color);
            float axis=p[0]>.5f?h:w,line=fmaxf(1,fminf(w,h)*.002f);
            float stroke=yr_clamp((line+1)/2-fabsf((point-divider)*axis),0,1);
            float edge=t<.15f?t/.15f:t>.85f?(1-t)/.15f:1;
            for (int k=0;k<3;k++) color[k]=(t<.5f?c[k]:d[k])*(1-edge)+(color[k]*(1-stroke)+.25f*stroke)*edge;
            color[3]=1;
            yr_write(&out,x,y,color);continue;
        } else if (kind==3) {
            float key[3];for (int k=0;k<3;k++) key[k]=yr_clamp(yr_srgb(c[k]),0,1);
            float threshold=e*1.16f-.08f,l=yr_luma(key);
            mix=yr_clamp((amount<.5f?l+threshold-1:threshold-l)/.08f+.5f,0,1);
        } else if (kind==4) {
            if (t<.5f) { mix=t*2;for (int k=0;k<3;k++) d[k]=p[9+k]; }
            else { mix=t*2-1;for (int k=0;k<3;k++) c[k]=p[9+k]; }
        } else if (kind==5) mix=p[12];
        else if (kind==6) mix=e;
        else if (kind==7) {
            mix=yr_clamp((t-.35f)/.3f,0,1);
            if (radius>=.35f) {
                if (mix<1) optical(&a,x,y,taps,c);
                if (mix>0) optical(&b,x,y,taps,d);
            }
        } else if (kind==8) {
            float distance=(p[0]>.5f?h:w)*.65f*(p[2]>.5f?-1:1),radius=fminf(w,h)*.12f*sinf(t*3.14159265359f);
            for (int k=0;k<4;k++) c[k]=d[k]=0;
            for (int i=0;i<16;i++) {
                float delta=radius*(i/15.f-.5f)*2,aa[4],bb[4];
                float shiftA=e*distance+delta,shiftB=-(1-e)*distance+delta;
                yr_sample(&a,x+(p[0]>.5f?0:shiftA),y+(p[0]>.5f?shiftA:0),aa);
                yr_sample(&b,x+(p[0]>.5f?0:shiftB),y+(p[0]>.5f?shiftB:0),bb);
                for (int k=0;k<4;k++) { c[k]+=aa[k]/16;d[k]+=bb[k]/16; }
            }
        }
        for (int k=0;k<3;k++) color[k]=c[k]*(1-mix)+d[k]*mix;
        yr_write(&out,x,y,color);
    }
done:
    if (lo) AndroidBitmap_unlockPixels(env,result);
    if (lb) AndroidBitmap_unlockPixels(env,second);
    if (la) AndroidBitmap_unlockPixels(env,first);
}
