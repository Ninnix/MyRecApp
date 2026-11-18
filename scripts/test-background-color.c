#include "../app/src/main/c/display.h"
#include <stdio.h>

int main(void) {
    display_prepare();
    uint32_t state=1;
    for (int i=0;i<4000000;i++) {
        state=state*1664525u+1013904223u;
        float value=(state>>8)/16777215.f;
        if (display_byte(value)!=yr_display(value)) return 1;
    }
    for (int i=1;i<256;i++) {
        float edge=display_edges[i];
        const float values[]={nextafterf(edge,0),edge,nextafterf(edge,INFINITY)};
        for (unsigned j=0;j<sizeof(values)/sizeof(*values);j++)
            if (display_byte(values[j])!=yr_display(values[j])) return 4;
    }
    for (int i=0;i<4096;i++) if ((display_bins[i]&255)>(display_bins[i]>>8)+1) return 5;
    for (int i=0;i<=4096;i++) {
        float value=i/4096.f;
        for (int direction=-1;direction<=1;direction++) {
            float edge=direction?nextafterf(value,direction<0?-INFINITY:INFINITY):value;
            if (display_byte(edge)!=yr_display(edge)) return 2;
        }
    }
    const float extremes[]={-INFINITY,-1,0,1,2,INFINITY,NAN};
    for (unsigned i=0;i<sizeof(extremes)/sizeof(*extremes);i++)
        if (display_byte(extremes[i])!=yr_display(extremes[i])) return 3;
    puts("Exact background display conversion passed");
    return 0;
}
