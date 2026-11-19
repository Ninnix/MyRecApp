#ifndef MYREC_STABILIZE_H
#define MYREC_STABILIZE_H
#include <stdint.h>
/* Maps reference pixels to current pixels: x'=a*x-b*y+tx, y'=b*x+a*y+ty. */
int yr_similarity(const uint8_t *reference, const uint8_t *current, int width, int height,
                  float transform[5]);
int yr_camera_similarity(const uint8_t *reference, const uint8_t *current, int width, int height,
                         float transform[5]);
#endif
