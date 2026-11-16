#ifndef YR_NEURAL_H
#define YR_NEURAL_H
#include <stddef.h>
typedef struct YrNeural YrNeural;
YrNeural *yr_neural_open(const char *library, const char *directory, int kind,
                         int threads, char *error, size_t size);
int yr_neural_process(YrNeural *s, const float *input, float *output, int samples);
int yr_neural_hop(YrNeural *s,const float *input,float *output);
int yr_neural_flush(YrNeural *s,float *output);
size_t yr_neural_state(YrNeural *s,void *data,size_t size,int restore);
const char *yr_neural_error(YrNeural *s);
void yr_neural_close(YrNeural *s);
#endif
