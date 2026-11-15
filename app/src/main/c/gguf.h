#ifndef MYREC_GGUF_H
#define MYREC_GGUF_H
#include <stddef.h>
#include <stdint.h>

typedef struct {
    char name[128];
    uint64_t shape[4], offset;
    uint32_t dims, type;
    const unsigned char *data;
    size_t bytes;
} gguf_tensor;

typedef struct {
    void *mapping;
    size_t size;
    gguf_tensor tensors[1024];
    int count;
} gguf_file;

gguf_file *myrec_gguf_open(const char *path);
void myrec_gguf_close(gguf_file *file);
const gguf_tensor *myrec_gguf_find(const gguf_file *file, const char *name);
void myrec_gguf_row(const gguf_tensor *tensor, size_t row, float *out);
float *myrec_gguf_floats(const gguf_tensor *tensor);
#endif
