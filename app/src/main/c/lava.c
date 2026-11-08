#include "lava.h"
#include "fft.h"
#include <complex.h>
#include <dlfcn.h>
#include <math.h>
#include <onnxruntime_c_api.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FFT 2048
#define HOP 512
#define BINS 1025
#define MELS 80
static const double pi = 3.14159265358979323846;

struct YrLava {
    void *library;
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *backbone, *head;
    OrtMemoryInfo *memory;
    float window[FFT], filters[MELS][BINS];
    char error[512];
};

static float mel(float hz) {
    return hz < 1000 ? hz / (200.0f / 3) : 15 + logf(hz / 1000) / (logf(6.4f) / 27);
}

static float hz(float value) {
    return value < 15 ? value * (200.0f / 3) : 1000 * expf((value - 15) * logf(6.4f) / 27);
}

static int checked(YrLava *s, OrtStatus *status) {
    if (!status)
        return 1;
    snprintf(s->error, sizeof(s->error), "%s", s->api->GetErrorMessage(status));
    s->api->ReleaseStatus(status);
    return 0;
}

void yr_lava_close(YrLava *s) {
    if (!s)
        return;
    if (s->api) {
        if (s->memory)
            s->api->ReleaseMemoryInfo(s->memory);
        if (s->head)
            s->api->ReleaseSession(s->head);
        if (s->backbone)
            s->api->ReleaseSession(s->backbone);
        if (s->env)
            s->api->ReleaseEnv(s->env);
    }
    if (s->library)
        dlclose(s->library);
    free(s);
}

YrLava *yr_lava_open(const char *library, const char *directory, int threads, char *error,
                     size_t size) {
    YrLava *s = calloc(1, sizeof(*s));
    OrtSessionOptions *options = NULL;
    if (!s) {
        snprintf(error, size, "Out of memory");
        return NULL;
    }
    s->library = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!s->library) {
        snprintf(s->error, sizeof(s->error), "%s", dlerror());
        goto failed;
    }
    const OrtApiBase *(*get_api)(void) =
        (const OrtApiBase *(*)(void))dlsym(s->library, "OrtGetApiBase");
    if (!get_api || !(s->api = get_api()->GetApi(ORT_API_VERSION))) {
        snprintf(s->error, sizeof(s->error), "Unsupported inference runtime");
        goto failed;
    }
    const OrtApi *api = s->api;
    if (!checked(s, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "yourec-lava", &s->env)) ||
        !checked(s, api->CreateSessionOptions(&options)) ||
        !checked(s, api->SetIntraOpNumThreads(options, threads)) ||
        !checked(s, api->SetInterOpNumThreads(options, 1)) ||
        !checked(s,
                 api->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0")) ||
        !checked(s, api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) ||
        !checked(s, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &s->memory)))
        goto failed;
    char path[4096];
    snprintf(path, sizeof(path), "%s/enhancer_backbone.onnx", directory);
    if (!checked(s, api->CreateSession(s->env, path, options, &s->backbone)))
        goto failed;
    snprintf(path, sizeof(path), "%s/enhancer_spec_head.onnx", directory);
    if (!checked(s, api->CreateSession(s->env, path, options, &s->head)))
        goto failed;
    api->ReleaseSessionOptions(options);
    for (int i = 0; i < FFT; i++)
        s->window[i] = .5f - .5f * cosf((float)(2 * pi * i / FFT));
    float edges[MELS + 2];
    for (int i = 0; i < MELS + 2; i++)
        edges[i] = hz(mel(8000) * i / (MELS + 1));
    for (int m = 0; m < MELS; m++)
        for (int f = 0; f < BINS; f++) {
            float frequency = 22050.0f * f / (BINS - 1);
            float left = (frequency - edges[m]) / (edges[m + 1] - edges[m]);
            float right = (edges[m + 2] - frequency) / (edges[m + 2] - edges[m + 1]);
            s->filters[m][f] = fmaxf(0, fminf(left, right)) * 2 / (edges[m + 2] - edges[m]);
        }
    return s;
failed:
    if (options)
        s->api->ReleaseSessionOptions(options);
    snprintf(error, size, "%s", s->error);
    yr_lava_close(s);
    return NULL;
}

const char *yr_lava_error(const YrLava *s) { return s->error; }

static int reflect(int sample, int count) {
    while (sample < 0 || sample >= count)
        sample = sample < 0 ? -sample : count * 2 - 2 - sample;
    return sample;
}

