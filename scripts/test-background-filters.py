#!/usr/bin/env python3
"""Compare sliding guided-filter moments with a brute-force spatial reference."""
import pathlib, subprocess, tempfile

root=pathlib.Path(__file__).resolve().parent.parent
source=(root/'app/src/main/c/background.c').read_text()
def function(name):
 start=source.index('static ',source.index(name)-20)
 opening=source.index('{',start);depth=1;end=opening+1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]

reference=r'''
static int reference(float *a,const float *guide,int w,int h,float softness,int person) {
 float *ab=malloc((size_t)w*h*2*sizeof(float));if(!ab)return 0;
 int r=(int)((2+6*softness)*fminf(w,h)/1080);if(r<1)r=1;if(r>6)r=6;
 for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
  float si=0,sp=0,sii=0,sip=0,count=0;
  for(int dy=-r;dy<=r;dy++)for(int dx=-r;dx<=r;dx++) {
   int sx=(int)yr_clamp(x+dx,0,w-1),sy=(int)yr_clamp(y+dy,0,h-1),i=sy*w+sx;
   si+=guide[i];sp+=a[i];sii+=guide[i]*guide[i];sip+=guide[i]*a[i];count++;
  }
  si/=count;sp/=count;sii/=count;sip/=count;
  float k=(sip-si*sp)/(fmaxf(0,sii-si*si)+.001f);
  ab[(y*w+x)*2]=k;ab[(y*w+x)*2+1]=sp-k*si;
 }
 int ok=blur(ab,w,h,2,person?.25f+.75f*softness:1+softness);
 if(ok)for(int i=0;i<w*h;i++) {
  float value=yr_clamp(ab[i*2]*guide[i]+ab[i*2+1],0,1),black=.02f+.05f*(1-softness);
  a[i]=person?yr_smooth(black,1-black,value):value;
 }
 free(ab);return ok;
}
int main(void) {
 const int shapes[][2]={{3,2},{63,39},{257,193},{512,288},{640,512},{1080,1080}};
 uint32_t state=123;float maximum=0;int cases=0;
 for(unsigned shape=0;shape<sizeof(shapes)/sizeof(*shapes);shape++)
 for(int mode=0;mode<3;mode++)for(int person=0;person<2;person++)for(int soft=0;soft<=2;soft++) {
  int w=shapes[shape][0],h=shapes[shape][1],n=w*h;
  float *a=malloc((size_t)n*sizeof(float)),*b=malloc((size_t)n*sizeof(float)),*g=malloc((size_t)n*sizeof(float));
  if(!a || !b || !g)return 1;
  for(int i=0;i<n;i++) {
   state=state*1664525u+1013904223u;a[i]=(state>>8)/16777215.f;
   state=state*1664525u+1013904223u;
   g[i]=mode==0?(state>>8)/16777215.f:mode==1?.8f:.5f+(state>>8)/16777215.f*.001f;
   b[i]=a[i];
  }
  if(!refine(a,g,w,h,soft*.5f,person) || !reference(b,g,w,h,soft*.5f,person))return 2;
  for(int i=0;i<n;i++)maximum=fmaxf(maximum,fabsf(a[i]-b[i]));
  free(a);free(b);free(g);cases++;
 }
 printf("%d guided-filter cases; maximum alpha error %.9g\n",cases,maximum);
 return maximum>.0002f;
}
'''
with tempfile.TemporaryDirectory(prefix='myrec-background-') as directory:
 path=pathlib.Path(directory)
 code='#include <stdlib.h>\n#include <string.h>\n#include <stdio.h>\n#include "color_space.h"\n'
 code+='\n'.join(function(name) for name in ['blur(', 'refine('])+reference
 (path/'filter.c').write_text(code)
 subprocess.run(['cc','-std=c11','-O3','-Wall','-Wextra','-Werror','-I',str(root/'app/src/main/c'),str(path/'filter.c'),'-lm','-o',str(path/'filter')],check=True)
 subprocess.run([str(path/'filter')],check=True)
