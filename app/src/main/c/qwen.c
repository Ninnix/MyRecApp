#include "gguf.h"
#include "qwen_asr.h"
#include "qwen_asr_audio.h"
#include "qwen_asr_kernels.h"
#include "qwen_asr_tokenizer.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef __aarch64__
#include <arm_neon.h>
#endif

#define DIM 1024
#define QDIM 2048
#define FF 3072
#define LAYERS 28
#define VOCAB 151936
#define BUDGET 512

extern void myrec_qwen_parallel(void (*fn)(int, int, void *), void *arg);

typedef struct {
    const gguf_tensor *q, *k, *v, *o, *gate, *up, *down;
    const float *norm, *ffn_norm, *q_norm, *k_norm;
} decoder_layer;

typedef struct {
    gguf_file *file;
    qwen_ctx_t *base;
    qwen_tokenizer_t *tokenizer;
    const gguf_tensor *embedding, *output;
    const float *norm;
    decoder_layer layers[LAYERS];
    float *keys, *values;
    int cache_size, used;
    int failed;
} model;

static const gguf_tensor *tensor(model *m, const char *name, unsigned dims, size_t width, size_t rows) {
    const gguf_tensor *t = myrec_gguf_find(m->file, name);
    size_t count = 1;
    if (t) for (unsigned d = 1; d < t->dims; d++) count *= (size_t)t->shape[d];
    if (!t || t->dims != dims || t->shape[0] != width || count != rows) { m->failed = 1; return NULL; }
    return t;
}

static const float *norm(model *m, const char *name, int width) {
    const gguf_tensor *t = tensor(m, name, 1, (size_t)width, 1);
    if (!t || t->type != 0) { m->failed = 1; return NULL; }
    return (const float *)(const void *)t->data;
}

static float *floats(model *m, const char *name, unsigned dims, size_t width, size_t rows) {
    float *out = myrec_gguf_floats(tensor(m, name, dims, width, rows));
    if (!out) m->failed = 1;
    return out;
}

void myrec_qwen_free(void *handle) {
    model *m = handle;
    if (!m) return;
    qwen_free(m->base);
    qwen_tokenizer_free(m->tokenizer);
    free(m->keys); free(m->values);
    myrec_gguf_close(m->file);
    free(m);
}

