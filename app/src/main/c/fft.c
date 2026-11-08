#include "fft.h"
#include <math.h>
#include <stdlib.h>
static const double pi = 3.14159265358979323846;

void yr_fft2(float complex *x, size_t n, int inverse) {
    for (size_t i = 1, j = 0; i < n; i++) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1)
            j ^= bit;
        j ^= bit;
        if (i < j) {
            float complex v = x[i];
            x[i] = x[j];
            x[j] = v;
        }
    }
    for (size_t size = 2; size <= n; size <<= 1) {
        double angle = (inverse ? 2 : -2) * pi / size;
        double complex step = cos(angle) + I * sin(angle);
        for (size_t start = 0; start < n; start += size) {
            double complex phase = 1;
            for (size_t j = 0; j < size / 2; j++) {
                float complex u = x[start + j];
                float complex v = (float complex)(phase * x[start + j + size / 2]);
                x[start + j] = u + v;
                x[start + j + size / 2] = u - v;
                phase *= step;
            }
        }
    }
    if (inverse)
        for (size_t i = 0; i < n; i++)
            x[i] /= (float)n;
}

int yr_fft(float complex *x, size_t n, int inverse) {
    if (!(n & (n - 1))) {
        yr_fft2(x, n, inverse);
        return 1;
    }
    size_t count = 1;
    while (count < n * 2 - 1)
        count <<= 1;
    float complex *a = calloc(count * 2, sizeof(*a));
    if (!a)
        return 0;
    float complex *b = a + count;
    double sign = inverse ? 1 : -1;
    for (size_t i = 0; i < n; i++) {
        double angle = sign * pi * fmod((double)i * i, 2.0 * n) / n;
        float complex phase = (float)cos(angle) + I * (float)sin(angle);
        a[i] = x[i] * phase;
        b[i] = conjf(phase);
        if (i)
            b[count - i] = b[i];
    }
    yr_fft2(a, count, 0);
    yr_fft2(b, count, 0);
    for (size_t i = 0; i < count; i++)
        a[i] *= b[i];
    yr_fft2(a, count, 1);
    for (size_t i = 0; i < n; i++) {
        double angle = sign * pi * fmod((double)i * i, 2.0 * n) / n;
        x[i] = a[i] * ((float)cos(angle) + I * (float)sin(angle));
        if (inverse)
            x[i] /= (float)n;
    }
    free(a);
    return 1;
}
