#include "pixels.h"

typedef struct { JNIEnv *env; jobject listener; jmethodID step; } NightProgress;
static int keep_going(NightProgress *p,int row,int total) {
    if (!p->listener) return 1;
    if ((*p->env)->ExceptionCheck(p->env)) return 0;
    return (*p->env)->CallBooleanMethod(p->env,p->listener,p->step,(jlong)row,(jlong)total) && !(*p->env)->ExceptionCheck(p->env);
}
static NightProgress progress_for(JNIEnv *env,jobject listener) {
    NightProgress p={env,listener,NULL};
    if (listener) { jclass type=(*env)->GetObjectClass(env,listener);p.step=(*env)->GetMethodID(env,type,"step","(JJ)Z");(*env)->DeleteLocalRef(env,type); }
    return p;
}
static void no_memory(JNIEnv *env) {
    jclass type=(*env)->FindClass(env,"java/lang/OutOfMemoryError");if (type) (*env)->ThrowNew(env,type,"Cannot allocate Night fix working images");
}

static float encode(float x) { return x < .018f ? 4.5f*x : 1.099f*powf(x,.45f)-.099f; }
static float decode(float x) { return x < .081f ? x/4.5f : powf((x+.099f)/1.099f,1/.45f); }
static float sample(const float *p,int w,int h,int n,float x,float y,int k) {
    x=yr_clamp(x,0,w-1); y=yr_clamp(y,0,h-1);
    int a=(int)x,b=(int)y,c=a+1<w?a+1:a,d=b+1<h?b+1:b;
    float u=x-a,v=y-b;
    return (1-v)*(p[(b*w+a)*n+k]*(1-u)+p[(b*w+c)*n+k]*u)+v*(p[(d*w+a)*n+k]*(1-u)+p[(d*w+c)*n+k]*u);
}
static float *guide(const YrPixels *image,int w,int h,NightProgress *progress) {
    float *out=malloc((size_t)w*h*sizeof(float)); if (!out) { no_memory(progress->env);return NULL; }
    for (int y=0;y<h;y++) {
        if (!keep_going(progress,y,h)) { free(out);return NULL; }
        for (int x=0;x<w;x++) {
        float sum=0;
        for (int dy=-1;dy<=1;dy++) for (int dx=-1;dx<=1;dx++) {
            float c[4]; yr_sample(image,(x+.5f+dx*.65f)*image->info.width/w-.5f,(y+.5f+dy*.65f)*image->info.height/h-.5f,c);
            for (int k=0;k<3;k++) c[k]=encode(c[k]);
            sum+=sqrtf(yr_clamp(yr_luma(c),0,1));
        }
        out[y*w+x]=roundf(sum/9*255)/255;
    }
    }
    return out;
}
/* Coarse-to-fine local flow, with a separate backward solve. Low texture and
 * occlusion are rejected by the fusion weights rather than invented motion. */
