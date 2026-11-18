#ifndef MYREC_DISPLAY_H
#define MYREC_DISPLAY_H
#include "color_space.h"
#include <pthread.h>
#include <string.h>

static uint16_t display_bins[4096];
static float display_edges[256];
static pthread_once_t display_once=PTHREAD_ONCE_INIT;
static void display_init(void) {
    for (int byte=1;byte<256;byte++) {
        uint32_t low=0,high=0x3f800000;
        while (low<high) {
            uint32_t middle=low+(high-low)/2;float value;memcpy(&value,&middle,4);
            if (yr_display(value)<byte) low=middle+1;else high=middle;
        }
        memcpy(display_edges+byte,&low,4);
    }
    for (int i=0;i<4096;i++) {
        int low=yr_display(i/4096.f),high=yr_display(nextafterf((i+1)/4096.f,0));
        display_bins[i]=(uint16_t)(low*256+high);
    }
}
static void display_prepare(void) { pthread_once(&display_once,display_init); }

/* Each interval spans at most one byte boundary. Its exact floating-point
 * threshold preserves the original rounding without per-pixel powers. */
static inline uint8_t display_byte(float value) {
    if (value>=0 && value<1) {
        unsigned pair=display_bins[(int)(value*4096)];
        unsigned low=pair>>8,high=pair&255;
        return (uint8_t)(low==high || value<display_edges[high]?low:high);
    }
    return yr_display(value);
}
#endif
