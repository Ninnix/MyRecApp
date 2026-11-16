#include "neural.h"
#include "inference.h"
#include "fft.h"
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const double pi = 3.14159265358979323846;
struct YrNeural {
    YrInference net;
    int kind, erb[32];
    float window[2048];
    float *mel;
    float analysis[480], synthesis[480], mean[32], unit[96], features[1920], bands[320], hidden[5][256];
    float complex spectra[5][481];
    int64_t hops;
    int drop, finished;
};

static void reset_deep(YrNeural *s) {
    memset(s->analysis,0,sizeof(s->analysis));memset(s->synthesis,0,sizeof(s->synthesis));
    memset(s->features,0,sizeof(s->features));memset(s->bands,0,sizeof(s->bands));
    memset(s->hidden,0,sizeof(s->hidden));memset(s->spectra,0,sizeof(s->spectra));
    for (int i=0;i<32;i++) s->mean[i]=-60-30.f*i/31;
    for (int i=0;i<96;i++) s->unit[i]=.001f+(.0001f-.001f)*i/95;
    s->drop=480;s->hops=0;s->finished=0;
}

void yr_neural_close(YrNeural *s) {
    if (!s) return;
    yr_inference_close(&s->net);
    free(s->mel);
    free(s);
}

size_t yr_neural_state(YrNeural *s,void *data,size_t size,int restore) {
    if (!s || s->kind!=0) return 0;
    size_t floats=sizeof(s->analysis)+sizeof(s->synthesis)+sizeof(s->mean)+sizeof(s->unit)+
        sizeof(s->features)+sizeof(s->bands)+sizeof(s->hidden)+sizeof(s->spectra);
    size_t required=floats+sizeof(s->hops)+sizeof(s->drop)+sizeof(s->finished);
    if (!data) return required;
    if (size!=required) return 0;
    unsigned char *p=data;
    if (restore) {
        for (size_t i=0;i<floats;i+=sizeof(float)) { float value;memcpy(&value,p+i,sizeof(value));if (!isfinite(value)) return 0; }
        int64_t hops;int drop,finished;
        memcpy(&hops,p+floats,sizeof(hops));memcpy(&drop,p+floats+sizeof(hops),sizeof(drop));memcpy(&finished,p+required-sizeof(finished),sizeof(finished));
        if (hops<0 || hops>60480000 || (drop!=0 && drop!=480) || finished!=0) return 0;
    }
#define STATE(field) do { if (restore) memcpy(&s->field,p,sizeof(s->field));else memcpy(p,&s->field,sizeof(s->field));p+=sizeof(s->field); } while (0)
    STATE(analysis);STATE(synthesis);STATE(mean);STATE(unit);STATE(features);STATE(bands);STATE(hidden);STATE(spectra);
    STATE(hops);STATE(drop);STATE(finished);
#undef STATE
    return required;
}

const char *yr_neural_error(YrNeural *s) { return s->net.error; }

