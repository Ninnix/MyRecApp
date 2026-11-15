/* Qwen GGUF reader. Q4_K packing follows the ggml format (MIT; bundled notice). */
#include "gguf.h"
#include <fcntl.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct { const unsigned char *p, *end; int error; } reader;

static uint64_t number(reader *r, size_t bytes) {
    if ((size_t)(r->end - r->p) < bytes) { r->error = 1; return 0; }
    uint64_t n = 0;
    for (size_t i = 0; i < bytes; i++) n |= (uint64_t)*r->p++ << (8 * i);
    return n;
}

static void string(reader *r, char *out, size_t capacity) {
    if (out && capacity) out[0] = 0;
    uint64_t n = number(r, 8);
    if (r->error || n > (uint64_t)(r->end - r->p)) { r->error = 1; return; }
    if (out) {
        if (n >= capacity) { r->error = 1; return; }
        memcpy(out, r->p, (size_t)n); out[n] = 0;
    }
    r->p += (size_t)n;
}

static void skip(reader *r, unsigned type) {
    static const unsigned sizes[] = {1,1,2,2,4,4,4,1,0,0,8,8,8};
    if (type == 8) { string(r, NULL, 0); return; }
    if (type == 9) {
        unsigned element = (unsigned)number(r, 4);
        uint64_t count = number(r, 8);
        if (element == 9 || element > 12 || count > 4000000) { r->error = 1; return; }
        for (uint64_t i = 0; i < count && !r->error; i++) skip(r, element);
    } else if (type <= 12) number(r, sizes[type]);
    else r->error = 1;
}

