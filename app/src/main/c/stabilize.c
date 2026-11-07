#include "stabilize.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    int w, h;
    float *pixels;
} Plane;
typedef struct {
    float x, y, u, v, score;
} Feature;

static float sample(const Plane *p, float x, float y) {
    int ix = (int)x, iy = (int)y;
    if (ix < 0 || iy < 0 || ix >= p->w - 1 || iy >= p->h - 1)
        return 0;
    float fx = x - ix, fy = y - iy;
    const float *r = p->pixels + iy * p->w + ix;
    return (r[0] * (1 - fx) + r[1] * fx) * (1 - fy) + (r[p->w] * (1 - fx) + r[p->w + 1] * fx) * fy;
}
static int pyramid(Plane p[3], const uint8_t *gray, int w, int h) {
    for (int level = 0; level < 3; level++) {
        p[level].w = w >> level;
        p[level].h = h >> level;
        p[level].pixels = calloc((size_t)p[level].w * p[level].h, sizeof(float));
        if (!p[level].pixels)
            return 0;
        for (int y = 0; y < p[level].h; y++)
            for (int x = 0; x < p[level].w; x++) {
                if (!level)
                    p[0].pixels[y * w + x] = gray[y * w + x];
                else {
                    Plane *a = &p[level - 1];
                    int i = y * 2 * a->w + x * 2;
                    p[level].pixels[y * p[level].w + x] =
                        (a->pixels[i] + a->pixels[i + 1] + a->pixels[i + a->w] +
                         a->pixels[i + a->w + 1]) *
                        0.25f;
                }
            }
    }
    return 1;
}
static int track(const Plane a[3], const Plane b[3], float x, float y, float *dx, float *dy) {
    float u = 0, v = 0;
    for (int level = 2; level >= 0; level--) {
        if (level < 2) {
            u *= 2;
            v *= 2;
        }
        float px = x / (1 << level), py = y / (1 << level);
        if (px < 5 || py < 5 || px >= a[level].w - 5 || py >= a[level].h - 5)
            return 0;
        float gx[81], gy[81], values[81], xx = 0, xy = 0, yy = 0;
        int n = 0;
        for (int j = -4; j <= 4; j++)
            for (int i = -4; i <= 4; i++) {
                float fx = px + i, fy = py + j;
                gx[n] = (sample(&a[level], fx + 1, fy) - sample(&a[level], fx - 1, fy)) * 0.5f;
                gy[n] = (sample(&a[level], fx, fy + 1) - sample(&a[level], fx, fy - 1)) * 0.5f;
                values[n] = sample(&a[level], fx, fy);
                xx += gx[n] * gx[n];
                xy += gx[n] * gy[n];
                yy += gy[n] * gy[n];
                n++;
            }
        float det = xx * yy - xy * xy;
        if (det < 1e-3f || det / (xx + yy + 1e-6f) < 2)
            return 0;
        for (int iteration = 0; iteration < 24; iteration++) {
            if (px + u < 5 || py + v < 5 || px + u >= b[level].w - 5 || py + v >= b[level].h - 5)
                return 0;
            float ex = 0, ey = 0;
            int n = 0;
            for (int j = -4; j <= 4; j++)
                for (int i = -4; i <= 4; i++) {
                    float error = values[n] - sample(&b[level], px + i + u, py + j + v);
                    ex += gx[n] * error;
                    ey += gy[n] * error;
                    n++;
                }
            float sx = (yy * ex - xy * ey) / det, sy = (xx * ey - xy * ex) / det;
            if (!isfinite(sx) || !isfinite(sy) || fabsf(sx) > 5 || fabsf(sy) > 5)
                return 0;
            u += sx;
            v += sy;
            if (sx * sx + sy * sy < 0.0004f)
                break;
        }
    }
    if (fabsf(u) > 48 || fabsf(v) > 48)
        return 0;
    *dx = u;
    *dy = v;
    return 1;
}
static int detect(const Plane *p, Feature *out) {
    int n = 0;
    for (int cy = 20; cy < p->h - 20; cy += 16)
        for (int cx = 20; cx < p->w - 20; cx += 16) {
            Feature best = {.score = 20};
            for (int y = cy; y < cy + 12 && y < p->h - 20; y += 2)
                for (int x = cx; x < cx + 12 && x < p->w - 20; x += 2) {
                    float xx = 0, xy = 0, yy = 0;
                    for (int j = -2; j <= 2; j++)
                        for (int i = -2; i <= 2; i++) {
                            int k = (y + j) * p->w + x + i;
                            float gx = (p->pixels[k + 1] - p->pixels[k - 1]) * 0.5f;
                            float gy = (p->pixels[k + p->w] - p->pixels[k - p->w]) * 0.5f;
                            xx += gx * gx;
                            xy += gx * gy;
                            yy += gy * gy;
                        }
                    float score = (xx * yy - xy * xy) / (xx + yy + 1e-6f);
                    if (score > best.score)
                        best = (Feature){.x = (float)x, .y = (float)y, .score = score};
                }
            if (best.score > 20)
                out[n++] = best;
            if (n == 512)
                return n;
        }
    return n;
}
static float error(const Feature *f, const float t[4]) {
    float dx = t[0] * f->x - t[1] * f->y + t[2] - f->u;
    float dy = t[1] * f->x + t[0] * f->y + t[3] - f->v;
    return dx * dx + dy * dy;
}
int yr_similarity(const uint8_t *reference, const uint8_t *current, int width, int height,
                  float result[5]) {
    result[0] = 1;
    result[1] = result[2] = result[3] = result[4] = 0;
    if (width < 64 || height < 64)
        return 0;
    Plane a[3] = {0}, b[3] = {0};
    int ok = 0;
    Feature features[512], matches[512];
    if (!pyramid(a, reference, width, height) || !pyramid(b, current, width, height))
        goto done;
    int count = detect(&a[0], features), n = 0;
    for (int i = 0; i < count; i++) {
        float dx, dy, rx, ry;
        Feature f = features[i];
        if (!track(a, b, f.x, f.y, &dx, &dy))
            continue;
        f.u = f.x + dx;
        f.v = f.y + dy;
        if (!track(b, a, f.u, f.v, &rx, &ry) ||
            (dx + rx) * (dx + rx) + (dy + ry) * (dy + ry) > 1.0f)
            continue;
        matches[n++] = f;
    }
    if (n < 6)
        goto done;
    float best[4] = {1, 0, 0, 0};
    int best_count = 0;
    uint32_t seed = 42;
    for (int trial = 0; trial < 160; trial++) {
        seed = seed * 1664525u + 1013904223u;
        int i = (int)(seed % (unsigned)n);
        seed = seed * 1664525u + 1013904223u;
        int j = (int)(seed % (unsigned)n);
        if (i == j)
            continue;
        float px = matches[j].x - matches[i].x, py = matches[j].y - matches[i].y;
        float qx = matches[j].u - matches[i].u, qy = matches[j].v - matches[i].v;
        float den = px * px + py * py;
        if (den < 400)
            continue;
        float t[4] = {(px * qx + py * qy) / den, (px * qy - py * qx) / den, 0, 0};
        float scale = t[0] * t[0] + t[1] * t[1];
        if (scale < 0.64f || scale > 1.5625f || fabsf(t[1]) > 0.26f)
            continue;
        t[2] = matches[i].u - t[0] * matches[i].x + t[1] * matches[i].y;
        t[3] = matches[i].v - t[1] * matches[i].x - t[0] * matches[i].y;
        int inliers = 0;
        for (int k = 0; k < n; k++)
            if (error(&matches[k], t) < 2.25f)
                inliers++;
        if (inliers > best_count) {
            best_count = inliers;
            memcpy(best, t, sizeof(best));
        }
    }
    if (best_count < 6)
        goto done;
    float px = 0, py = 0, qx = 0, qy = 0;
    for (int k = 0; k < n; k++)
        if (error(&matches[k], best) < 2.25f) {
            px += matches[k].x;
            py += matches[k].y;
            qx += matches[k].u;
            qy += matches[k].v;
        }
    px /= best_count;
    py /= best_count;
    qx /= best_count;
    qy /= best_count;
    float aa = 0, bb = 0, den = 0;
    for (int k = 0; k < n; k++)
        if (error(&matches[k], best) < 2.25f) {
            float x = matches[k].x - px, y = matches[k].y - py, u = matches[k].u - qx,
                  v = matches[k].v - qy;
            aa += x * u + y * v;
            bb += x * v - y * u;
            den += x * x + y * y;
        }
    if (den < 1)
        goto done;
    result[0] = aa / den;
    result[1] = bb / den;
    result[2] = qx - result[0] * px + result[1] * py;
    result[3] = qy - result[1] * px - result[0] * py;
    result[4] = (float)best_count;
    ok = 1;
done:
    for (int i = 0; i < 3; i++) {
        free(a[i].pixels);
        free(b[i].pixels);
    }
    return ok;
}
