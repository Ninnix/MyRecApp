#ifndef YR_FFT_H
#define YR_FFT_H
#include <complex.h>
#include <stddef.h>
void yr_fft2(float complex *x, size_t n, int inverse);
int yr_fft(float complex *x, size_t n, int inverse);
#endif
