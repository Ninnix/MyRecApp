#ifndef MYREC_GRAIN_H
#define MYREC_GRAIN_H
#include "pixels.h"
#if defined(__aarch64__)
#include <arm_neon.h>
static inline uint32x4_t yr_hash4(uint32x4_t n) {
    n = veorq_u32(n,vshrq_n_u32(n,16)); n = vmulq_n_u32(n,0x7feb352d);
    n = veorq_u32(n,vshrq_n_u32(n,15)); n = vmulq_n_u32(n,0x846ca68b);
    return veorq_u32(n,vshrq_n_u32(n,16));
}
static inline float32x4_t yr_grain_random4(uint32x4_t point, uint32_t seed) {
    uint32x4_t bits = vshrq_n_u32(yr_hash4(veorq_u32(point,vdupq_n_u32(seed))),8);
    return vsubq_f32(vmulq_n_f32(vcvtq_f32_u32(bits),2.f/16777216),vdupq_n_f32(1));
}
#endif

static inline uint32_t yr_hash(uint32_t n) {
    n ^= n >> 16; n *= 0x7feb352d; n ^= n >> 15; n *= 0x846ca68b; n ^= n >> 16;
    return n;
}
static inline float yr_random(uint32_t x, uint32_t y, uint32_t z) {
    return (float)(yr_hash(x ^ yr_hash(y + 0x9e3779b9) ^ yr_hash(z + 0x85ebca6b)) >> 8) / 16777216;
}
typedef struct { uint32_t seed[4]; } YrGrainClock;
static inline YrGrainClock yr_grain_clock(uint32_t frame) {
    YrGrainClock clock;
    const uint32_t offsets[] = {13,29,59,101};
    for (int k = 0; k < 4; k++) clock.seed[k] = yr_hash(frame+offsets[k]+0x85ebca6b);
    return clock;
}
static inline float yr_grain_random(uint32_t x, uint32_t row, uint32_t seed) {
    return (float)(yr_hash(x^row^seed)>>8)/16777216*2-1;
}
static inline float yr_noise(float x, float y, uint32_t frame, uint32_t seed) {
    int ix = (int)floorf(x), iy = (int)floorf(y);
    float dx = x-ix, dy = y-iy;
    dx = dx*dx*(3-2*dx); dy = dy*dy*(3-2*dy);
    float a = yr_random(ix,iy,frame+seed), b = yr_random(ix+1,iy,frame+seed);
    float c = yr_random(ix,iy+1,frame+seed), d = yr_random(ix+1,iy+1,frame+seed);
    return ((1-dy)*(a+(b-a)*dx)+dy*(c+(d-c)*dx))*2-1;
}
static inline void yr_grain(float c[3], float x, float y, const YrGrainClock *clock, float strength, int mono) {
    if (strength <= 0) { for (int k = 0; k < 3; k++) c[k] = yr_clamp(c[k],0,1); return; }
    float l = yr_clamp(yr_luma(c),0,1), a = strength*.24f*powf(fmaxf(0,4*l*(1-l)),.7f);
    if (a == 0) { for (int k = 0; k < 3; k++) c[k] = yr_clamp(c[k],0,1); return; }
    uint32_t ix = (uint32_t)x, iy = (uint32_t)y;
    uint32_t rows[] = {yr_hash(iy+0x9e3779b9),yr_hash(iy+1+0x9e3779b9)};
    float dx = x-ix, dy = y-iy, value[3][4];
    dx = dx*dx*(3-2*dx); dy = dy*dy*(3-2*dy);
    /* Share row/frame hashes and the correlated base across the RGB lattice. */
#if defined(__aarch64__)
    const uint32_t points[] = {ix^rows[0],(ix+1)^rows[0],ix^rows[1],(ix+1)^rows[1]};
    uint32x4_t point = vld1q_u32(points);
    float32x4_t base = yr_grain_random4(point,clock->seed[0]);
    for (int k = 0; k < (mono?1:3); k++) {
        float32x4_t result = mono?base:vaddq_f32(vmulq_n_f32(base,.7f),vmulq_n_f32(yr_grain_random4(point,clock->seed[k+1]),.3f));
        vst1q_f32(value[k],vcvt_f32_f16(vcvt_f16_f32(result)));
    }
#else
    for (int j = 0; j < 4; j++) {
        uint32_t xx = ix+(j&1), row = rows[j/2];
        float base = yr_grain_random(xx,row,clock->seed[0]);
        for (int k = 0; k < (mono?1:3); k++) value[k][j] = yr_float(yr_half(mono?base:
            base*.7f+yr_grain_random(xx,row,clock->seed[k+1])*.3f));
    }
#endif
    float noise[3];
    for (int k = 0; k < (mono?1:3); k++) noise[k] = yr_float(yr_half(
        (1-dy)*(value[k][0]+(value[k][1]-value[k][0])*dx)+dy*(value[k][2]+(value[k][3]-value[k][2])*dx)));
    if (mono) {
        float gain = expf(noise[0]*a);
        for (int k = 0; k < 3; k++) c[k] = yr_clamp(c[k]*gain,0,1);
    } else for (int k = 0; k < 3; k++) c[k] = yr_clamp(c[k]*expf(noise[k]*a),0,1);
}
#endif
