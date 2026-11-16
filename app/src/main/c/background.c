#include "pixels.h"

static void failure(JNIEnv *env) {
    jclass t=(*env)->FindClass(env,"java/lang/IllegalStateException");
    if (t) (*env)->ThrowNew(env,t,"Cannot allocate the background effect frame");
}
static float at(const float *p,int w,int h,int channels,float x,float y,int c) {
    x=yr_clamp(x,0,w-1); y=yr_clamp(y,0,h-1);
    int a=(int)x,b=(int)y,n=a+1<w?a+1:a,m=b+1<h?b+1:b;
    float dx=x-a,dy=y-b;
    return (1-dy)*(p[(b*w+a)*channels+c]*(1-dx)+p[(b*w+n)*channels+c]*dx)+dy*(p[(m*w+a)*channels+c]*(1-dx)+p[(m*w+n)*channels+c]*dx);
}
static int blur(float *p,int w,int h,int channels,float sigma) {
    if (sigma<.01f) return 1;
    int r=(int)ceilf(sigma*3); if (r>64) r=64;
    float weights[129],sum=0;
    for (int i=-r;i<=r;i++) { weights[i+r]=expf(-i*i/(2*sigma*sigma)); sum+=weights[i+r]; }
    for (int i=0;i<=2*r;i++) weights[i]/=sum;
    float *tmp=malloc((size_t)w*h*channels*sizeof(float)); if (!tmp) return 0;
    for (int axis=0;axis<2;axis++) {
        const float *src=axis?tmp:p; float *dst=axis?p:tmp;
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) for (int c=0;c<channels;c++) {
            float value=0;
            for (int i=-r;i<=r;i++) {
                int sx=axis?x:(int)yr_clamp(x+i,0,w-1),sy=axis?(int)yr_clamp(y+i,0,h-1):y;
                value+=src[(sy*w+sx)*channels+c]*weights[i+r];
            }
            dst[(y*w+x)*channels+c]=value;
        }
    }
    free(tmp); return 1;
}
static int morph(float *p,int w,int h,float radius) {
    if (fabsf(radius)<.001f) return 1;
    float *tmp=malloc((size_t)w*h*sizeof(float)); if (!tmp) return 0;
    int r=(int)floorf(fminf(64,fabsf(radius)));
    for (int axis=0;axis<2;axis++) {
        const float *src=axis?tmp:p; float *dst=axis?p:tmp;
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            float value=src[y*w+x];
            for (int i=-r;i<=r;i++) {
                float v=at(src,w,h,1,x+(axis?0:i),y+(axis?i:0),0);
                value=radius>0?fmaxf(value,v):fminf(value,v);
            }
            for (int sign=-1;sign<=1;sign+=2) {
                float v=at(src,w,h,1,x+(axis?0:sign*fabsf(radius)),y+(axis?sign*fabsf(radius):0),0);
                value=radius>0?fmaxf(value,v):fminf(value,v);
            }
            dst[y*w+x]=value;
        }
    }
    free(tmp); return 1;
}
static int refine(float *a,const float *guide,int w,int h,float softness,int person) {
    float *ab=malloc((size_t)w*h*2*sizeof(float)); if (!ab) return 0;
    int r=(int)((2+6*softness)*fminf(w,h)/1080); if (r<1) r=1; if (r>6) r=6;
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        float si=0,sp=0,sii=0,sip=0,count=0;
        for (int dy=-r;dy<=r;dy++) for (int dx=-r;dx<=r;dx++) {
            int sx=(int)yr_clamp(x+dx,0,w-1),sy=(int)yr_clamp(y+dy,0,h-1),i=sy*w+sx;
            si+=guide[i]; sp+=a[i]; sii+=guide[i]*guide[i]; sip+=guide[i]*a[i]; count++;
        }
        si/=count; sp/=count; sii/=count; sip/=count;
        float k=(sip-si*sp)/(fmaxf(0,sii-si*si)+.001f);
        ab[(y*w+x)*2]=k; ab[(y*w+x)*2+1]=sp-k*si;
    }
    int ok=blur(ab,w,h,2,person?.25f+.75f*softness:1+softness);
    if (ok) for (int i=0;i<w*h;i++) {
        float value=yr_clamp(ab[i*2]*guide[i]+ab[i*2+1],0,1),black=.02f+.05f*(1-softness);
        a[i]=person?yr_smooth(black,1-black,value):value;
    }
    free(ab); return ok;
}
static void screen_reference(const YrPixels *image,const float *p,float color[3]) {
    for (int c=0;c<3;c++) color[c]=yr_linear(p[10+c]);
    if (p[2]<.5f) return;
    float samples[64][4]; int greens=0,blues=0;
    for (int i=0;i<16;i++) {
        float t=(i+.5f)/16,points[4][2]={{t,.02f},{t,.98f},{.02f,t},{.98f,t}};
        for (int e=0;e<4;e++) {
            float *v=samples[i*4+e]; yr_sample(image,points[e][0]*image->info.width-.5f,points[e][1]*image->info.height-.5f,v);
            greens+=v[1]-fmaxf(v[0],v[2])>.02f; blues+=v[2]-fmaxf(v[0],v[1])>.02f;
        }
    }
    int blue=p[1]>1.5f || (p[1]<.5f && blues>greens),count=0; float values[64][3];
    for (int i=0;i<64;i++) if ((blue?samples[i][2]-fmaxf(samples[i][0],samples[i][1]):samples[i][1]-fmaxf(samples[i][0],samples[i][2]))>.02f) {
        for (int c=0;c<3;c++) values[count][c]=samples[i][c];
        count++;
    }
    if (count<4) return;
    for (int c=0;c<3;c++) {
        for (int i=1;i<count;i++) {
            float v=values[i][c]; int j=i;
            while (j>0 && values[j-1][c]>v) { values[j][c]=values[j-1][c]; j--; }
            values[j][c]=v;
        }
        color[c]=values[count/2][c];
    }
}
JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_backgroundMatte(JNIEnv *env,jobject self,jobject bitmap,jfloatArray person,jobject plate,jfloatArray settings) {
    (void)self;
    if ((*env)->GetArrayLength(env,settings)!=19 || (person && (*env)->GetArrayLength(env,person)!=192*192)) return NULL;
    float p[19]; (*env)->GetFloatArrayRegion(env,settings,0,19,p);
    YrPixels image,clean; if (!yr_lock(env,bitmap,&image)) return NULL;
    int locked=plate && yr_lock(env,plate,&clean),w=(int)image.info.width,h=(int)image.info.height,n=w*h,ok=1;
    if (plate && !locked) { AndroidBitmap_unlockPixels(env,bitmap); return NULL; }
    float *result=calloc((size_t)n*2+3,sizeof(float)),*hint=NULL,*eroded=NULL;
    jfloat *mask=person?(*env)->GetFloatArrayElements(env,person,NULL):NULL;
    if (!result || (person && !mask)) { ok=0; goto done; }
    float color[3]; screen_reference(&image,p,color);
    if (mask) {
        hint=malloc((size_t)n*sizeof(float)); eroded=malloc((size_t)n*sizeof(float));
        if (!hint || !eroded) { ok=0; goto done; }
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) hint[y*w+x]=at(mask,192,192,1,(x+.5f)*192/w-.5f,(y+.5f)*192/h-.5f,0);
        memcpy(eroded,hint,(size_t)n*sizeof(float));
        if (p[0]<.5f && p[9]>.5f) ok=morph(hint,w,h,h*.04f) && morph(eroded,w,h,-h*.03f);
    }
    for (int y=0;y<h && ok;y++) for (int x=0;x<w;x++) {
        int i=y*w+x; float c[4],screen[4]={color[0],color[1],color[2],1}; yr_read(&image,x,y,c);
        result[n+3+i]=yr_srgb(yr_clamp(yr_luma(c),0,1));
        if (locked) yr_sample(&clean,(x+.5f)*clean.info.width/w-.5f,(y+.5f)*clean.info.height/h-.5f,screen);
        int blue=p[1]>1.5f || (p[1]<.5f && screen[2]>screen[1]);
        float other=c[blue?1:2],so=screen[blue?1:2];
        float d=c[blue?2:1]-(fabsf(p[4]-.5f)<.0001f?fmaxf(c[0],other):p[4]*c[0]+(1-p[4])*other);
        float ds=screen[blue?2:1]-(fabsf(p[4]-.5f)<.0001f?fmaxf(screen[0],so):p[4]*screen[0]+(1-p[4])*so);
        float a=p[0]>.5f?(hint?hint[i]:0):yr_clamp((1-yr_clamp(p[3]*d/fmaxf(ds,.0001f),0,1)-p[5])/fmaxf(.0001f,p[6]-p[5]),0,1);
        if (p[0]<.5f && hint && p[9]>.5f) { if (hint[i]<.005f) a=0; if (eroded[i]>.995f && a>.2f) a=1; }
        result[i]=a;
    }
    if (p[0]<.5f) ok=ok && morph(result,w,h,p[8]*fminf(w,h)/1080);
    if (ok && (p[0]>.5f || p[7]>0)) ok=refine(result,result+n+3,w,h,p[7],p[0]>.5f);
    if (p[0]>.5f) ok=ok && morph(result,w,h,p[8]*fminf(w,h)/1080);
    for (int c=0;c<3;c++) result[n+c]=color[c];
