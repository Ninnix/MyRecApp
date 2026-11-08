#ifndef YR_NEURAL_H
#define YR_NEURAL_H
#include <stddef.h>
typedef struct YrNeural YrNeural;
YrNeural *yr_neural_open(const char *library, const char *directory, int kind,
                         int threads, char *error, size_t size);
int yr_neural_process(YrNeural *s, const float *input, float *output, int samples);
const char *yr_neural_error(YrNeural *s);
void yr_neural_close(YrNeural *s);
#endif
