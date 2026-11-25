#ifndef MYREC_COLOR_SPACE_H
#define MYREC_COLOR_SPACE_H

#include <math.h>
#include <stdint.h>

static inline float yr_clamp(float v, float lo, float hi) { return fminf(hi, fmaxf(lo, v)); }
static inline float yr_linear(float v) { return v <= .04045f ? v / 12.92f : powf((v + .055f) / 1.055f, 2.4f); }
static inline float yr_srgb(float v) { return v <= .0031308f ? v * 12.92f : 1.055f * powf(v, 1.f / 2.4f) - .055f; }
static inline uint8_t yr_display(float v) { return (uint8_t)lroundf(yr_clamp(yr_srgb(v) * 255, 0, 255)); }
static inline float yr_luma(const float c[3]) { return .2126f * c[0] + .7152f * c[1] + .0722f * c[2]; }
static inline float yr_smooth(float lo, float hi, float v) {
    float t = yr_clamp((v - lo) / (hi - lo), 0, 1);
    return t * t * (3 - 2 * t);
}

static inline void yr_tone(float c[4]) {
    // Preserve contrast through the whole 1000-nit HLG range on an SDR display.
    // The shoulder joins 18% grey at unit slope and reaches white at the HDR peak.
    const float knee = .18f, peak = 1000.f / 203.f;
    float y = fmaxf(1e-6f, yr_luma(c)), head = fmaxf(0, y - knee);
    float mapped = fminf(1, fminf(y,knee) + head / (1 + head * (1 / (1 - knee) - 1 / (peak - knee))));
    float delta[3], fit = 1;
    for (int i = 0; i < 3; i++) {
        c[i] *= mapped / y; delta[i] = c[i] - mapped;
        if (delta[i] > 0) fit = fminf(fit, (1 - mapped) / delta[i]);
        else if (delta[i] < 0) fit = fminf(fit, -mapped / delta[i]);
    }
    for (int i = 0; i < 3; i++) c[i] = yr_clamp(mapped + delta[i] * fmaxf(0, fit), 0, 1);
}
static inline void yr_oklab(const float c[3], float out[3]) {
    float l = cbrtf(fmaxf(0, .4122214708f * c[0] + .5363325363f * c[1] + .0514459929f * c[2]));
    float m = cbrtf(fmaxf(0, .2119034982f * c[0] + .6806995451f * c[1] + .1073969566f * c[2]));
    float s = cbrtf(fmaxf(0, .0883024619f * c[0] + .2817188376f * c[1] + .6299787005f * c[2]));
    out[0] = .2104542553f * l + .793617785f * m - .0040720468f * s;
    out[1] = 1.9779984951f * l - 2.428592205f * m + .4505937099f * s;
    out[2] = .0259040371f * l + .7827717662f * m - .808675766f * s;
}

static inline void yr_unlab(const float c[3], float out[3]) {
    float l = c[0] + .3963377774f * c[1] + .2158037573f * c[2];
    float m = c[0] - .1055613458f * c[1] - .0638541728f * c[2];
    float s = c[0] - .0894841775f * c[1] - 1.291485548f * c[2];
    l = l * l * l; m = m * m * m; s = s * s * s;
    out[0] = 4.0767416621f * l - 3.3077115913f * m + .2309699292f * s;
    out[1] = -1.2684380046f * l + 2.6097574011f * m - .3413193965f * s;
    out[2] = -.0041960863f * l - .7034186147f * m + 1.707614701f * s;
}

static inline void yr_hsv(const float c[3], float out[3]) {
    float hi = fmaxf(c[0], fmaxf(c[1], c[2])), lo = fminf(c[0], fminf(c[1], c[2]));
    float d = hi - lo, h = 0;
    if (d > 1e-6f) {
        if (hi == c[0]) h = fmodf((c[1] - c[2]) / d + 6, 6);
        else if (hi == c[1]) h = (c[2] - c[0]) / d + 2;
        else h = (c[0] - c[1]) / d + 4;
    }
    out[0] = h / 6; out[1] = hi > 1e-6f ? d / hi : 0; out[2] = hi;
}

#endif