YrNeural *yr_neural_open(const char *library, const char *directory, int kind,
                         int threads, char *error, size_t size) {
    YrNeural *s = calloc(1, sizeof(*s));
    if (!s) { snprintf(error, size, "Not enough memory"); return NULL; }
    s->kind = kind;
    const char *df[] = {"enc-stream.onnx", "erb_dec-stream.onnx", "df_dec-stream.onnx"};
    const char *vf[] = {"analysis.onnx", "vocoder.onnx"};
    if (!yr_inference_open(&s->net, library, directory, kind == 0 ? df : vf,
                            kind == 0 ? 3 : 2, threads)) goto failed;
    if (kind == 0) {
        reset_deep(s);
        for (int i = 0; i < 960; i++) {
            double x = sin(pi * (i + .5) / 960);
            s->window[i] = (float)sin(pi * .5 * x * x);
        }
        float high = 9.265f * log1pf(24000.f / (24.7f * 9.265f));
        int prev = 0, excess = 0, total = 0;
        for (int i = 1; i <= 32; i++) {
            float hz = 24.7f * 9.265f * (expf(high * i / (32 * 9.265f)) - 1);
            int bin = (int)roundf(hz / 50);
            int n = bin - prev - excess;
            excess = n < 2 ? 2 - n : 0;
            s->erb[i - 1] = n < 2 ? 2 : n;
            total += s->erb[i - 1];
            prev = bin;
        }
        s->erb[31] += 481 - total;
    } else {
        for (int i = 0; i < 2048; i++) s->window[i] = (float)(.5 - .5 * cos(2 * pi * i / 2048));
        char path[4096];
        snprintf(path, sizeof(path), "%s/mel_filters.npy", directory);
        FILE *f = fopen(path, "rb");
        unsigned char header[10];
        s->mel = malloc(1025 * 128 * sizeof(float));
        int ok = f && s->mel && fread(header, 1, 10, f) == 10 &&
            memcmp(header, "\x93NUMPY", 6) == 0 && header[6] == 1 &&
            fseek(f, 10 + header[8] + 256 * header[9], SEEK_SET) == 0 &&
            fread(s->mel, sizeof(float), 1025 * 128, f) == 1025 * 128;
        if (f) fclose(f);
        if (!ok) { snprintf(s->net.error, sizeof(s->net.error), "Invalid VoiceFixer mel filters"); goto failed; }
    }
    return s;
failed:
    snprintf(error, size, "%s", s->net.error);
    yr_neural_close(s);
    return NULL;
}

