#include "soft_image.h"

JNIEXPORT void JNICALL Java_org_myrec_app_Native_crashZoom(JNIEnv *env,jobject self,jobject bitmap,jfloat scale,jfloat blur,jfloat tx,jfloat ty) {
    (void)self;
    if (!isfinite(scale) || scale<1 || scale>5 || !isfinite(blur) || !isfinite(tx) || !isfinite(ty)) return;
    YrPixels image,original; if (!yr_lock(env,bitmap,&image)) return;
    if (!yr_copy_pixels(&image,&original)) { AndroidBitmap_unlockPixels(env,bitmap); return; }
    blur=yr_clamp(blur,0,.12f); tx=yr_clamp(tx,0,1); ty=yr_clamp(ty,0,1);
    for (unsigned y=0;y<image.info.height;y++) for (unsigned x=0;x<image.info.width;x++) {
        float u=((x+.5f)/image.info.width-tx)/scale+tx,v=((y+.5f)/image.info.height-ty)/scale+ty,c[4]={0};
        int count=blur>0?8:1;
        for (int i=0;i<count;i++) {
            float offset=count==1?0:blur*(i/7.f-.5f),p[4];
            yr_sample(&original,(u+(u-tx)*offset)*image.info.width-.5f,(v+(v-ty)*offset)*image.info.height-.5f,p);
            for (int k=0;k<4;k++) c[k]+=p[k]/count;
        }
        yr_write(&image,x,y,c);
    }
    free(original.data); AndroidBitmap_unlockPixels(env,bitmap);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_timeFilm(JNIEnv *env,jobject self,jobject bitmap,jint kind,jfloat strength,jfloatArray parameters,jlong frame,jdouble elapsed) {
    (void)self;
    int count=kind==0?2:kind==1?6:1;
    if (kind<0 || kind>2 || !isfinite(strength) || strength<=0 || !isfinite(elapsed) || !parameters || (*env)->GetArrayLength(env,parameters)!=count) return;
    float p[6]={0}; (*env)->GetFloatArrayRegion(env,parameters,0,count,p);
    for (int i=0;i<count;i++) { if (!isfinite(p[i])) return; p[i]=yr_clamp(p[i],0,1); }
    YrPixels image; if (!yr_lock(env,bitmap,&image)) return;
    SoftImage original={(int)image.info.width,(int)image.info.height,NULL},a=original,b=original;
    original.pixels=malloc((size_t)original.width*original.height*3*sizeof(uint16_t));
    if (!original.pixels) goto done;
    for (int y=0;y<original.height;y++) for (int x=0;x<original.width;x++) {
        float c[4]; yr_read(&image,x,y,c);
        for (int k=0;k<3;k++) original.pixels[((size_t)y*original.width+x)*3+k]=yr_half(c[k]);
    }
    if (kind==1) {
        a.pixels=malloc((size_t)a.width*a.height*3*sizeof(uint16_t));b.pixels=malloc((size_t)b.width*b.height*3*sizeof(uint16_t));
        if (!a.pixels || !b.pixels) goto done;
        soften(&original,&a,&b,2);
    }
    float pixel=fminf(image.info.width,image.info.height)/1080,zoom=kind==0?1.01f:kind==1?1.012f:1;
    float weave=kind==0?p[1]:kind==1?p[2]:0,clock=(float)(uint32_t)frame;
    float gx=yr_noise(clock*(kind==0?.18f:.16f),kind==0?3:2,0,kind==0?7:5)*2*weave*pixel;
    float gy=yr_noise(clock*(kind==0?.18f:.16f),kind==0?8:9,0,kind==0?11:17)*3*weave*pixel;
    YrGrainClock grain=yr_grain_clock((uint32_t)frame);
    strength=yr_clamp(strength,0,1);
    for (unsigned y=0;y<image.info.height;y++) for (unsigned x=0;x<image.info.width;x++) {
        float u=(x+.5f)/image.info.width,v=(y+.5f)/image.info.height,c[4],out[3]; yr_read(&image,x,y,c);
        float sx=((u-.5f)/zoom+.5f)*image.info.width-.5f+gx,sy=((v-.5f)/zoom+.5f)*image.info.height-.5f+gy;
        soft_sample(&original,sx,sy,out);
        if (kind==0) {
            float flicker=1+(yr_random(4,7,(uint32_t)frame)*2-1)*.08f*p[0];
            for (int k=0;k<3;k++) out[k]*=flicker;
        } else if (kind==1) {
            float red[3],blue[3];soft_sample(&b,sx+(u-.5f)*pixel,sy+(v-.5f)*pixel,red);
            soft_sample(&b,sx,sy,out);soft_sample(&b,sx-(u-.5f)*pixel,sy-(v-.5f)*pixel,blue);out[0]=red[0];out[2]=blue[2];
            for (int k=0;k<3;k++) out[k]+=(.06f+out[k]*.88f-out[k])*p[0];
            out[0]*=1.06f;out[2]*=.92f;out[0]+=out[1]*.025f*p[0];
            float l=yr_luma(out);for (int k=0;k<3;k++) out[k]=l+(out[k]-l)*.8f;
            yr_grain(out,x/(pixel*2.2f),y/(pixel*2.2f),&grain,p[1],0);
            uint32_t cycle=(uint32_t)floor(elapsed/6);float phase=elapsed-6*cycle,begin=yr_random(cycle,12,3)*4.5f,length=.5f+yr_random(cycle,4,6);
            float pulse=phase>begin && phase<begin+length ? sinf((phase-begin)/length*3.14159265358979323846f) : 0;
            float edge=yr_random(cycle,7,11)>.5f?u:1-u,leak=expf(-edge*7)*pulse*p[4];
            out[0]+=leak;out[1]+=leak*.22f;out[2]+=leak*.035f;
            if (p[5]>.5f) {
                float dx=fabsf(u-.5f)-.445f,dy=fabsf(v-.5f)-.445f;
                float d=hypotf(fmaxf(dx,0),fmaxf(dy,0))+fminf(fmaxf(dx,dy),0),gate=1-yr_smooth(.051f,.059f,d);
                for (int k=0;k<3;k++) out[k]*=gate;
            }
        } else {
            float l=yr_luma(out),vignette=1-.12f*yr_smooth(.25f,.7f,hypotf(u-.5f,v-.5f));
            for (int k=0;k<3;k++) out[k]=(l+(out[k]-l)*.95f)*vignette;
            yr_grain(out,x/(pixel*1.5f),y/(pixel*1.5f),&grain,.3f,0);
        }
        for (int k=0;k<3;k++) c[k]+=(yr_clamp(out[k],0,1)-c[k])*strength;
        yr_write(&image,x,y,c);
    }
done:
    free(original.pixels);free(a.pixels);free(b.pixels);AndroidBitmap_unlockPixels(env,bitmap);
}

static float gray(const YrPixels *image,float x,float y) {
    float c[4];yr_sample(image,x,y,c);return yr_srgb(fmaxf(0,yr_luma(c)));
}
static float gray_sample(const float *image,int width,int height,float x,float y) {
    x=yr_clamp(x,0,width-1);y=yr_clamp(y,0,height-1);
    int a=(int)x,b=(int)y,c=a+1<width?a+1:a,d=b+1<height?b+1:b;
    float u=x-a,v=y-b;
    return (1-v)*(image[b*width+a]*(1-u)+image[b*width+c]*u)+v*(image[d*width+a]*(1-u)+image[d*width+c]*u);
}
static float *tracking_guide(const YrPixels *image) {
    int w=image->info.width,h=image->info.height;float *a=malloc((size_t)w*h*sizeof(float)),*b=malloc((size_t)w*h*sizeof(float));
    if (!a || !b) { free(a);free(b);return NULL; }
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) a[y*w+x]=gray(image,x,y);
    const float weights[]={1.f/16,4.f/16,6.f/16,4.f/16,1.f/16};
    for (int pass=0;pass<2;pass++) {
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            float sum=0;for (int i=-2;i<=2;i++) {
                int xx=pass?x:(int)yr_clamp(x+i,0,w-1),yy=pass?(int)yr_clamp(y+i,0,h-1):y;
                sum+=a[yy*w+xx]*weights[i+2];
            }
            b[y*w+x]=sum;
        }
        float *swap=a;a=b;b=swap;
    }
    free(b);return a;
}
static float correlation(const float *reference,const float *image,int width,int height,float cx,float cy,float w,float h) {
    float dot=0,sum=0,square=0;
    for (int y=0;y<16;y++) for (int x=0;x<16;x++) {
        float v=gray_sample(image,width,height,cx+((x+.5f)/16-.5f)*w,cy+((y+.5f)/16-.5f)*h),a=reference[y*16+x];
        dot+=a*v;sum+=v;square+=v*v;
    }
    float variance=square-sum*sum/256;return variance>1e-5f?dot/sqrtf(variance):-1;
}
JNIEXPORT jfloatArray JNICALL Java_org_myrec_app_Native_trackSubject(JNIEnv *env,jobject self,jobject previous,jobject current,jfloatArray bounds) {
    (void)self;
    if (!bounds || (*env)->GetArrayLength(env,bounds)!=4) return NULL;
    float p[4];(*env)->GetFloatArrayRegion(env,bounds,0,4,p);for (int i=0;i<4;i++) if (!isfinite(p[i])) return NULL;
    YrPixels a,b;if (!yr_lock(env,previous,&a)) return NULL;
    if (!yr_lock(env,current,&b)) { AndroidBitmap_unlockPixels(env,previous);return NULL; }
    float *guide=tracking_guide(&b),*old=tracking_guide(&a);
    if (!guide || !old) { free(guide);free(old);AndroidBitmap_unlockPixels(env,previous);AndroidBitmap_unlockPixels(env,current);return NULL; }
    float reference[256],mean=0,variance=0,cx=p[0]*a.info.width,cy=p[1]*a.info.height,w=p[2]*a.info.width,h=p[3]*a.info.height;
    for (int y=0;y<16;y++) for (int x=0;x<16;x++) { float v=gray_sample(old,a.info.width,a.info.height,cx+((x+.5f)/16-.5f)*w,cy+((y+.5f)/16-.5f)*h);reference[y*16+x]=v;mean+=v/256; }
    for (int i=0;i<256;i++) { reference[i]-=mean;variance+=reference[i]*reference[i]; }
    if (variance>1e-5f) for (int i=0;i<256;i++) reference[i]/=sqrtf(variance);
    float tx=p[0]*b.info.width,ty=p[1]*b.info.height,tw=p[2]*b.info.width,th=p[3]*b.info.height,score=-1;
    for (int level=1;level>=0;level--) {
        float step=level?2.f:.5f,bestx=tx,besty=ty,bestw=tw,besth=th;
        score=-1;
        int radius=level?8:3;
        for (int scale=-1;scale<=1;scale++) for (int dy=-radius;dy<=radius;dy++) for (int dx=-radius;dx<=radius;dx++) {
            float x=tx+dx*step,y=ty+dy*step,sw=tw*(1+scale*(level?.06f:.02f)),sh=th*(1+scale*(level?.06f:.02f));
            if (x-sw/2<0 || y-sh/2<0 || x+sw/2>b.info.width || y+sh/2>b.info.height) continue;
            float value=variance>1e-5f?correlation(reference,guide,b.info.width,b.info.height,x,y,sw,sh):-1;
            value-=.0001f*(abs(dx)+abs(dy)+abs(scale));
            if (value>score) { score=value;bestx=x;besty=y;bestw=sw;besth=sh; }
        }
        tx=bestx;ty=besty;tw=bestw;th=besth;
    }
    float out[]={tx/b.info.width,ty/b.info.height,tw/b.info.width,th/b.info.height,score};
    free(guide);free(old);AndroidBitmap_unlockPixels(env,previous);AndroidBitmap_unlockPixels(env,current);
    jfloatArray result=(*env)->NewFloatArray(env,5);if (result) (*env)->SetFloatArrayRegion(env,result,0,5,out);return result;
}
