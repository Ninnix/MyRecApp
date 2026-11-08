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
};

void yr_neural_close(YrNeural *s) {
    if (!s) return;
    yr_inference_close(&s->net);
    free(s->mel);
    free(s);
}

const char *yr_neural_error(YrNeural *s) { return s->net.error; }

YrNeural *yr_neural_open(const char *library, const char *directory, int kind,
                         int threads, char *error, size_t size) {
    YrNeural *s = calloc(1, sizeof(*s));
    if (!s) { snprintf(error, size, "Not enough memory"); return NULL; }
    s->kind = kind;
    const char *df[] = {"enc.onnx", "erb_dec.onnx", "df_dec.onnx"};
    const char *vf[] = {"analysis.onnx", "vocoder.onnx"};
    if (!yr_inference_open(&s->net, library, directory, kind == 0 ? df : vf,
                            kind == 0 ? 3 : 2, threads)) goto failed;
    if (kind == 0) {
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

static int deepfilter(YrNeural *s, const float *input, float *output, int samples) {
    YrInference *n = &s->net;
    const OrtApi *api = n->api;
    int frames = (samples + 479) / 480 + 2;
    float complex *spec = calloc((size_t)frames * 481, sizeof(*spec));
    float *erb = calloc((size_t)frames * 32, sizeof(float));
    float *features = calloc((size_t)frames * 96 * 2, sizeof(float));
    OrtValue *in[2] = {NULL}, *enc[7] = {NULL}, *mask = NULL, *coefs = NULL;
    int ok = 0;
    if (!spec || !erb || !features) goto done;
    float mean[32], unit[96], alpha = expf(-.01f);
    for (int i = 0; i < 32; i++) mean[i] = -60 - 30.f * i / 31;
    for (int i = 0; i < 96; i++) unit[i] = .001f + (.0001f - .001f) * i / 95;
    for (int t = 0; t < frames; t++) {
        float complex fft[960];
        for (int i = 0; i < 960; i++) {
            int at = t * 480 + i - 480;
            fft[i] = (at >= 0 && at < samples ? input[at] : 0) * s->window[i];
        }
        if (!yr_fft(fft, 960, 0)) goto done;
        for (int i = 0; i < 481; i++) spec[t * 481 + i] = fft[i] / 960.f;
        int bin = 0;
        for (int b = 0; b < 32; b++) {
            float power = 0;
            for (int i = 0; i < s->erb[b]; i++) {
                float complex z = spec[t * 481 + bin++];
                power += (crealf(z) * crealf(z) + cimagf(z) * cimagf(z)) / s->erb[b];
            }
            float db = 10 * log10f(power + 1e-10f);
            mean[b] = db * (1 - alpha) + mean[b] * alpha;
            if (t >= 2) erb[(t - 2) * 32 + b] = (db - mean[b]) / 40;
        }
        for (int f = 0; f < 96; f++) {
            float complex z = spec[t * 481 + f];
            unit[f] = cabsf(z) * (1 - alpha) + unit[f] * alpha;
            if (t >= 2) {
                features[(t - 2) * 96 + f] = crealf(z) / sqrtf(unit[f]);
                features[frames * 96 + (t - 2) * 96 + f] = cimagf(z) / sqrtf(unit[f]);
            }
        }
    }
    int64_t edim[] = {1, 1, frames, 32}, sdim[] = {1, 2, frames, 96};
    in[0] = yr_inference_tensor(n, erb, edim, 4);
    in[1] = yr_inference_tensor(n, features, sdim, 4);
    const char *in_names[] = {"feat_erb", "feat_spec"};
    const char *enc_names[] = {"e0", "e1", "e2", "e3", "emb", "c0", "lsnr"};
    if (!in[0] || !in[1] || !yr_inference_check(n, api->Run(n->sessions[0], NULL,
        in_names, (const OrtValue *const *)in, 2, enc_names, 7, enc))) goto done;
    const char *mask_names[] = {"emb", "e3", "e2", "e1", "e0"}, *mask_out[] = {"m"};
    const OrtValue *mask_in[] = {enc[4], enc[3], enc[2], enc[1], enc[0]};
    if (!yr_inference_check(n, api->Run(n->sessions[1], NULL, mask_names, mask_in, 5,
        mask_out, 1, &mask))) goto done;
    const char *df_names[] = {"emb", "c0"}, *df_out[] = {"coefs"};
    const OrtValue *df_in[] = {enc[4], enc[5]};
    if (!yr_inference_check(n, api->Run(n->sessions[2], NULL, df_names, df_in, 2,
        df_out, 1, &coefs))) goto done;
    size_t mask_size, coef_size;
    float *m = yr_inference_data(n, mask, &mask_size);
    float *c = yr_inference_data(n, coefs, &coef_size);
    if (!m || !c || mask_size != (size_t)frames * 32 || coef_size != (size_t)frames * 96 * 10) {
        snprintf(n->error, sizeof(n->error), "Unexpected DeepFilterNet3 output"); goto done;
    }
    memset(output, 0, (size_t)samples * sizeof(float));
    for (int t = 0; t < frames; t++) {
        float complex fft[960];
        int bin = 0;
        for (int b = 0; b < 32; b++) for (int j = 0; j < s->erb[b]; j++, bin++)
            fft[bin] = spec[t * 481 + bin] * m[t * 32 + b];
        // The trained deep filter uses original low bins, not the ERB-masked spectrum.
        for (int f = 0; f < 96; f++) {
            float complex z = 0;
            for (int tap = 0; tap < 5; tap++) {
                int at = t + tap - 2;
                if (at < 0 || at >= frames) continue;
                int index = (t * 96 + f) * 10 + tap * 2;
                z += spec[at * 481 + f] * (c[index] + I * c[index + 1]);
            }
            fft[f] = z;
        }
        fft[0] = crealf(fft[0]); fft[480] = crealf(fft[480]);
        for (int f = 481; f < 960; f++) fft[f] = conjf(fft[960 - f]);
        if (!yr_fft(fft, 960, 1)) goto done;
        for (int i = 0; i < 960; i++) {
            int at = t * 480 + i - 480;
            if (at >= 0 && at < samples) output[at] += crealf(fft[i]) * 960 * s->window[i];
        }
    }
    ok = 1;
done:
    for (int i = 0; i < 2; i++) if (in[i]) api->ReleaseValue(in[i]);
    for (int i = 0; i < 7; i++) if (enc[i]) api->ReleaseValue(enc[i]);
    if (mask) api->ReleaseValue(mask);
    if (coefs) api->ReleaseValue(coefs);
    free(spec); free(erb); free(features);
    if (!ok && !n->error[0]) snprintf(n->error, sizeof(n->error), "Not enough memory for noise reduction");
    return ok;
}

static float *resample(const float *input, int count, int from, int to, int *length) {
    *length = (int)llround((double)count * to / from);
    float *out = calloc((size_t)*length, sizeof(float));
    if (!out) return NULL;
    double cutoff = fmin(1, (double)to / from) * .95;
    for (int i = 0; i < *length; i++) {
        double pos = (double)i * from / to, sum = 0, norm = 0;
        int center = (int)floor(pos);
        for (int j = center - 31; j <= center + 32; j++) {
            double d = pos - j;
            if (fabs(d) >= 32) continue;
            double x = pi * d * cutoff;
            double weight = (fabs(x) < 1e-12 ? 1 : sin(x) / x) * (.5 + .5 * cos(pi * d / 32));
            int at = j < 0 ? 0 : j >= count ? count - 1 : j;
            sum += input[at] * weight;
            norm += weight;
        }
        out[i] = (float)(sum / fmax(norm, 1e-12));
    }
    return out;
}

static int voicefixer(YrNeural *s, const float *input, float *output, int samples) {
    enum { WINDOW = 501 * 441, STRIDE = 401 * 441, OVERLAP = 100 * 441 };
    YrInference *n = &s->net;
    const OrtApi *api = n->api;
    int count;
    float *wave = resample(input, samples, 48000, 44100, &count);
    float *sum = calloc((size_t)count * 2, sizeof(float));
    float *mel = calloc(501 * 128, sizeof(float));
    OrtValue *mel_value = NULL, *predicted = NULL, *generated = NULL;
    int ok = 0;
    if (!wave || !sum || !mel) goto done;
    for (int start = 0; start < count; start += STRIDE) {
        int available = count - start < WINDOW ? count - start : WINDOW;
        memset(mel, 0, 501 * 128 * sizeof(float));
        for (int t = 0; t < 501; t++) {
            float complex fft[2048];
            for (int i = 0; i < 2048; i++) {
                int at = t * 441 + i - 1024;
                while (at < 0 || at >= WINDOW) at = at < 0 ? -at : 2 * WINDOW - 2 - at;
                fft[i] = (at < available ? wave[start + at] : 0) * s->window[i];
            }
            yr_fft2(fft, 2048, 0);
            for (int f = 0; f < 1025; f++) {
                float magnitude = cabsf(fft[f]);
                for (int m = 0; m < 128; m++) mel[t * 128 + m] += magnitude * s->mel[f * 128 + m];
            }
        }
        int64_t dims[] = {1, 1, 501, 128};
        mel_value = yr_inference_tensor(n, mel, dims, 4);
        const char *in_name[] = {"mel_orig"}, *out_name[] = {"mel"};
        const OrtValue *inputs[] = {mel_value};
        if (!mel_value || !yr_inference_check(n, api->Run(n->sessions[0], NULL, in_name,
            inputs, 1, out_name, 1, &predicted))) goto done;
        size_t elements;
        float *log_mel = yr_inference_data(n, predicted, &elements);
        if (!log_mel || elements != 501 * 128) goto done;
        for (size_t i = 0; i < elements; i++) log_mel[i] = powf(10, fminf(5, log_mel[i]));
        const char *vin[] = {"mel"}, *vout[] = {"wav"};
        const OrtValue *vinputs[] = {predicted};
        if (!yr_inference_check(n, api->Run(n->sessions[1], NULL, vin, vinputs, 1, vout, 1, &generated))) goto done;
        float *restored = yr_inference_data(n, generated, &elements);
        if (!restored || elements < (size_t)available) goto done;
        for (int i = 0; i < available; i++) {
            float weight = 1;
            if (start > 0 && i < OVERLAP) weight *= i / (float)(OVERLAP - 1);
            if (start + WINDOW < count && i >= WINDOW - OVERLAP)
                weight *= (WINDOW - 1 - i) / (float)(OVERLAP - 1);
            sum[start + i] += restored[i] * weight;
            sum[count + start + i] += weight;
        }
        api->ReleaseValue(mel_value); mel_value = NULL;
        api->ReleaseValue(predicted); predicted = NULL;
        api->ReleaseValue(generated); generated = NULL;
    }
    for (int i = 0; i < count; i++) sum[i] /= fmaxf(sum[count + i], 1e-8f);
    int length;
    float *converted = resample(sum, count, 44100, 48000, &length);
    if (!converted) goto done;
    for (int i = 0; i < samples; i++) output[i] = converted[i < length ? i : length - 1];
    free(converted);
    ok = 1;
done:
    if (mel_value) api->ReleaseValue(mel_value);
    if (predicted) api->ReleaseValue(predicted);
    if (generated) api->ReleaseValue(generated);
    free(wave); free(sum); free(mel);
    if (!ok && !n->error[0]) snprintf(n->error, sizeof(n->error), "Cannot restore this audio block");
    return ok;
}

int yr_neural_process(YrNeural *s, const float *input, float *output, int samples) {
    if (!s || samples < 480 || samples > 48000 * 30) return 0;
    s->net.error[0] = 0;
    return s->kind == 0 ? deepfilter(s, input, output, samples) : voicefixer(s, input, output, samples);
}