int yr_lava_process(YrLava *s, const float *input, float *output, int samples) {
    if (samples < FFT || samples > 48000 * 30) {
        snprintf(s->error, sizeof(s->error), "Audio block must contain 2048..1440000 samples");
        return 0;
    }
    const OrtApi *api = s->api;
    int frames = (samples - HOP) / HOP + 1, pad = (FFT - HOP) / 2;
    int length = (frames - 1) * HOP + FFT;
    float *features = calloc((size_t)frames * MELS, sizeof(float));
    float *wave = calloc((size_t)length * 2, sizeof(float));
    float complex *spectra = calloc((size_t)samples * 2, sizeof(*spectra));
    OrtValue *mel_input = NULL, *hidden = NULL, *head[2] = {NULL, NULL};
    OrtTensorTypeAndShapeInfo *shape = NULL;
    int success = 0;
    if (!features || !wave || !spectra) {
        snprintf(s->error, sizeof(s->error), "Out of memory");
        goto done;
    }
    float complex buffer[FFT];
    float magnitudes[BINS];
    for (int t = 0; t < frames; t++) {
        for (int i = 0; i < FFT; i++)
            buffer[i] = input[reflect(t * HOP + i - pad, samples)] * s->window[i];
        yr_fft2(buffer, FFT, 0);
        for (int f = 0; f < BINS; f++)
            magnitudes[f] = cabsf(buffer[f]);
        for (int m = 0; m < MELS; m++) {
            float value = 0;
            for (int f = 0; f < BINS; f++)
                value += s->filters[m][f] * magnitudes[f];
            features[m * frames + t] = logf(fmaxf(value, 1e-5f));
        }
    }
    int64_t dims[3] = {1, MELS, frames};
    if (!checked(s, api->CreateTensorWithDataAsOrtValue(
                        s->memory, features, (size_t)frames * MELS * sizeof(float), dims, 3,
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &mel_input)))
        goto done;
    const char *in_names[] = {"mel"}, *out_names[] = {"hidden"};
    const OrtValue *inputs[] = {mel_input};
    if (!checked(s, api->Run(s->backbone, NULL, in_names, inputs, 1, out_names, 1, &hidden)))
        goto done;
    const char *head_in[] = {"hidden"}, *head_out[] = {"real", "imag"};
    const OrtValue *hidden_input[] = {hidden};
    if (!checked(s, api->Run(s->head, NULL, head_in, hidden_input, 1, head_out, 2, head)))
        goto done;
    if (!checked(s, api->GetTensorTypeAndShape(head[0], &shape)))
        goto done;
    size_t elements;
    if (!checked(s, api->GetTensorShapeElementCount(shape, &elements)))
        goto done;
    if (elements != (size_t)BINS * frames) {
        snprintf(s->error, sizeof(s->error), "Unexpected model output");
        goto done;
    }
    float *real, *imag;
    if (!checked(s, api->GetTensorMutableData(head[0], (void **)&real)) ||
        !checked(s, api->GetTensorMutableData(head[1], (void **)&imag)))
        goto done;
    for (int t = 0; t < frames; t++) {
        for (int f = 0; f < BINS; f++)
            buffer[f] = real[f * frames + t] + I * imag[f * frames + t];
        for (int f = BINS; f < FFT; f++)
            buffer[f] = conjf(buffer[FFT - f]);
        yr_fft2(buffer, FFT, 1);
        for (int i = 0; i < FFT; i++) {
            int at = t * HOP + i;
            wave[at] += crealf(buffer[i]) * s->window[i];
            wave[length + at] += s->window[i] * s->window[i];
        }
    }
    int available = length - pad * 2;
    for (int i = 0; i < samples; i++) {
        spectra[i] = i < available ? wave[i + pad] / fmaxf(wave[length + i + pad], 1e-8f) : 0;
        spectra[samples + i] = input[i];
    }
    if (!yr_fft(spectra, (size_t)samples, 0) ||
        !yr_fft(spectra + samples, (size_t)samples, 0))
        goto memory_error;
    int bins = samples / 2 + 1, cutoff = bins / 6, first = cutoff - 128, last = cutoff + 128;
    for (int i = 0; i < samples; i++) {
        int frequency = i <= samples / 2 ? i : samples - i;
        float fraction = fmaxf(0, fminf(1, (frequency - first) / (float)(last - first - 1)));
        float mask = fraction * fraction * (3 - 2 * fraction);
        spectra[i] = spectra[samples + i] + (spectra[i] - spectra[samples + i]) * mask;
    }
    if (!yr_fft(spectra, (size_t)samples, 1))
        goto memory_error;
    for (int i = 0; i < samples; i++) {
        output[i] = crealf(spectra[i]);
        if (!isfinite(output[i])) {
            snprintf(s->error, sizeof(s->error), "Invalid model output");
            goto done;
        }
    }
    success = 1;
    goto done;
memory_error:
    snprintf(s->error, sizeof(s->error), "Out of memory");
done:
    if (shape)
        api->ReleaseTensorTypeAndShapeInfo(shape);
    for (int i = 0; i < 2; i++)
        if (head[i])
            api->ReleaseValue(head[i]);
    if (hidden)
        api->ReleaseValue(hidden);
    if (mel_input)
        api->ReleaseValue(mel_input);
    free(features);
    free(wave);
    free(spectra);
    return success;
}
