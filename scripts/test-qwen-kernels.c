#include <dlfcn.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
typedef void (*conv)(float*,const float*,const float*,const float*,int,int,int,int,int,int,int,int);
typedef void (*linear)(float*,const float*,const float*,const float*,int,int,int);
static unsigned state=42;
static float random_value(void){state=1664525*state+1013904223;return ((state>>8)%2001-1000.f)/1000.f;}
static void fill(float *x,int n){for(int i=0;i<n;i++)x[i]=random_value();}
static int near(float *a,float*b,int n){float max=0;for(int i=0;i<n;i++){float e=fabsf(a[i]-b[i]);if(e>max)max=e;if(!isfinite(a[i])||e>1e-4f)return 0;}printf("max %.9f\n",max);return 1;}
int main(int argc,char**argv){
 if(argc!=2)return 1;void *lib=dlopen(argv[1],RTLD_NOW);if(!lib){puts(dlerror());return 2;}
 conv a=dlsym(lib,"qwen_conv2d"),b=dlsym(lib,"qwen_base_conv2d");
 linear la=dlsym(lib,"qwen_linear"),lb=dlsym(lib,"qwen_base_linear");
 void(*threads)(int)=dlsym(lib,"qwen_set_threads");
 if(!a||!b||!la||!lb||!threads){puts("Missing kernel symbols");return 2;}threads(4);
 float x[3*9*11],w[7*3*3*3],bias[7],out[7*9*11],ref[7*9*11];fill(x,3*9*11);fill(w,7*3*3*3);fill(bias,7);
 for(int stride=1;stride<=2;stride++){
  a(out,x,w,bias,3,7,9,11,3,3,stride,1);b(ref,x,w,bias,3,7,9,11,3,3,stride,1);
  int n=7*((9+2-3)/stride+1)*((11+2-3)/stride+1);if(!near(out,ref,n))return 3;
 }
 for(int k=13;k<35;k+=19){fill(x,5*k);fill(w,7*k);
  la(out,x,w,bias,5,k,7);lb(ref,x,w,bias,5,k,7);if(!near(out,ref,35))return 4;
 }
 puts("PASS C/NEON convolution and linear tails, bias, stride, multiple threads");return 0;
}