void *myrec_qwen_load(const char *path, int threads) {
    model *m = calloc(1, sizeof(*m));
    if (!m) return NULL;
    m->file = myrec_gguf_open(path);
    if (!m->file) goto fail;
    m->base = calloc(1, sizeof(*m->base));
    if (!m->base) goto fail;
    qwen_set_threads(threads > 0 && threads <= 8 ? threads : 4);
    m->base->config = (qwen_config_t){
        .enc_d_model=896, .enc_layers=18, .enc_heads=14, .enc_head_dim=64,
        .enc_ffn_dim=3584, .enc_output_dim=DIM, .enc_n_window=50,
        .enc_n_window_infer=800, .enc_chunk_size=100, .enc_conv_proj_dim=7680,
        .dec_hidden=DIM, .dec_layers=LAYERS, .dec_heads=16, .dec_kv_heads=8,
        .dec_head_dim=128, .dec_intermediate=FF, .vocab_size=VOCAB,
        .dec_rms_norm_eps=1e-6f, .dec_rope_theta=1e6f,
    };
    qwen_encoder_t *e = &m->base->encoder;
    e->conv1_weight = floats(m,"audio.conv.1.weight",4,3,3*480);
    e->conv2_weight = floats(m,"audio.conv.2.weight",4,3,3*480*480);
    e->conv3_weight = floats(m,"audio.conv.3.weight",4,3,3*480*480);
    e->conv1_bias = floats(m,"audio.conv.1.bias",1,480,1);
    e->conv2_bias = floats(m,"audio.conv.2.bias",1,480,1);
    e->conv3_bias = floats(m,"audio.conv.3.bias",1,480,1);
    e->conv_out_weight = floats(m,"audio.conv_out.weight",2,7680,896);
    char name[128];
    for (int i = 0; i < 18 && !m->failed; i++) {
        qwen_enc_layer_t *l = &e->layers[i];
#define ENC(field, suffix, width, rows) do { \
        snprintf(name,sizeof(name),"audio.blk.%d.%s",i,suffix); \
        l->field = floats(m,name,(rows)==1?1:2,width,rows); \
    } while (0)
        ENC(wq_weight,"attn_q.weight",896,896); ENC(wq_bias,"attn_q.bias",896,1);
        ENC(wk_weight,"attn_k.weight",896,896); ENC(wk_bias,"attn_k.bias",896,1);
        ENC(wv_weight,"attn_v.weight",896,896); ENC(wv_bias,"attn_v.bias",896,1);
        ENC(wo_weight,"attn_out.weight",896,896); ENC(wo_bias,"attn_out.bias",896,1);
        ENC(attn_norm_weight,"attn_norm.weight",896,1); ENC(attn_norm_bias,"attn_norm.bias",896,1);
        ENC(fc1_weight,"ffn_up.weight",896,3584); ENC(fc1_bias,"ffn_up.bias",3584,1);
        ENC(fc2_weight,"ffn_down.weight",3584,896); ENC(fc2_bias,"ffn_down.bias",896,1);
        ENC(ffn_norm_weight,"ffn_norm.weight",896,1); ENC(ffn_norm_bias,"ffn_norm.bias",896,1);
#undef ENC
    }
    e->ln_post_weight = floats(m,"audio.ln_post.weight",1,896,1);
    e->ln_post_bias = floats(m,"audio.ln_post.bias",1,896,1);
    e->proj1_weight = floats(m,"audio.proj1.weight",2,896,896);
    e->proj1_bias = floats(m,"audio.proj1.bias",1,896,1);
    e->proj2_weight = floats(m,"audio.proj2.weight",2,896,DIM);
    e->proj2_bias = floats(m,"audio.proj2.bias",1,DIM,1);
    m->embedding = tensor(m,"token_embd.weight",2,DIM,VOCAB);
    m->output = tensor(m,"output.weight",2,DIM,VOCAB);
    m->norm = norm(m,"output_norm.weight",DIM);
    for (int i = 0; i < LAYERS && !m->failed; i++) {
        decoder_layer *l = &m->layers[i];
#define DEC(field, suffix, width, rows) do { \
        snprintf(name,sizeof(name),"blk.%d.%s",i,suffix); \
        l->field = tensor(m,name,2,width,rows); \
    } while (0)
#define NORM(field, suffix, width) do { \
        snprintf(name,sizeof(name),"blk.%d.%s",i,suffix); \
        l->field = norm(m,name,width); \
    } while (0)
        DEC(q,"attn_q.weight",DIM,QDIM); DEC(k,"attn_k.weight",DIM,DIM);
        DEC(v,"attn_v.weight",DIM,DIM); DEC(o,"attn_output.weight",QDIM,DIM);
        DEC(gate,"ffn_gate.weight",DIM,FF); DEC(up,"ffn_up.weight",DIM,FF);
        DEC(down,"ffn_down.weight",FF,DIM);
        NORM(norm,"attn_norm.weight",DIM); NORM(ffn_norm,"ffn_norm.weight",DIM);
        NORM(q_norm,"attn_q_norm.weight",128); NORM(k_norm,"attn_k_norm.weight",128);
#undef DEC
#undef NORM
    }
    if (m->failed) goto fail;
    char vocab[1024];
    if (snprintf(vocab,sizeof(vocab),"%s",path) >= (int)sizeof(vocab)) goto fail;
    char *slash = strrchr(vocab,'/');
    if (!slash || (size_t)(slash-vocab)+12 >= sizeof(vocab)) goto fail;
    strcpy(slash+1,"vocab.json");
    m->tokenizer = qwen_tokenizer_load(vocab);
    if (!m->tokenizer || m->tokenizer->vocab_size < 151643) goto fail;
    return m;
fail:
    myrec_qwen_free(m);
    return NULL;
}