gguf_file *myrec_gguf_open(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;
    struct stat st;
    if (fstat(fd, &st) || st.st_size < 24 || (uint64_t)st.st_size > SIZE_MAX) { close(fd); return NULL; }
    gguf_file *f = calloc(1, sizeof(*f));
    if (!f) { close(fd); return NULL; }
    f->size = (size_t)st.st_size;
    f->mapping = mmap(NULL, f->size, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (f->mapping == MAP_FAILED) { free(f); return NULL; }
    reader r = {f->mapping, (const unsigned char *)f->mapping + f->size, 0};
    if (number(&r, 4) != 0x46554747 || number(&r, 4) != 3) goto fail;
    uint64_t count = number(&r, 8), keys = number(&r, 8);
    if (count > 1024 || keys > 10000) goto fail;
    uint64_t alignment = 32;
    int architecture = 0;
    for (uint64_t i = 0; i < keys && !r.error; i++) {
        char key[256]; string(&r, key, sizeof(key));
        unsigned type = (unsigned)number(&r, 4);
        if (!strcmp(key, "general.alignment") && type == 4) alignment = number(&r, 4);
        else if (!strcmp(key, "general.architecture") && type == 8) {
            char value[64]; string(&r, value, sizeof(value));
            architecture = !r.error && !strcmp(value, "qwen3asr");
        } else skip(&r, type);
    }
    if (!architecture || !alignment || alignment > 4096 || (alignment & (alignment - 1))) goto fail;
    f->count = (int)count;
    for (int i = 0; i < f->count && !r.error; i++) {
        gguf_tensor *t = &f->tensors[i];
        string(&r, t->name, sizeof(t->name));
        t->dims = (uint32_t)number(&r, 4);
        if (t->dims < 1 || t->dims > 4) goto fail;
        uint64_t elements = 1;
        for (unsigned d = 0; d < t->dims; d++) {
            t->shape[d] = number(&r, 8);
            if (!t->shape[d] || t->shape[d] > 200000 || elements > SIZE_MAX / t->shape[d]) goto fail;
            elements *= t->shape[d];
        }
        t->type = (uint32_t)number(&r, 4); t->offset = number(&r, 8);
        size_t block, bytes;
        switch (t->type) {
        case 0: block = 1; bytes = 4; break;
        case 1: block = 1; bytes = 2; break;
        case 8: block = 32; bytes = 34; break;
        case 12: block = 256; bytes = 144; break;
        default: goto fail;
        }
        if (t->shape[0] % block || elements / block > SIZE_MAX / bytes) goto fail;
        t->bytes = (size_t)(elements / block) * bytes;
        for (int j = 0; j < i; j++) if (!strcmp(t->name, f->tensors[j].name)) goto fail;
    }
    if (r.error) goto fail;
    size_t start = (size_t)(r.p - (const unsigned char *)f->mapping);
    if (start > SIZE_MAX - alignment) goto fail;
    start = (start + alignment - 1) & ~(alignment - 1);
    if (start > f->size) goto fail;
    for (int i = 0; i < f->count; i++) {
        gguf_tensor *t = &f->tensors[i];
        if (t->offset % alignment || t->offset > f->size - start || t->bytes > f->size - start - t->offset) goto fail;
        t->data = (const unsigned char *)f->mapping + start + t->offset;
    }
    return f;
fail:
    myrec_gguf_close(f);
    return NULL;
}

void myrec_gguf_close(gguf_file *f) {
    if (f) { munmap(f->mapping, f->size); free(f); }
}

const gguf_tensor *myrec_gguf_find(const gguf_file *f, const char *name) {
    for (int i = 0; i < f->count; i++) if (!strcmp(name, f->tensors[i].name)) return &f->tensors[i];
    return NULL;
}

static float half(const unsigned char *p) {
    unsigned bits = p[0] | (unsigned)p[1] << 8;
    int exponent = (bits >> 10) & 31;
    if (!exponent) {
        float value = ldexpf((float)(bits & 1023), -24);
        return bits & 32768 ? -value : value;
    }
    uint32_t raw = (uint32_t)(bits & 32768) << 16 | (uint32_t)(exponent == 31 ? 255 : exponent + 112) << 23 |
        (uint32_t)(bits & 1023) << 13;
    float value;
    memcpy(&value, &raw, sizeof(value));
    return value;
}

static unsigned scale(const unsigned char *s, unsigned j) {
    return j < 4 ? s[j] & 63 : (s[j + 4] & 15) | ((s[j - 4] >> 6) << 4);
}

static unsigned minimum(const unsigned char *s, unsigned j) {
    return j < 4 ? s[j + 4] & 63 : (s[j + 4] >> 4) | ((s[j] >> 6) << 4);
}

void myrec_gguf_row(const gguf_tensor *t, size_t row, float *out) {
    size_t width = (size_t)t->shape[0];
    if (t->type == 0) { memcpy(out, t->data + row * width * 4, width * 4); return; }
    if (t->type == 1) {
        const unsigned char *p = t->data + row * width * 2;
        for (size_t i = 0; i < width; i++) out[i] = half(p + i * 2);
    } else if (t->type == 8) {
        const unsigned char *p = t->data + row * (width / 32) * 34;
        for (size_t i = 0; i < width; i += 32, p += 34) {
            float d = half(p);
            for (unsigned j = 0; j < 32; j++) out[i + j] = d * (int8_t)p[2 + j];
        }
    } else if (t->type == 12) {
        const unsigned char *p = t->data + row * (width / 256) * 144;
        for (size_t i = 0; i < width; i += 256, p += 144) {
            float d = half(p), m = half(p + 2);
            for (unsigned j = 0; j < 8; j++) {
                float ds = d * scale(p + 4, j), ms = m * minimum(p + 4, j);
                const unsigned char *q = p + 16 + (j / 2) * 32;
                for (unsigned k = 0; k < 32; k++) out[i + j * 32 + k] = ds * ((q[k] >> ((j & 1) * 4)) & 15) - ms;
            }
        }
    }
}

float *myrec_gguf_floats(const gguf_tensor *t) {
    if (!t) return NULL;
    size_t rows = 1;
    for (unsigned d = 1; d < t->dims; d++) rows *= (size_t)t->shape[d];
    if (rows > SIZE_MAX / t->shape[0] / sizeof(float)) return NULL;
    float *data = malloc(rows * (size_t)t->shape[0] * sizeof(float));
    if (data) for (size_t r = 0; r < rows; r++) myrec_gguf_row(t, r, data + r * t->shape[0]);
    return data;
}
