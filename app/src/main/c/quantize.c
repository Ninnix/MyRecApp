#include "quantize.h"
#include <math.h>
#include <stddef.h>

static float distance(const float *a, const float *b) {
    float r = a[0] - b[0], g = a[1] - b[1], blue = a[2] - b[2];
    return r * r * .299f + g * g * .587f + blue * blue * .114f;
}

static int nearest(const float *sample, float centers[16][3], int count) {
    int best = 0;
    float cost = distance(sample, centers[0]);
    for (int i = 1; i < count; i++) {
        float next = distance(sample, centers[i]);
        if (next < cost) {
            best = i;
            cost = next;
        }
    }
    return best;
}

void yr_quantizer_init(YrQuantizer *q, const uint8_t *pixels, int width, int height, int stride,
                       int count, int palette, int monochrome) {
    static const float pop[3][5][3] = {
        {{26, 19, 28}, {116, 34, 60}, {192, 102, 73}, {204, 177, 86}, {238, 235, 194}},
        {{18, 24, 38}, {27, 85, 109}, {53, 145, 151}, {227, 151, 82}, {248, 234, 186}},
        {{27, 18, 45}, {90, 48, 119}, {207, 68, 123}, {66, 184, 157}, {250, 225, 159}}};
    float centers[16][3] = {{0}};
    count = count < 2 ? 2 : count > 16 ? 16 : count;
    q->count = count;
    if (palette > 0 && palette <= 3) {
        for (int i = 0; i < count; i++) {
            float position = i * 4.f / (count - 1);
            int a = (int)position, b = a < 4 ? a + 1 : a;
            for (int c = 0; c < 3; c++)
                centers[i][c] =
                    pop[palette - 1][a][c] +
                    (pop[palette - 1][b][c] - pop[palette - 1][a][c]) * (position - a);
        }
    } else {
        float samples[4096][3];
        size_t total = (size_t)width * height;
        size_t step = total / 4096 + 1;
        int n = 0;
        for (size_t at = 0; at < total && n < 4096; at += step) {
            const uint8_t *p = pixels + at / width * stride + at % width * 4;
            for (int c = 0; c < 3; c++)
                samples[n][c] = p[c];
            if (monochrome) {
                float value = p[0] * .299f + p[1] * .587f + p[2] * .114f;
                samples[n][0] = samples[n][1] = samples[n][2] = value;
            }
            n++;
        }
        if (n == 0)
            n = 1, samples[0][0] = samples[0][1] = samples[0][2] = 0;
        int darkest = 0;
        for (int i = 1; i < n; i++) {
            float a = samples[i][0] + samples[i][1] + samples[i][2];
            float b = samples[darkest][0] + samples[darkest][1] + samples[darkest][2];
            if (a < b)
                darkest = i;
        }
        for (int c = 0; c < 3; c++)
            centers[0][c] = samples[darkest][c];
        for (int k = 1; k < count; k++) {
            int farthest = 0;
            float maximum = -1;
            for (int i = 0; i < n; i++) {
                float cost = distance(samples[i], centers[nearest(samples[i], centers, k)]);
                if (cost > maximum)
                    maximum = cost, farthest = i;
            }
            for (int c = 0; c < 3; c++)
                centers[k][c] = samples[farthest][c];
        }
        for (int pass = 0; pass < 8; pass++) {
            float sum[16][3] = {{0}};
            int population[16] = {0};
            for (int i = 0; i < n; i++) {
                int index = nearest(samples[i], centers, count);
                population[index]++;
                for (int c = 0; c < 3; c++)
                    sum[index][c] += samples[i][c];
            }
            for (int k = 0; k < count; k++)
                if (population[k])
                    for (int c = 0; c < 3; c++)
                        centers[k][c] = sum[k][c] / population[k];
        }
    }
    if (monochrome)
        for (int i = 0; i < count; i++) {
            float value = centers[i][0] * .299f + centers[i][1] * .587f + centers[i][2] * .114f;
            centers[i][0] = centers[i][1] = centers[i][2] = value;
        }
    for (int i = 0; i < count; i++)
        for (int c = 0; c < 3; c++)
            q->colors[i][c] = (uint8_t)roundf(centers[i][c]);
    /* A small RGB lookup keeps full-resolution rendering independent of palette
     * size. */
    for (int i = 0; i < 32768; i++) {
        float sample[3] = {(i >> 10) * 8.f + 4, ((i >> 5) & 31) * 8.f + 4, (i & 31) * 8.f + 4};
        q->nearest[i] = (uint8_t)nearest(sample, centers, count);
    }
}
