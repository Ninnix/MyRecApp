#ifndef YR_INFERENCE_H
#define YR_INFERENCE_H
#include <onnxruntime_c_api.h>
#include <stddef.h>
typedef struct {
    void *library;
    const OrtApi *api;
    OrtEnv *env;
    OrtSession *sessions[3];
    OrtMemoryInfo *memory;
    char error[512];
} YrInference;
int yr_inference_open(YrInference *s, const char *library, const char *directory,
                      const char *const *models, int count, int threads);
void yr_inference_close(YrInference *s);
int yr_inference_check(YrInference *s, OrtStatus *status);
OrtValue *yr_inference_tensor(YrInference *s, float *data, const int64_t *dims, size_t rank);
float *yr_inference_data(YrInference *s, OrtValue *value, size_t *count);
#endif