static float dot(const float *x, const float *w, int n) {
#ifdef __aarch64__
    float32x4_t a = vdupq_n_f32(0), b = a;
    for (int i = 0; i < n; i += 8) {
        a = vfmaq_f32(a,vld1q_f32(x+i),vld1q_f32(w+i));
        b = vfmaq_f32(b,vld1q_f32(x+i+4),vld1q_f32(w+i+4));
    }
    return vaddvq_f32(vaddq_f32(a,b));
#else
    float sum = 0;
    for (int i = 0; i < n; i++) sum += x[i] * w[i];
    return sum;
#endif
}

typedef struct { const gguf_tensor *w; const float *x; float *y; int rows; } matvec;
static void matvec_worker(int tid, int threads, void *arg) {
    matvec *task = arg;
    int first = task->rows * tid / threads, end = task->rows * (tid+1) / threads;
    float row[FF];
    int width = (int)task->w->shape[0];
    for (int r = first; r < end; r++) {
        myrec_gguf_row(task->w,(size_t)r,row);
        task->y[r] = dot(task->x,row,width);
    }
}

static int linear(float *y, const float *x, const gguf_tensor *w, int seq) {
    int width = (int)w->shape[0], rows = (int)w->shape[1];
    if (seq == 1) {
        matvec task = {w,x,y,rows};
        myrec_qwen_parallel(matvec_worker,&task);
    } else {
        /* Prefill reuses each matrix across the whole prompt. Keep only this
         * matrix expanded; autoregressive decoding reads the packed rows. */
        float *expanded = myrec_gguf_floats(w);
        if (!expanded) return 0;
        qwen_linear_nobias(y,x,expanded,seq,width,rows);
        free(expanded);
    }
    return 1;
}

static int decoder(model *m, float *x, int seq) {
    if (seq <= 0 || m->used > m->cache_size - seq) return -1;
    size_t n = (size_t)seq;
    float *work = malloc(n*(DIM*5+QDIM*2+FF*2+256)*sizeof(float));
    int *positions = malloc(n*sizeof(int));
    if (!work || !positions) { free(work); free(positions); return -1; }
    float *xn=work, *q=xn+n*DIM, *k=q+n*QDIM, *v=k+n*DIM;
    float *attn=v+n*DIM, *proj=attn+n*QDIM, *gate=proj+n*DIM;
    float *up=gate+n*FF, *cosine=up+n*FF, *sine=cosine+n*128;
    for (int i=0; i<seq; i++) positions[i]=m->used+i;
    qwen_compute_rope_neox(cosine,sine,positions,seq,128,1e6f);
    int ok = 1;
    for (int i=0; i<LAYERS && ok; i++) {
        decoder_layer *l=&m->layers[i];
        qwen_rms_norm(xn,x,l->norm,seq,DIM,1e-6f);
        ok=linear(q,xn,l->q,seq) && linear(k,xn,l->k,seq) && linear(v,xn,l->v,seq);
        if (!ok) break;
        qwen_rms_norm_per_head(q,l->q_norm,seq,16,128,1e-6f);
        qwen_rms_norm_per_head(k,l->k_norm,seq,8,128,1e-6f);
        qwen_apply_rope_neox(q,cosine,sine,seq,16,128);
        qwen_apply_rope_neox(k,cosine,sine,seq,8,128);
        float *keys=m->keys+(size_t)i*m->cache_size*DIM;
        float *values=m->values+(size_t)i*m->cache_size*DIM;
        memcpy(keys+(size_t)m->used*DIM,k,n*DIM*sizeof(float));
        memcpy(values+(size_t)m->used*DIM,v,n*DIM*sizeof(float));
        qwen_causal_attention(attn,q,keys,values,seq,m->used+seq,16,8,128,1.f/sqrtf(128.f),m->used);
        ok=linear(proj,attn,l->o,seq);
        if (!ok) break;
        qwen_add_inplace(x,proj,seq*DIM);
        qwen_rms_norm(xn,x,l->ffn_norm,seq,DIM,1e-6f);
        ok=linear(gate,xn,l->gate,seq) && linear(up,xn,l->up,seq);
        if (!ok) break;
        qwen_silu(gate,seq*FF);
        qwen_mul_inplace(gate,up,seq*FF);
        ok=linear(proj,gate,l->down,seq);
        if (!ok) break;
        qwen_add_inplace(x,proj,seq*DIM);
    }
    int best=-1;
    if (ok) {
        qwen_rms_norm(xn,x+(n-1)*DIM,m->norm,1,DIM,1e-6f);
        float *logits=malloc(VOCAB*sizeof(float));
        if (logits && linear(logits,xn,m->output,1)) {
            for (int i=0; i<VOCAB; i++) if (isfinite(logits[i]) && (best<0 || logits[i]>logits[best])) best=i;
        }
        free(logits);
        m->used+=seq;
    }
    free(work); free(positions);
    return best;
}