static float *flow(const float *a,const float *b,int w,int h,int *gw,int *gh,NightProgress *progress) {
    int step=4; *gw=(w+step-1)/step+1; *gh=(h+step-1)/step+1;
    float *out=calloc((size_t)*gw**gh*2,sizeof(float)); if (!out) { no_memory(progress->env);return NULL; }
    for (int y=0;y<*gh;y++) {
        if (!keep_going(progress,y,*gh)) { free(out);return NULL; }
        for (int x=0;x<*gw;x++) {
        float u=0,v=0,px=fminf(x*step,w-1),py=fminf(y*step,h-1);
        for (int level=3;level>=0;level--) {
            float stride=(float)(1<<level);
            for (int iteration=0;iteration<6;iteration++) {
                float xx=0,xy=0,yy=0,xt=0,yt=0;
                for (int dy=-3;dy<=3;dy++) for (int dx=-3;dx<=3;dx++) {
                    float sx=px+dx*stride,sy=py+dy*stride;
                    float qx=sx+u,qy=sy+v;
                    float gx=(sample(b,w,h,1,qx+stride,qy,0)-sample(b,w,h,1,qx-stride,qy,0))*.5f/stride;
                    float gy=(sample(b,w,h,1,qx,qy+stride,0)-sample(b,w,h,1,qx,qy-stride,0))*.5f/stride;
                    float dt=sample(b,w,h,1,qx,qy,0)-sample(a,w,h,1,sx,sy,0);
                    xx+=gx*gx; xy+=gx*gy; yy+=gy*gy; xt+=gx*dt; yt+=gy*dt;
                }
                float det=xx*yy-xy*xy; if (det<1e-9f) break;
                float du=yr_clamp((xy*yt-yy*xt)/det,-stride,stride),dv=yr_clamp((xy*xt-xx*yt)/det,-stride,stride);
                u=yr_clamp(u+du,-32,32); v=yr_clamp(v+dv,-32,32);
                if (du*du+dv*dv<.0025f) break;
            }
        }
        out[(y**gw+x)*2]=u; out[(y**gw+x)*2+1]=v;
    }
    }
    return out;
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_nightFuse(JNIEnv *env,jobject self,jobject original,jobject fused,jobject neighbor,jfloatArray masses,jint distance,jboolean precise,jobject listener) {
    (void)self;
    NightProgress progress=progress_for(env,listener);
    if (distance < -4 || distance>4 || !keep_going(&progress,0,1)) return;
    YrPixels a,b,out; int la=0,lb=0,lo=0; float *ga=NULL,*gb=NULL,*forward=NULL,*back=NULL,*mass=NULL;
    if (!yr_lock(env,original,&a)) return; la=1;
    if (!yr_lock(env,neighbor,&b)) goto done; lb=1;
    if (a.info.width!=b.info.width || a.info.height!=b.info.height || (*env)->GetArrayLength(env,masses)!=(jsize)(a.info.width*a.info.height)) goto done;
    float scale=fminf(.25f,(precise?384.f:256.f)/fmaxf(a.info.width,a.info.height));
    int w=fmaxf(32,roundf(a.info.width*scale)),h=fmaxf(32,roundf(a.info.height*scale));
    ga=guide(&a,w,h,&progress); if (!ga) goto done;gb=guide(&b,w,h,&progress); if (!ga || !gb) goto done;
    float mean=0,signature=0;
    for (int i=0;i<w*h;i++) mean+=ga[i]-gb[i];
    for (int y=0;y<16;y++) for (int x=0;x<16;x++) signature+=fabsf(sample(ga,w,h,1,(x+.5f)*w/16,(y+.5f)*h/16,0)-sample(gb,w,h,1,(x+.5f)*w/16,(y+.5f)*h/16,0));
    if (fabsf(mean/(w*h))>.075f || signature/256>22.f/255) goto done;
    int fw,fh,bw,bh;
    forward=flow(ga,gb,w,h,&fw,&fh,&progress);if (!forward) goto done;back=flow(gb,ga,w,h,&bw,&bh,&progress); if (!forward || !back) goto done;
    mass=(*env)->GetFloatArrayElements(env,masses,NULL); if (!mass || !yr_lock(env,fused,&out)) goto done; lo=1;
    if (out.info.width!=a.info.width || out.info.height!=a.info.height) goto done;
    for (unsigned y=0;y<a.info.height;y++) {
        if (!keep_going(&progress,y,a.info.height)) goto done;
        for (unsigned x=0;x<a.info.width;x++) {
        float gx=(x+.5f)*w/a.info.width-.5f,gy=(y+.5f)*h/a.info.height-.5f;
        float u=sample(forward,fw,fh,2,gx/4,gy/4,0),v=sample(forward,fw,fh,2,gx/4,gy/4,1);
        float qx=gx+u,qy=gy+v;
        if (qx<1 || qy<1 || qx>w-2 || qy>h-2) continue;
        float du=u+sample(back,bw,bh,2,qx/4,qy/4,0),dv=v+sample(back,bw,bh,2,qx/4,qy/4,1),agreement=du*du+dv*dv;
        if (agreement>9) continue;
        float sx=x+u*a.info.width/w,sy=y+v*a.info.height/h,diff[3]={0};
        for (int dy=-1;dy<=1;dy++) for (int dx=-1;dx<=1;dx++) {
            float c[4],d[4]; yr_sample(&a,x+dx*1.2f,y+dy*1.2f,c); yr_sample(&b,sx+dx*1.2f,sy+dy*1.2f,d);
            for (int k=0;k<3;k++) diff[k]+=(encode(c[k])-encode(d[k]))/9;
        }
        float error=diff[0]*diff[0]+diff[1]*diff[1]+diff[2]*diff[2];
        float weight=expf(-agreement/.64f-error/(3*powf(9.f/255,2))-.5f*distance*distance/9);
        size_t index=(size_t)y*a.info.width+x; float old=fmaxf(1,mass[index]),c[4],d[4]; yr_read(&out,x,y,c); yr_sample(&b,sx,sy,d);
        for (int k=0;k<3;k++) c[k]=(c[k]*old+d[k]*weight)/(old+weight);
        yr_write(&out,x,y,c); mass[index]=old+weight;
    }
    }
done:
    if (mass) (*env)->ReleaseFloatArrayElements(env,masses,mass,0);
    if (lo) AndroidBitmap_unlockPixels(env,fused);
    if (lb) AndroidBitmap_unlockPixels(env,neighbor);
    if (la) AndroidBitmap_unlockPixels(env,original);
    free(ga); free(gb); free(forward); free(back);
}
JNIEXPORT void JNICALL Java_org_myrec_app_Native_nightRestore(JNIEnv *env,jobject self,jobject bitmap,jobject fused,jfloat strength,jfloat brightness,jfloat reduction,jfloat detail,jobject listener) {
    (void)self;
    NightProgress progress=progress_for(env,listener);
    if (!keep_going(&progress,0,1)) return;
    if (!isfinite(strength) || !isfinite(brightness) || !isfinite(reduction) || !isfinite(detail) || strength<=0 || (brightness==0 && reduction==0)) return;
    YrPixels original,source; int lo=0,ls=0; uint16_t *a=NULL,*b=NULL;
    if (!yr_lock(env,bitmap,&original)) return; lo=1;
    if (!yr_lock(env,fused,&source)) goto done; ls=1;
    int w=original.info.width,h=original.info.height;
    if (source.info.width!=(unsigned)w || source.info.height!=(unsigned)h) goto done;
    a=malloc((size_t)w*h*3*sizeof(uint16_t)); b=malloc((size_t)w*h*3*sizeof(uint16_t)); if (!a || !b) { no_memory(env);goto done; }
    reduction=yr_clamp(reduction,0,3); detail=yr_clamp(detail,0,1); brightness=yr_clamp(brightness,0,8); strength=yr_clamp(strength,0,1);
    float radius=.85f+fmaxf(0,reduction-1)*1.3f,gain=exp2f(brightness);
    float noise=powf(6.f/255,2)/3*reduction*reduction*(1.5f-1.3f*detail);
    for (int y=0;y<h;y++) {
        if (!keep_going(&progress,y,h)) goto done;
        for (int x=0;x<w;x++) {
        float c[4],f[4],mean[3]={0},square[3]={0}; yr_read(&original,x,y,c); yr_read(&source,x,y,f);
        for (int k=0;k<3;k++) c[k]=encode(c[k]+(f[k]-c[k])*fminf(1,reduction));
        for (int dy=-1;dy<=1;dy++) for (int dx=-1;dx<=1;dx++) {
            float p[4],q[4]; yr_sample(&original,x+dx*radius,y+dy*radius,p); yr_sample(&source,x+dx*radius,y+dy*radius,q);
            for (int k=0;k<3;k++) { float value=encode(p[k]+(q[k]-p[k])*fminf(1,reduction)); mean[k]+=value/9; square[k]+=value*value/9; }
        }
        for (int k=0;k<3;k++) {
            float variance=fmaxf(1e-8f,square[k]-mean[k]*mean[k]),keep=yr_clamp(1-noise/variance,0,1);
            c[k]=decode(c[k]+(mean[k]+keep*(c[k]-mean[k])-c[k])*fminf(1,reduction));
        }
        float l=yr_luma(c),scale=l<=1 ? gain/(1+(gain-1)*l) : 1;
        for (int k=0;k<3;k++) a[((size_t)y*w+x)*3+k]=yr_half(encode(c[k]*scale));
    }
    }
    int r=(int)yr_clamp(10*reduction,1,30); float spatial=fmaxf(1,r*.65f);
    for (int pass=0;pass<2;pass++) {
        for (int y=0;y<h;y++) {
            if (!keep_going(&progress,y,h)) goto done;
            for (int x=0;x<w;x++) {
            float c[3]; for (int k=0;k<3;k++) c[k]=yr_float(a[((size_t)y*w+x)*3+k]);
            float l=yr_luma(c),cr=0,cb=0,total=0;
            for (int i=-r;i<=r;i++) {
                int xx=pass?x:(int)yr_clamp(x+i,0,w-1),yy=pass?(int)yr_clamp(y+i,0,h-1):y;
                float p[3]; for (int k=0;k<3;k++) p[k]=yr_float(a[((size_t)yy*w+xx)*3+k]);
                float ly=yr_luma(p),weight=expf(-(ly-l)*(ly-l)/(.0012f*fmaxf(1,reduction))-.5f*i*i/(spatial*spatial));
                cr+=(p[0]-ly)*weight; cb+=(p[2]-ly)*weight; total+=weight;
            }
            float mix=fminf(.98f,.85f*reduction);
            c[0]+=(l+cr/total-c[0])*mix; c[2]+=(l+cb/total-c[2])*mix; c[1]=(l-.2126f*c[0]-.0722f*c[2])/.7152f;
            for (int k=0;k<3;k++) b[((size_t)y*w+x)*3+k]=yr_half(fmaxf(0,c[k]));
        }
        }
        uint16_t *swap=a;a=b;b=swap;
    }
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
        float c[4]; yr_read(&original,x,y,c);
        for (int k=0;k<3;k++) c[k]+=(decode(yr_float(a[((size_t)y*w+x)*3+k]))-c[k])*strength;
        yr_write(&original,x,y,c);
    }
done:
    free(a); free(b);
    if (ls) AndroidBitmap_unlockPixels(env,fused);
    if (lo) AndroidBitmap_unlockPixels(env,bitmap);
}
