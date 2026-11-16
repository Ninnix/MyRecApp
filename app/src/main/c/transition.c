#include "pixels.h"

static void framed(const YrPixels *image,float u,float v,float rw,float rh,const float *framing,float out[4]) {
    float aspect=(float)image->info.width/image->info.height,wanted=rw/rh;
    float cw=fminf(1,wanted/aspect)/framing[2],ch=fminf(1,aspect/wanted)/framing[2];
    yr_sample(image,((1-cw)*framing[0]+u*cw)*image->info.width-.5f,((1-ch)*framing[1]+v*ch)*image->info.height-.5f,out);
}
static void optical(const YrPixels *image,float x,float y,float radius,float out[4]) {
    if (radius<.35f) { yr_sample(image,x,y,out);return; }
    for (int k=0;k<4;k++) out[k]=0;
    for (int i=0;i<48;i++) {
        float r=radius*sqrtf((i+.5f)/48),angle=i*2.39996323f,c[4];yr_sample(image,x+cosf(angle)*r,y+sinf(angle)*r,c);
        float weight=r>radius*.9f?1.12f:1;
        for (int k=0;k<4;k++) out[k]+=c[k]*weight;
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
    for (unsigned y=0;y<out.info.height;y++) for (unsigned x=0;x<out.info.width;x++) {
        float c[4],d[4],color[4]={0,0,0,1},mix=e;
        yr_read(&a,x,y,c);yr_read(&b,x,y,d);
        if (t<=0) { yr_write(&out,x,y,c);continue; }if (t>=1) { yr_write(&out,x,y,d);continue; }
        if (kind==1) {
            float feather=soft?fmaxf(1,span*.035f):.5f;
            float distance=(span+2*feather)*e-span/2-feather,at=(x-w/2)*cs+(y-h/2)*sn;
            mix=yr_clamp((distance+feather-at)/(2*feather),0,1);
        } else if (kind==2) {
            float u=(x+.5f)/w,v=(y+.5f)/h,divider=yr_clamp(p[1],.15f,.85f),point=p[0]>.5f?v:u;
            int side=point>=divider,flip=p[2]>.5f;
            const YrPixels *image=side!=flip?&b:&a;const float *f=p+(side!=flip?6:3);
            if (p[0]>.5f) framed(image,u,(v-(side?divider:0))/(side?1-divider:divider),w,h*(side?1-divider:divider),f,color);
            else framed(image,(u-(side?divider:0))/(side?1-divider:divider),v,w*(side?1-divider:divider),h,f,color);
            float edge=t<.15f?t/.15f:t>.85f?(1-t)/.15f:1;
            for (int k=0;k<3;k++) color[k]=(t<.5f?c[k]:d[k])*(1-edge)+color[k]*edge;
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
            float radius=fminf(w,h)*.05f*amount*sinf(t*3.14159265359f);
            optical(&a,x,y,radius,c);optical(&b,x,y,radius,d);mix=yr_clamp((t-.35f)/.3f,0,1);
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