int yr_neural_hop(YrNeural *s, const float *input, float *output) {
    if (!s) return -1;
    if (s->kind!=0 || s->finished) { snprintf(s->net.error,sizeof(s->net.error),"Noise reduction stream is finished or unavailable");return -1; }
    YrInference *n=&s->net;const OrtApi *api=n->api;
    float complex fft[960];
    for (int i=0;i<480;i++) {
        if (!isfinite(input[i])) { snprintf(n->error,sizeof(n->error),"Invalid noise reduction samples");return -1; }
        fft[i]=s->analysis[i]*s->window[i];fft[i+480]=input[i]*s->window[i+480];s->analysis[i]=input[i];
    }
    if (!yr_fft(fft,960,0)) return -1;
    float complex *spectrum=s->spectra[s->hops%5];
    for (int f=0;f<481;f++) spectrum[f]=fft[f]/960.f;
    memmove(s->bands,s->bands+32,9*32*sizeof(float));
    int bin=0;
    for (int b=0;b<32;b++) {
        float power=0;
        for (int j=0;j<s->erb[b];j++) { float complex z=spectrum[bin++];power+=(crealf(z)*crealf(z)+cimagf(z)*cimagf(z))/s->erb[b]; }
        float db=10*log10f(power+1e-10f);s->mean[b]=.99f*s->mean[b]+.01f*db;s->bands[9*32+b]=(db-s->mean[b])/40;
    }
    for (int c=0;c<2;c++) memmove(s->features+c*960,s->features+c*960+96,9*96*sizeof(float));
    for (int f=0;f<96;f++) {
        float complex z=spectrum[f];s->unit[f]=.99f*s->unit[f]+.01f*cabsf(z);float scale=1/sqrtf(fmaxf(1e-12f,s->unit[f]));
        s->features[9*96+f]=crealf(z)*scale;s->features[960+9*96+f]=cimagf(z)*scale;
    }
    if (++s->hops<=2) return 0;
    OrtValue *in[7]={0},*enc[8]={0},*mask[3]={0},*coefs[3]={0};int result=-1;
    int64_t edim[]={1,1,10,32},sdim[]={1,2,10,96},hdim[]={1,1,256};
    in[0]=yr_inference_tensor(n,s->bands,edim,4);in[1]=yr_inference_tensor(n,s->features,sdim,4);
    for (int i=0;i<5;i++) in[i+2]=yr_inference_tensor(n,s->hidden[i],hdim,3);
    for (int i=0;i<7;i++) if (!in[i]) goto done;
    const char *inputs[]={"feat_erb","feat_spec","state_in0"};
    const char *outputs[]={"e0","e1","e2","e3","emb","c0","lsnr","state_out0"};
    if (!yr_inference_check(n,api->Run(n->sessions[0],NULL,inputs,(const OrtValue *const *)in,3,outputs,8,enc))) goto done;
    const char *mi[]={"emb","e3","e2","e1","e0","state_in0","state_in1"},*mo[]={"m","state_out0","state_out1"};
    const OrtValue *mv[]={enc[4],enc[3],enc[2],enc[1],enc[0],in[3],in[4]};
    if (!yr_inference_check(n,api->Run(n->sessions[1],NULL,mi,mv,7,mo,3,mask))) goto done;
    const char *di[]={"emb","c0","state_in0","state_in1"},*dout[]={"coefs","state_out0","state_out1"};
    const OrtValue *dv[]={enc[4],enc[5],in[5],in[6]};
    if (!yr_inference_check(n,api->Run(n->sessions[2],NULL,di,dv,4,dout,3,coefs))) goto done;
    OrtValue *states[]={enc[7],mask[1],mask[2],coefs[1],coefs[2]};
    for (int i=0;i<5;i++) {
        size_t count;float *value=yr_inference_data(n,states[i],&count);
        if (!value || count!=256) goto done;
        for (int j=0;j<256;j++) if (!isfinite(value[j])) goto done;
        memcpy(s->hidden[i],value,256*sizeof(float));
    }
    size_t mask_count,coef_count;float *m=yr_inference_data(n,mask[0],&mask_count),*c=yr_inference_data(n,coefs[0],&coef_count);
    if (!m || !c || mask_count!=32 || coef_count!=960) goto done;
    int64_t target=s->hops-3;bin=0;
    for (int b=0;b<32;b++) for (int j=0;j<s->erb[b];j++,bin++) fft[bin]=s->spectra[target%5][bin]*m[b];
    for (int f=0;f<96;f++) {
        float complex value=0;
        for (int tap=0;tap<5;tap++) {
            int64_t at=target+tap-2;if (at<0) continue;
            int index=f*10+tap*2;value+=s->spectra[at%5][f]*(c[index]+I*c[index+1]);
        }
        fft[f]=value;
    }
    fft[0]=crealf(fft[0]);fft[480]=crealf(fft[480]);
    for (int f=481;f<960;f++) fft[f]=conjf(fft[960-f]);
    if (!yr_fft(fft,960,1)) goto done;
    for (int i=0;i<480;i++) {
        output[i]=crealf(fft[i])*960*s->window[i]+s->synthesis[i];
        s->synthesis[i]=crealf(fft[i+480])*960*s->window[i+480];
        if (!isfinite(output[i])) goto done;
    }
    result=s->drop ? 0 : 480;s->drop=0;
done:
    for (int i=0;i<7;i++) if (in[i]) api->ReleaseValue(in[i]);
    for (int i=0;i<8;i++) if (enc[i]) api->ReleaseValue(enc[i]);
    for (int i=0;i<3;i++) { if (mask[i]) api->ReleaseValue(mask[i]);if (coefs[i]) api->ReleaseValue(coefs[i]); }
    if (result<0 && !n->error[0]) snprintf(n->error,sizeof(n->error),"Invalid streaming noise reduction output");
    return result;
}

int yr_neural_flush(YrNeural *s,float *output) {
    if (!s || s->kind!=0) return -1;
    if (s->finished) return 0;
    int count=0;float zero[480]={0};
    if (s->hops>0) for (int i=0;i<3;i++) {
        int n=yr_neural_hop(s,zero,output+count);if (n<0) return -1;count+=n;
    }
    s->finished=1;return count;
}

