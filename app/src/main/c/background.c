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

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_backgroundGuide(JNIEnv *env,jobject self,jfloatArray values,jint w,jint h) {
    (void)self;
    if (w<=0 || h<=0 || w>512 || h>512 || (*env)->GetArrayLength(env,values)!=w*h) return NULL;
    int gw=(w+3)/4,gh=(h+3)/4;
    float *input=(*env)->GetFloatArrayElements(env,values,NULL),*guide=malloc((size_t)gw*gh*sizeof(float));
    jfloatArray output=NULL;
    if (!input || !guide) goto done;
    for (int y=0;y<gh;y++) for (int x=0;x<gw;x++) {
        double sum=0;int count=0;
        for (int dy=0;dy<4 && y*4+dy<h;dy++) for (int dx=0;dx<4 && x*4+dx<w;dx++) {
            double v=input[(y*4+dy)*w+x*4+dx];
            sum+=v<=.04045?v/12.92:pow((v+.055)/1.055,2.4);count++;
        }
        double v=sum/count;
        guide[y*gw+x]=(float)(v<=.0031308?v*12.92:1.055*pow(v,1/2.4)-.055);
    }
    output=(*env)->NewFloatArray(env,gw*gh);
    if (output) (*env)->SetFloatArrayRegion(env,output,0,gw*gh,guide);
done:
    if (input) (*env)->ReleaseFloatArrayElements(env,values,input,JNI_ABORT);
    free(guide);if (!output && !(*env)->ExceptionCheck(env)) failure(env);return output;
}

JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_backgroundTemporal(JNIEnv *env,jobject self,jobjectArray alphas,jobjectArray guides,jintArray sizes,jbooleanArray cuts,jboolean key,jboolean motion) {
    (void)self;
    int count=(*env)->GetArrayLength(env,alphas),shape[32]={0};
    if (count<1 || count>(key?6:8) || (*env)->GetArrayLength(env,guides)!=count ||
        (*env)->GetArrayLength(env,sizes)!=count*4 || (*env)->GetArrayLength(env,cuts)!=count-1) return NULL;
    (*env)->GetIntArrayRegion(env,sizes,0,count*4,shape);
    jboolean reset[7]={0};(*env)->GetBooleanArrayRegion(env,cuts,0,count-1,reset);
    jfloatArray aa[8]={0},gg[8]={0};float *alpha[8]={0},*guide[8]={0},*result=NULL;
    jfloatArray output=NULL;
    for (int f=0;f<count;f++) {
        int *s=shape+f*4;
        if (s[0]<1 || s[0]>512 || s[1]<1 || s[1]>512 || s[2]<1 || s[2]>512 || s[3]<1 || s[3]>512) goto done;
        aa[f]=(jfloatArray)(*env)->GetObjectArrayElement(env,alphas,f);
        gg[f]=(jfloatArray)(*env)->GetObjectArrayElement(env,guides,f);
        if (!aa[f] || !gg[f] || (*env)->GetArrayLength(env,aa[f])!=s[0]*s[1]+3 || (*env)->GetArrayLength(env,gg[f])!=s[2]*s[3]) goto done;
        alpha[f]=(*env)->GetFloatArrayElements(env,aa[f],NULL);guide[f]=(*env)->GetFloatArrayElements(env,gg[f],NULL);
        if (!alpha[f] || !guide[f]) goto done;
    }
    int w=shape[0],h=shape[1],n=w*h;
    result=malloc((size_t)(n+3)*sizeof(float));if (!result) goto done;
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        int i=y*w+x;float u=(x+.5f)/w,v=(y+.5f)/h,remaining=1,sum=0;
        for (int f=0;f<count-1;f++) {
            int *s=shape+f*4,*t=s+4;
            float prior=reset[f]?0:key?.25f:1.f/3;
            if (prior==0) { sum+=remaining*(s[0]==w && s[1]==h?alpha[f][i]:at(alpha[f],s[0],s[1],1,u*s[0]-.5f,v*s[1]-.5f,0));remaining=0;break; }
            if (key || motion) {
                float a=at(guide[f],s[2],s[3],1,u*s[2]-.5f,v*s[3]-.5f,0);
                float b=at(guide[f+1],t[2],t[3],1,u*t[2]-.5f,v*t[3]-.5f,0);
                float z=yr_clamp((fabsf(a-b)-(key?.01f:.025f))/(key?.01f:.075f),0,1);
                prior*=1-z*z*(3-2*z);
                if (!key) {
                    a=s[0]==w && s[1]==h?alpha[f][i]:at(alpha[f],s[0],s[1],1,u*s[0]-.5f,v*s[1]-.5f,0);
                    b=t[0]==w && t[1]==h?alpha[f+1][i]:at(alpha[f+1],t[0],t[1],1,u*t[0]-.5f,v*t[1]-.5f,0);
                    z=yr_clamp((fabsf(a-b)-.15f)/.45f,0,1);prior*=1-z*z*(3-2*z);
                }
            }
            float a=s[0]==w && s[1]==h?alpha[f][i]:at(alpha[f],s[0],s[1],1,u*s[0]-.5f,v*s[1]-.5f,0);
            sum+=remaining*(1-prior)*a;remaining*=prior;
            if (remaining==0) break;
        }
        int *s=shape+(count-1)*4;
        float oldest=s[0]==w && s[1]==h?alpha[count-1][i]:at(alpha[count-1],s[0],s[1],1,u*s[0]-.5f,v*s[1]-.5f,0);
        result[i]=yr_clamp(sum+remaining*oldest,0,1);
    }
    memcpy(result+n,alpha[0]+n,3*sizeof(float));
    output=(*env)->NewFloatArray(env,n+3);if (output) (*env)->SetFloatArrayRegion(env,output,0,n+3,result);
