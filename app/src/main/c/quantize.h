#ifndef MYREC_QUANTIZE_H
#define MYREC_QUANTIZE_H
#include <stdint.h>

typedef struct {
    uint8_t colors[16][3];
    uint8_t nearest[32768];
    int count;
} YrQuantizer;

void yr_quantizer_init(YrQuantizer *q, const uint8_t *pixels, int width, int height, int stride,
                       int count, int palette, int monochrome);
#endif