static int deepfilter(YrNeural *s,const float *input,float *output,int samples) {
    int written=0;
    for (int at=0;at<samples;at+=480) {
        float hop[480]={0},wet[480];int size=samples-at<480 ? samples-at : 480;
        memcpy(hop,input+at,(size_t)size*sizeof(float));int n=yr_neural_hop(s,hop,wet);if (n<0) return 0;
        int count=n<samples-written ? n : samples-written;memcpy(output+written,wet,(size_t)count*sizeof(float));written+=count;
    }
    float tail[1440];int n=yr_neural_flush(s,tail);if (n<0) return 0;
    int count=n<samples-written ? n : samples-written;memcpy(output+written,tail,(size_t)count*sizeof(float));return written+count==samples;
}

static int voicefixer(YrNeural *s, const float *input, float *output, int samples) {
    enum { FRAMES = 256, SAMPLES = 255 * 441 };
    if (samples != SAMPLES) return 0;
    YrInference *n = &s->net; const OrtApi *api = n->api;
    float *mel = calloc(FRAMES * 128, sizeof(float));
    OrtValue *value = NULL, *predicted = NULL, *generated = NULL;
    int ok = 0;
    if (!mel) goto done;
    for (int t = 0; t < FRAMES; t++) {
        float complex fft[2048];
        for (int i = 0; i < 2048; i++) {
            int j = t * 441 + i - 1024;
            if (j < 0) j = -j;
            if (j >= samples) j = 2 * samples - 2 - j;
            fft[i] = input[j] * s->window[i];
        }
        yr_fft2(fft, 2048, 0);
        for (int f = 0; f < 1025; f++) {
            float magnitude = sqrtf(fmaxf(1e-8f, crealf(fft[f]) * crealf(fft[f]) + cimagf(fft[f]) * cimagf(fft[f])));
            for (int m = 0; m < 128; m++) mel[t * 128 + m] += magnitude * s->mel[f * 128 + m];
        }
    }
    int64_t dims[] = {1, 1, FRAMES, 128}; value = yr_inference_tensor(n, mel, dims, 4);
    const char *in[] = {"mel_orig"}, *out[] = {"mel"}; const OrtValue *inputs[] = {value};
    if (!value || !yr_inference_check(n, api->Run(n->sessions[0], NULL, in, inputs, 1, out, 1, &predicted))) goto done;
    size_t elements; float *log_mel = yr_inference_data(n, predicted, &elements);
    if (!log_mel || elements != FRAMES * 128) goto done;
    for (size_t i = 0; i < elements; i++) log_mel[i] = powf(10, fminf(5, log_mel[i]));
    const char *vin[] = {"mel"}, *vout[] = {"wav"}; const OrtValue *vinputs[] = {predicted};
    if (!yr_inference_check(n, api->Run(n->sessions[1], NULL, vin, vinputs, 1, vout, 1, &generated))) goto done;
    float *restored = yr_inference_data(n, generated, &elements);
    if (!restored || elements != 260 * 441) goto done;
    int trim = ((int)elements - samples) / 2;
    for (int i = 0; i < samples; i++) { if (!isfinite(restored[trim + i])) goto done; output[i] = restored[trim + i]; }
    ok = 1;
done:
    if (value) api->ReleaseValue(value);
    if (predicted) api->ReleaseValue(predicted);
    if (generated) api->ReleaseValue(generated);
    free(mel);
    if (!ok && !n->error[0]) snprintf(n->error, sizeof(n->error), "Cannot restore this audio window");
    return ok;
}

int yr_neural_process(YrNeural *s, const float *input, float *output, int samples) {
    if (!s || samples < 480 || samples > 48000 * 30) return 0;
    s->net.error[0] = 0;
    if (s->kind==0) reset_deep(s);
    return s->kind == 0 ? deepfilter(s, input, output, samples) : voicefixer(s, input, output, samples);
}