done:;
    jfloatArray output=NULL;
    if (ok) { output=(*env)->NewFloatArray(env,n*2+3); if (output) (*env)->SetFloatArrayRegion(env,output,0,n*2+3,result); }
    if (mask) (*env)->ReleaseFloatArrayElements(env,person,mask,JNI_ABORT);
    free(hint); free(eroded); free(result);
    if (locked) AndroidBitmap_unlockPixels(env,plate);
    AndroidBitmap_unlockPixels(env,bitmap);
    if (!ok && !(*env)->ExceptionCheck(env)) failure(env);
    return output;
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_backgroundComposite(JNIEnv *env,jobject self,jobject bitmap,jobject background,jobject plate,jfloatArray matte,jint mw,jint mh,jfloatArray settings) {
    (void)self;
    if (mw<=0 || mh<=0 || (*env)->GetArrayLength(env,matte)!=mw*mh+3 || (*env)->GetArrayLength(env,settings)!=19) return;
    float p[19]; (*env)->GetFloatArrayRegion(env,settings,0,19,p);
    YrPixels image,bg,clean; if (!yr_lock(env,bitmap,&image)) return;
    if (!yr_lock(env,background,&bg)) { AndroidBitmap_unlockPixels(env,bitmap); return; }
    int locked=plate && yr_lock(env,plate,&clean),w=(int)image.info.width,h=(int)image.info.height;
    if (plate && !locked) { AndroidBitmap_unlockPixels(env,background); AndroidBitmap_unlockPixels(env,bitmap); return; }
    jfloat *mask=(*env)->GetFloatArrayElements(env,matte,NULL);
    int qw=w/4>1?w/4:2,qh=h/4>1?h/4:2,ok=mask!=NULL;
    int need_estimate=p[0]>.5f,need_wrap=p[15]>0;
    float *estimate=need_estimate?calloc((size_t)qw*qh*4,sizeof(float)):NULL;
    float *wrap=need_wrap?calloc((size_t)qw*qh*3,sizeof(float)):NULL;
    if ((need_estimate && !estimate) || (need_wrap && !wrap) || !mask) { ok=0; goto done; }
    for (int y=0;y<qh && (need_estimate || need_wrap);y++) for (int x=0;x<qw;x++) {
        float u=(x+.5f)/qw,v=(y+.5f)/qh,c[4],b[4],a=at(mask,mw,mh,1,u*mw-.5f,v*mh-.5f,0);
        yr_sample(&image,u*w-.5f,v*h-.5f,c); yr_sample(&bg,u*bg.info.width-.5f,v*bg.info.height-.5f,b);
        for (int i=0;i<3;i++) { if (estimate) estimate[(y*qw+x)*4+i]=c[i]*(1-a); if (wrap) wrap[(y*qw+x)*3+i]=b[i]; }
        if (estimate) estimate[(y*qw+x)*4+3]=1-a;
    }
    ok=(!estimate || blur(estimate,qw,qh,4,4*fminf(w,h)/1080)) && (!wrap || blur(wrap,qw,qh,3,w*.02f/4));
    float foreground[3]={0},target[3]={0},weight=0;
    if (p[16]>.5f) for (int y=0;y<36;y++) for (int x=0;x<64;x++) {
        float u=(x+.5f)/64,v=(y+.5f)/36,c[4],b[4],a=yr_smooth(.5f,.95f,at(mask,mw,mh,1,u*mw-.5f,v*mh-.5f,0));
        yr_sample(&image,u*w-.5f,v*h-.5f,c); yr_sample(&bg,u*bg.info.width-.5f,v*bg.info.height-.5f,b);
        for (int i=0;i<3;i++) { foreground[i]+=c[i]*a; target[i]+=b[i]; } weight+=a;
    }
    for (int y=0;y<h && ok;y++) for (int x=0;x<w;x++) {
        float u=(x+.5f)/w,v=(y+.5f)/h,c[4],b[4],screen[4],out[4],a=yr_clamp(at(mask,mw,mh,1,u*mw-.5f,v*mh-.5f,0),0,1);
        yr_read(&image,x,y,c); yr_sample(&bg,u*bg.info.width-.5f,v*bg.info.height-.5f,b);
        for (int i=0;i<3;i++) screen[i]=mask[mw*mh+i];
        if (locked) yr_sample(&clean,u*clean.info.width-.5f,v*clean.info.height-.5f,screen);
        else if (p[0]>.5f) {
            float cover=at(estimate,qw,qh,4,u*qw-.5f,v*qh-.5f,3);
            for (int i=0;i<3;i++) screen[i]=cover>.001f?at(estimate,qw,qh,4,u*qw-.5f,v*qh-.5f,i)/cover:c[i];
        }
        float straight[3];
        for (int i=0;i<3;i++) straight[i]=yr_clamp((c[i]-(1-a)*screen[i])/fmaxf(a,.0001f),0,1);
        if (p[0]<.5f) {
            int blue=p[1]>1.5f || (p[1]<.5f && screen[2]>screen[1]),channel=blue?2:1;
            float limit=(straight[0]+straight[blue?1:2])*.525f,before=straight[channel];
            straight[channel]=fminf(before,before+(limit-before)*p[14]);
            float removed=before-straight[channel]; const float compensation[3]={.3f,.6f,.1f};
            for (int i=0;i<3;i++) straight[i]+=removed*compensation[i]*.5f;
        }
        for (int i=0;i<3;i++) {
            if (p[16]>.5f && weight>.001f) straight[i]*=1+(yr_clamp((target[i]/2304)/fmaxf(foreground[i]/weight,.01f),.5f,2)-1)*p[17];
            out[i]=straight[i]*a+b[i]*(1-a)+(wrap?at(wrap,qw,qh,3,u*qw-.5f,v*qh-.5f,i)*a*(1-a)*p[15]:0);
            if (p[18]>.5f && p[18]<1.5f) out[i]=a;
            if (p[18]>1.5f) out[i]=c[i];
            out[i]=c[i]+(yr_clamp(out[i],0,1)-c[i])*p[13];
        }
        out[3]=1; yr_write(&image,x,y,out);
    }
done:
    free(estimate); free(wrap);
    if (mask) (*env)->ReleaseFloatArrayElements(env,matte,mask,JNI_ABORT);
    if (locked) AndroidBitmap_unlockPixels(env,plate);
    AndroidBitmap_unlockPixels(env,background); AndroidBitmap_unlockPixels(env,bitmap);
    if (!ok && !(*env)->ExceptionCheck(env)) failure(env);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_backgroundBlur(JNIEnv *env,jobject self,jobject bitmap,jfloat sigma,jfloat exposure) {
    (void)self;
    if (sigma<.01f && exposure==0) return;
    YrPixels image; if (!yr_lock(env,bitmap,&image)) return;
    int w=(int)image.info.width,h=(int)image.info.height;
    if (sigma<.01f) {
        float gain=exp2f(exposure);
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            float c[4]; yr_read(&image,x,y,c);
            for (int i=0;i<3;i++) c[i]*=gain;
            yr_write(&image,x,y,c);
        }
        AndroidBitmap_unlockPixels(env,bitmap); return;
    }
    /* Broad backgrounds contain no fine detail. Filter their area average so
     * export cost and scratch memory do not grow with blur radius. */
    int reduction=(int)floorf(sigma/3); if (reduction<1) reduction=1;
    int bw=(w+reduction-1)/reduction,bh=(h+reduction-1)/reduction;
    float *values=calloc((size_t)bw*bh*3,sizeof(float));
    if (!values) { AndroidBitmap_unlockPixels(env,bitmap); failure(env); return; }
    for (int y=0;y<bh;y++) for (int x=0;x<bw;x++) {
        int count=0;
        for (int dy=0;dy<reduction && y*reduction+dy<h;dy++) for (int dx=0;dx<reduction && x*reduction+dx<w;dx++) {
            float c[4]; yr_read(&image,x*reduction+dx,y*reduction+dy,c);
            for (int i=0;i<3;i++) values[(y*bw+x)*3+i]+=c[i]; count++;
        }
        for (int i=0;i<3;i++) values[(y*bw+x)*3+i]/=count;
    }
    int ok=blur(values,bw,bh,3,sigma/reduction); float gain=exp2f(exposure);
    if (ok) for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        float c[4]={0,0,0,1}; for (int i=0;i<3;i++) c[i]=at(values,bw,bh,3,(x+.5f)/reduction-.5f,(y+.5f)/reduction-.5f,i)*gain;
        yr_write(&image,x,y,c);
    }
    free(values); AndroidBitmap_unlockPixels(env,bitmap);
    if (!ok) failure(env);
}
