#ifndef YR_LAVA_RUNTIME_H
#define YR_LAVA_RUNTIME_H
#include <stddef.h>

typedef struct YrLava YrLava;
YrLava *yr_lava_open(const char *library, const char *directory, int threads, char *error,
                     size_t size);
int yr_lava_process(YrLava *state, const float *input, float *output, int samples);
const char *yr_lava_error(const YrLava *state);
void yr_lava_close(YrLava *state);
#endif
