#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WINDOW 2048
#define HOP 512
#define SEARCH 480
#define BUFFER (WINDOW + 2 * SEARCH)

static int64_t reflected(int64_t i, int64_t length) {
    if (length <= 1) return 0;
    int64_t turn = 2 * (length - 1);
    i %= turn;
    if (i < 0) i += turn;
    return i < length ? i : turn - i;
}

static double similarity(const float *source, const float *reference, const float *weights, int offset) {
    double dot = 0, a = 0, b = 0;
    for (int i = 0; i < WINDOW - HOP; i += 4) {
        if (weights[i] < .0001f) continue;
        for (int ch = 0; ch < 2; ch++) {
            double x = source[(i + offset) * 2 + ch], y = reference[i * 2 + ch] / weights[i];
            dot += x * y; a += x * x; b += y * y;
        }
    }
    if (a * b < 1e-16) return -abs(offset - SEARCH) * 1e-7;
    return dot / sqrt(a * b) - abs(offset - SEARCH) * 1e-7;
}

// Both channels share one alignment. Caching the original source preserves the
// same waveform and phase when clips are split, moved, sought, or exported.
JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_audioStretch(JNIEnv *env, jobject self,
    jstring input_path, jstring output_path, jdouble speed, jobject progress) {
    (void)self;
    if (!isfinite(speed) || speed < .1 || speed > 128) return -1;
    const char *input = (*env)->GetStringUTFChars(env, input_path, NULL);
    if (!input) return -1;
    const char *output = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!output) { (*env)->ReleaseStringUTFChars(env, input_path, input); return -1; }
    FILE *in = fopen(input, "rb"), *out = NULL;
    float *memory = NULL;
    int64_t result = -1;
    jmethodID callback = NULL;
    if (progress) {
        jclass cls = (*env)->GetObjectClass(env, progress);
        callback = (*env)->GetMethodID(env, cls, "step", "(JJ)Z");
        (*env)->DeleteLocalRef(env, cls);
        if (!callback) goto done;
    }
    if (!in || fseeko(in, 0, SEEK_END)) goto done;
    off_t bytes = ftello(in);
    if (bytes < 0 || bytes % 8) goto done;
    int64_t frames = bytes / 8, total = llround(frames / speed);
    int fd = open(output, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (fd < 0) goto done;
    out = fdopen(fd, "wb");
    if (!out) { close(fd); remove(output); goto done; }
    if (!frames) { result = 0; goto done; }
    memory = calloc(BUFFER * 4 + WINDOW * 4 + HOP * 2, sizeof(float));
    if (!memory) goto done;
    float *source = memory, *raw = source + BUFFER * 2, *ola = raw + BUFFER * 2;
    float *weights = ola + WINDOW * 2, *window = weights + WINDOW, *block = window + WINDOW;
    for (int i = 0; i < WINDOW; i++) window[i] = .5f - .5f * cosf(2 * 3.14159265358979323846f * i / WINDOW);
    int grain = 0;
    for (int64_t position = -WINDOW / 2; position < total; position += HOP, grain++) {
        if (callback && grain % 32 == 0) {
            jboolean keep = (*env)->CallBooleanMethod(env, progress, callback, (jlong)(position < 0 ? 0 : position), (jlong)total);
            if ((*env)->ExceptionCheck(env) || !keep) goto done;
        }
        int64_t predicted = llround((position + WINDOW / 2) * speed) - WINDOW / 2;
        int64_t minimum = frames, maximum = 0;
        for (int i = 0; i < BUFFER; i++) {
            int64_t at = reflected(predicted - SEARCH + i, frames);
            if (at < minimum) minimum = at;
            if (at > maximum) maximum = at;
        }
        size_t count = (size_t)(maximum - minimum + 1);
        if (count > BUFFER || fseeko(in, minimum * 8, SEEK_SET) || fread(raw, 8, count, in) != count) goto done;
        for (int i = 0; i < BUFFER; i++) {
            int64_t at = reflected(predicted - SEARCH + i, frames) - minimum;
            for (int ch = 0; ch < 2; ch++) {
                float value = raw[at * 2 + ch];
                if (!isfinite(value)) goto done;
                source[i * 2 + ch] = value;
            }
        }
        int offset = SEARCH;
        if (grain > 0) {
            double best = -2;
            for (int i = 0; i <= SEARCH * 2; i += 8) {
                double score = similarity(source, ola, weights, i);
                if (score > best) { best = score; offset = i; }
            }
            int from = offset - 7, to = offset + 7;
            if (from < 0) from = 0;
            if (to > 2 * SEARCH) to = 2 * SEARCH;
            for (int i = from; i <= to; i++) {
                double score = similarity(source, ola, weights, i);
                if (score > best) { best = score; offset = i; }
            }
        }
        for (int i = 0; i < WINDOW; i++) {
            weights[i] += window[i];
            for (int ch = 0; ch < 2; ch++) ola[i * 2 + ch] += source[(offset + i) * 2 + ch] * window[i];
        }
        if (position >= 0) {
            int amount = (int)fmin(HOP, total - position);
            for (int i = 0; i < amount; i++) for (int ch = 0; ch < 2; ch++)
                block[i * 2 + ch] = weights[i] > 1e-8f ? ola[i * 2 + ch] / weights[i] : 0;
            if (fwrite(block, 8, amount, out) != (size_t)amount) goto done;
        }
        memmove(ola, ola + HOP * 2, (WINDOW - HOP) * 2 * sizeof(float));
        memset(ola + (WINDOW - HOP) * 2, 0, HOP * 2 * sizeof(float));
        memmove(weights, weights + HOP, (WINDOW - HOP) * sizeof(float));
        memset(weights + WINDOW - HOP, 0, HOP * sizeof(float));
    }
    if (fflush(out)) goto done;
    result = total;
done:
    free(memory);
    if (in) fclose(in);
    if (out && fclose(out)) result = -1;
    if (result < 0 && out) remove(output);
    (*env)->ReleaseStringUTFChars(env, input_path, input);
    (*env)->ReleaseStringUTFChars(env, output_path, output);
    return result;
}