char *myrec_qwen_transcribe(void *handle, const float *samples, int count, const char *language) {
    model *m=handle;
    if (!m || !samples || count<=0 || count>16000*12) return NULL;
    for (int i=0; i<count; i++) if (!isfinite(samples[i])) return NULL;
    if (qwen_set_force_language(m->base,language)) return NULL;
    int frames=0, encoded=0;
    float *mel=qwen_mel_spectrogram(samples,count,&frames);
    if (!mel) return NULL;
    float *audio=qwen_encoder_forward(m->base,mel,frames,&encoded);
    free(mel);
    if (!audio || encoded<=0 || encoded>512) { free(audio); return NULL; }
    int *forced=NULL, forced_count=0;
    if (m->base->force_language) {
        char prefix[96]; snprintf(prefix,sizeof(prefix),"language %s",m->base->force_language);
        forced=qwen_tokenizer_encode(m->tokenizer,prefix,&forced_count);
        if (!forced || forced_count<=0 || forced_count>32) { free(audio); free(forced); return NULL; }
    }
    const int head[]={151644,8948,198,151645,198,151644,872,198,151669};
    const int tail[]={151670,151645,198,151644,77091,198};
    int length=encoded+15+forced_count+(forced_count>0);
    float *x=malloc((size_t)length*DIM*sizeof(float));
    char *out=calloc(65536,1);
    if (!x || !out) { free(audio); free(forced); free(x); free(out); return NULL; }
    int at=0;
    for (unsigned i=0; i<sizeof(head)/sizeof(*head); i++,at++) myrec_gguf_row(m->embedding,head[i],x+(size_t)at*DIM);
    memcpy(x+(size_t)at*DIM,audio,(size_t)encoded*DIM*sizeof(float)); at+=encoded;
    for (unsigned i=0; i<sizeof(tail)/sizeof(*tail); i++,at++) myrec_gguf_row(m->embedding,tail[i],x+(size_t)at*DIM);
    for (int i=0; i<forced_count; i++,at++) myrec_gguf_row(m->embedding,forced[i],x+(size_t)at*DIM);
    if (forced_count) myrec_gguf_row(m->embedding,QWEN_TOKEN_ASR_TEXT,x+(size_t)at*DIM);
    free(audio); free(forced);
    if (m->cache_size<length+BUDGET || !m->keys || !m->values) {
        free(m->keys); free(m->values);
        m->cache_size=length+BUDGET;
        size_t elements=(size_t)LAYERS*m->cache_size*DIM;
        m->keys=malloc(elements*sizeof(float)); m->values=malloc(elements*sizeof(float));
    }
    if (!m->keys || !m->values) { free(x); free(out); return NULL; }
    m->used=0;
    int next=decoder(m,x,length), success=0, text_ready=forced_count>0;
    size_t used=0;
    for (int step=0; step<BUDGET && next>=0; step++) {
        if (next==QWEN_TOKEN_IM_END || next==151643) { success=1; break; }
        if (next==QWEN_TOKEN_ASR_TEXT) text_ready=1;
        else if (text_ready) {
            const char *piece=qwen_tokenizer_decode(m->tokenizer,next);
            size_t bytes=strlen(piece);
            if (used+bytes>=65536) break;
            memcpy(out+used,piece,bytes); used+=bytes;
        }
        myrec_gguf_row(m->embedding,(size_t)next,x);
        next=decoder(m,x,1);
    }
    free(x);
    if (!success) { free(out); return NULL; }
    return out;
}
