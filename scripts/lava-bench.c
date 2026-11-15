#define _POSIX_C_SOURCE 200809L
#include "lava-runtime.h"
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static double now(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return value.tv_sec + value.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    if (argc != 6) {
        fprintf(stderr,
                "Usage: lava-bench runtime model-directory input.f32 output.f32 threads\n");
        return 1;
    }
    FILE *file = fopen(argv[3], "rb");
    if (!file)
        return 1;
    fseek(file, 0, SEEK_END);
    long bytes = ftell(file);
    rewind(file);
    if (bytes < 8192 || bytes > 48000 * 30 * 4 || bytes % 4) {
        fclose(file);
        return 1;
    }
    float *input = malloc((size_t)bytes), *output = malloc((size_t)bytes);
    if (!input || !output) {
        fclose(file);
        free(input);
        free(output);
        return 1;
    }
    int success = fread(input, 1, (size_t)bytes, file) == (size_t)bytes;
    fclose(file);
    char error[512];
    double load = now();
    YrLava *state = yr_lava_open(argv[1], argv[2], atoi(argv[5]), error, sizeof(error));
    load = now() - load;
    double began = now();
    if (success && state)
        success = yr_lava_process(state, input, output, (int)(bytes / 4));
    else
        success = 0;
    double elapsed = now() - began;
    if (!success)
        fprintf(stderr, "%s\n", state ? yr_lava_error(state) : error);
    else {
        file = fopen(argv[4], "wb");
        if (!file)
            success = 0;
        else {
            success = fwrite(output, 1, (size_t)bytes, file) == (size_t)bytes;
            fclose(file);
        }
        printf("{\"samples\":%ld,\"load_seconds\":%.6f,\"process_seconds\":%.6f}\n", bytes / 4,
               load, elapsed);
    }
    yr_lava_close(state);
    free(input);
    free(output);
    return success ? 0 : 1;
}
