#include "inference.h"
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

int yr_inference_check(YrInference *s, OrtStatus *status) {
    if (!status) return 1;
    snprintf(s->error, sizeof(s->error), "%s", s->api->GetErrorMessage(status));
    s->api->ReleaseStatus(status);
    return 0;
}

void yr_inference_close(YrInference *s) {
    if (s->api) {
        for (int i = 0; i < 3; i++) if (s->sessions[i]) s->api->ReleaseSession(s->sessions[i]);
        if (s->memory) s->api->ReleaseMemoryInfo(s->memory);
        if (s->env) s->api->ReleaseEnv(s->env);
    }
    if (s->library) dlclose(s->library);
}

int yr_inference_open(YrInference *s, const char *library, const char *directory,
                      const char *const *models, int count, int threads) {
    memset(s, 0, sizeof(*s));
    OrtSessionOptions *options = NULL;
    s->library = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!s->library) { snprintf(s->error, sizeof(s->error), "%s", dlerror()); return 0; }
    const OrtApiBase *(*get_api)(void) = (const OrtApiBase *(*)(void))dlsym(s->library, "OrtGetApiBase");
    if (!get_api || !(s->api = get_api()->GetApi(20))) {
        snprintf(s->error, sizeof(s->error), "Unsupported inference runtime");
        return 0;
    }
    const OrtApi *api = s->api;
    if (!yr_inference_check(s, api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "yourec-audio", &s->env)) ||
        !yr_inference_check(s, api->CreateSessionOptions(&options)) ||
        !yr_inference_check(s, api->SetIntraOpNumThreads(options, threads)) ||
        !yr_inference_check(s, api->SetInterOpNumThreads(options, 1)) ||
        !yr_inference_check(s, api->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0")) ||
        !yr_inference_check(s, api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL)) ||
        !yr_inference_check(s, api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &s->memory))) goto done;
    for (int i = 0; i < count; i++) {
        char path[4096];
        snprintf(path, sizeof(path), "%s/%s", directory, models[i]);
        if (!yr_inference_check(s, api->CreateSession(s->env, path, options, &s->sessions[i]))) goto done;
    }
done:
    if (options) api->ReleaseSessionOptions(options);
    return s->error[0] == 0;
}

OrtValue *yr_inference_tensor(YrInference *s, float *data, const int64_t *dims, size_t rank) {
    size_t elements = 1;
    for (size_t i = 0; i < rank; i++) elements *= (size_t)dims[i];
    OrtValue *value = NULL;
    yr_inference_check(s, s->api->CreateTensorWithDataAsOrtValue(s->memory, data,
        elements * sizeof(float), dims, rank, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &value));
    return value;
}

float *yr_inference_data(YrInference *s, OrtValue *value, size_t *count) {
    float *data = NULL;
    if (!value) return NULL;
    if (count) {
        OrtTensorTypeAndShapeInfo *shape = NULL;
        if (!yr_inference_check(s, s->api->GetTensorTypeAndShape(value, &shape))) return NULL;
        int ok = yr_inference_check(s, s->api->GetTensorShapeElementCount(shape, count));
        s->api->ReleaseTensorTypeAndShapeInfo(shape);
        if (!ok) return NULL;
    }
    if (!yr_inference_check(s, s->api->GetTensorMutableData(value, (void **)&data))) return NULL;
    return data;
}
