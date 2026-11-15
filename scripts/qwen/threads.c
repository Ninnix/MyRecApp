/* Keep the runtime's worker pool; replace its scalar float matrix paths. */
#define qwen_linear qwen_base_linear
#define qwen_linear_nobias qwen_base_linear_nobias
#define qwen_conv2d qwen_base_conv2d
#include "qwen_asr_kernels.c"
#undef qwen_linear
#undef qwen_linear_nobias
#undef qwen_conv2d
#ifdef __aarch64__
#include <arm_neon.h>
#endif

void myrec_qwen_parallel(void (*fn)(int, int, void *), void *arg) {
    parallel_for(fn, arg);
}

static float dot_float(const float *x, const float *w, int n) {
    int i=0;
    float sum=0;
#ifdef __aarch64__
    float32x4_t a=vdupq_n_f32(0), b=a;
    for (; i+8<=n; i+=8) {
        a=vfmaq_f32(a,vld1q_f32(x+i),vld1q_f32(w+i));
        b=vfmaq_f32(b,vld1q_f32(x+i+4),vld1q_f32(w+i+4));
    }
    sum=vaddvq_f32(vaddq_f32(a,b));
#endif
    for (; i<n; i++) sum+=x[i]*w[i];
    return sum;
}

typedef struct { float *y; const float *x,*w,*bias; int seq,in,out; } float_linear;
static void linear_worker(int tid,int threads,void *arg) {
    float_linear *t=arg;
    int first=t->out*tid/threads, end=t->out*(tid+1)/threads;
    for (int o=first; o<end; o++) {
        const float *w=t->w+(size_t)o*t->in;
        for (int s=0; s<t->seq; s++)
            t->y[(size_t)s*t->out+o]=dot_float(t->x+(size_t)s*t->in,w,t->in)+(t->bias?t->bias[o]:0);
    }
}

void qwen_linear(float *y,const float *x,const float *w,const float *bias,int seq,int in,int out) {
    float_linear task={y,x,w,bias,seq,in,out};
    parallel_for(linear_worker,&task);
}

void qwen_linear_nobias(float *y,const float *x,const float *w,int seq,int in,int out) {
    qwen_linear(y,x,w,NULL,seq,in,out);
}

typedef struct { float *out; const float *cols,*w,*bias; int channels,spatial,patch; } convolution;
static void conv_worker(int tid,int threads,void *arg) {
    convolution *t=arg;
    int first=t->channels*tid/threads, end=t->channels*(tid+1)/threads;
    for (int c=first; c<end; c++) {
        float *out=t->out+(size_t)c*t->spatial;
        const float *w=t->w+(size_t)c*t->patch;
        int s=0;
#ifdef __aarch64__
        for (; s+16<=t->spatial; s+=16) {
            float32x4_t a=vdupq_n_f32(t->bias?t->bias[c]:0),b=a,d=a,e=a;
            for (int p=0; p<t->patch; p++) {
                const float *col=t->cols+(size_t)p*t->spatial+s;
                a=vfmaq_n_f32(a,vld1q_f32(col),w[p]);
                b=vfmaq_n_f32(b,vld1q_f32(col+4),w[p]);
                d=vfmaq_n_f32(d,vld1q_f32(col+8),w[p]);
                e=vfmaq_n_f32(e,vld1q_f32(col+12),w[p]);
            }
            vst1q_f32(out+s,a); vst1q_f32(out+s+4,b);
            vst1q_f32(out+s+8,d); vst1q_f32(out+s+12,e);
        }
#endif
        for (; s<t->spatial; s++) {
            float sum=t->bias?t->bias[c]:0;
            for (int p=0; p<t->patch; p++) sum+=w[p]*t->cols[(size_t)p*t->spatial+s];
            out[s]=sum;
        }
    }
}

void qwen_conv2d(float *out,const float *in,const float *w,const float *bias,
    int cin,int cout,int hin,int win,int kh,int kw,int stride,int padding) {
    int hout=(hin+2*padding-kh)/stride+1, wout=(win+2*padding-kw)/stride+1;
    int patch=cin*kh*kw, spatial=hout*wout;
    float *cols=malloc((size_t)patch*spatial*sizeof(float));
    if (!cols) {
        for (int i=0; i<cout*spatial; i++) out[i]=NAN;
        return;
    }
    im2col(in,cols,cin,hin,win,kh,kw,stride,padding,hout,wout);
    convolution task={out,cols,w,bias,cout,spatial,patch};
    parallel_for(conv_worker,&task);
    free(cols);
}
