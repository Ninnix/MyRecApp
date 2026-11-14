#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>

static int gcd(int a, int b) { while (b) { int r = a % b; a = b; b = r; } return a; }
static int64_t reflect(int64_t i, int64_t n) {
    if (n == 1) return 0;
    int64_t period = 2 * (n - 1); i = (i % period + period) % period;
    return i < n ? i : period - i;
}
JNIEXPORT jlong JNICALL Java_org_myrec_app_Native_audioResample(JNIEnv *env, jobject self,
    jstring input_path, jstring output_path, jint from, jint to, jlong expected, jint channels, jobject progress) {
    (void)self;
    if ((channels != 1 && channels != 2) || !((from == 48000 && (to == 44100 || to == 16000)) || (from == 44100 && to == 48000))) return -1;
    int frame_bytes = channels * (int)sizeof(float);
    const char *input = (*env)->GetStringUTFChars(env, input_path, NULL);
    const char *output = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!input || !output) { if (input) (*env)->ReleaseStringUTFChars(env, input_path, input); return -1; }
    FILE *source = fopen(input, "rb"), *target = NULL;
    float *weights = NULL, *samples = NULL;
    int created = 0, ok = 0; int64_t frames = 0;
    if (!source || fseek(source, 0, SEEK_END)) goto done;
    int64_t bytes = ftell(source), length = bytes / frame_bytes;
    if (length <= 0 || bytes % frame_bytes || length > (int64_t)from * 604800) goto done;
    frames = expected > 0 ? expected : llround((double)length * to / from);
    if (frames <= 0 || llabs(frames - llround((double)length * to / from)) > 2) goto done;
    int descriptor = open(output, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (descriptor < 0) goto done;
    created = 1; target = fdopen(descriptor, "wb");
    if (!target) { close(descriptor); goto done; }
    int divisor = gcd(from, to), phases = to / divisor;
    weights = malloc((size_t)phases * 64 * sizeof(float));
    int capacity = (int)ceil(1024.0 * from / to) + 128;
    samples = malloc((size_t)capacity * channels * sizeof(float));
    if (!weights || !samples) goto done;
    const double pi = 3.14159265358979323846, cutoff = fmin(1.0, (double)to / from) * .95;
    for (int phase = 0; phase < phases; phase++) {
        double total = 0, frac = (double)phase / phases;
        for (int j = -31; j <= 32; j++) {
            double d = frac - j, x = pi * d * cutoff;
            float w = fabs(d) >= 32 ? 0 : (float)((fabs(x) < 1e-12 ? 1 : sin(x) / x) * (.5 + .5 * cos(pi * d / 32)));
            weights[phase * 64 + j + 31] = w; total += w;
        }
        for (int j = 0; j < 64; j++) weights[phase * 64 + j] /= (float)total;
    }
    jmethodID step = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, progress), "step", "(JJ)Z");
    if (!step) goto done;
    for (int64_t at = 0; at < frames; at += 1024) {
        if (!(*env)->CallBooleanMethod(env, progress, step, (jlong)at, (jlong)frames) || (*env)->ExceptionCheck(env)) goto done;
        int n = (int)(frames - at < 1024 ? frames - at : 1024);
        int64_t low = length - 1, high = 0;
        for (int i = 0; i < n; i++) {
            int64_t center = (at + i) * from / to;
            for (int j = -31; j <= 32; j++) { int64_t p = reflect(center + j, length); if (p < low) low = p; if (p > high) high = p; }
        }
        if (high - low + 1 > capacity || fseek(source, low * frame_bytes, SEEK_SET) || fread(samples, frame_bytes, high - low + 1, source) != (size_t)(high - low + 1)) goto done;
        for (int64_t i = 0; i < (high - low + 1) * channels; i++) if (!isfinite(samples[i])) goto done;
        float result[2048];
        for (int i = 0; i < n; i++) {
            int64_t tick = (at + i) * from, center = tick / to;
            int phase = (int)((tick % to) / divisor);
            double sum[2] = {0};
            for (int j = -31; j <= 32; j++) {
                int64_t index = (reflect(center + j, length) - low) * channels;
                for (int ch = 0; ch < channels; ch++) sum[ch] += samples[index + ch] * weights[phase * 64 + j + 31];
            }
            for (int ch = 0; ch < channels; ch++) result[i * channels + ch] = (float)sum[ch];
        }
        if (fwrite(result, frame_bytes, n, target) != (size_t)n) goto done;
    }
    if (fflush(target)) goto done;
    ok = 1;
done:
    free(weights); free(samples);
    if (source) fclose(source);
    if (target && fclose(target)) ok = 0;
    if (!ok && created) unlink(output);
    (*env)->ReleaseStringUTFChars(env, input_path, input); (*env)->ReleaseStringUTFChars(env, output_path, output);
    return ok ? frames : -1;
}