done:
    for (int f=0;f<count;f++) {
        if (alpha[f]) (*env)->ReleaseFloatArrayElements(env,aa[f],alpha[f],JNI_ABORT);
        if (guide[f]) (*env)->ReleaseFloatArrayElements(env,gg[f],guide[f],JNI_ABORT);
        if (aa[f]) (*env)->DeleteLocalRef(env,aa[f]);if (gg[f]) (*env)->DeleteLocalRef(env,gg[f]);
    }
    free(result);if (!output && !(*env)->ExceptionCheck(env)) failure(env);return output;
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
        for (int y=0;y<h;y++) {
            float *restrict out=dst+(size_t)y*w*channels;
            memset(out,0,(size_t)w*channels*sizeof(float));
            for (int i=-r;i<=r;i++) {
                float weight=weights[i+r];
                if (axis) {
                    int sy=y+i;if (sy<0) sy=0;if (sy>=h) sy=h-1;
                    const float *restrict row=src+(size_t)sy*w*channels;
                    for (int k=0;k<w*channels;k++) out[k]+=row[k]*weight;
                } else {
                    const float *restrict row=src+(size_t)y*w*channels;
                    int start=i<0?-i:0;if (start>w) start=w;
                    int end=w-i;if (end>w) end=w;if (end<start) end=start;
                    for (int x=0;x<start;x++) for (int c=0;c<channels;c++) out[x*channels+c]+=row[c]*weight;
                    for (int k=start*channels;k<end*channels;k++) out[k]+=row[k+i*channels]*weight;
                    for (int x=end;x<w;x++) for (int c=0;c<channels;c++) out[x*channels+c]+=row[(w-1)*channels+c]*weight;
                }
            }
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
        float u=(x+.5f)/w,v=(y+.5f)/h,c[4],b[4],screen[4],out[4],a=yr_clamp(mw==w && mh==h?mask[y*w+x]:at(mask,mw,mh,1,u*mw-.5f,v*mh-.5f,0),0,1);
        yr_read(&image,x,y,c);
        if (bg.info.width==(unsigned)w && bg.info.height==(unsigned)h) yr_read(&bg,x,y,b);
        else yr_sample(&bg,u*bg.info.width-.5f,v*bg.info.height-.5f,b);
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
        float c[4]={0,0,0,1}; for (int i=0;i<3;i++) c[i]=(reduction==1?values[(y*bw+x)*3+i]:at(values,bw,bh,3,(x+.5f)/reduction-.5f,(y+.5f)/reduction-.5f,i))*gain;
        yr_write(&image,x,y,c);
    }
    free(values); AndroidBitmap_unlockPixels(env,bitmap);
    if (!ok) failure(env);
}
