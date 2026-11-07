#define _POSIX_C_SOURCE 200809L
#include <onnxruntime_c_api.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
static const OrtApi *api;
static void check(OrtStatus *status){if(!status)return;fprintf(stderr,"%s\n",api->GetErrorMessage(status));api->ReleaseStatus(status);exit(1);}
int main(int argc,char **argv) {
    if(argc<6){fprintf(stderr,"Usage: algorithm-bench model input dims output threads\n");return 1;}
    api=OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if(!api)return 1;
    FILE *file=fopen(argv[2],"rb");if(!file)return 1;
    fseek(file,0,SEEK_END);long bytes=ftell(file);rewind(file);
    float *samples=malloc((size_t)bytes);if(!samples)return 1;
    if(fread(samples,1,(size_t)bytes,file)!=(size_t)bytes)return 1;fclose(file);
    int64_t dims[8];size_t rank=0,count=1;
    for(char *p=strtok(argv[3],",");p&&rank<8;p=strtok(NULL,",")){dims[rank]=strtoll(p,NULL,10);count*=(size_t)dims[rank++];}
    if(count*sizeof(float)!=(size_t)bytes){fprintf(stderr,"Input shape mismatch\n");return 1;}
    OrtEnv *env=NULL;OrtSessionOptions *options=NULL;OrtSession *session=NULL;
    OrtMemoryInfo *memory=NULL;OrtAllocator *allocator=NULL;OrtValue *input=NULL,*output=NULL;
    check(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING,"yourec-research",&env));
    check(api->CreateSessionOptions(&options));check(api->SetIntraOpNumThreads(options,atoi(argv[5])));
    check(api->SetInterOpNumThreads(options,1));check(api->SetSessionGraphOptimizationLevel(options,ORT_ENABLE_ALL));
    double load=now();check(api->CreateSession(env,argv[1],options,&session));load=now()-load;
    check(api->GetAllocatorWithDefaultOptions(&allocator));char *name=NULL,*out_name=NULL;
    check(api->SessionGetInputName(session,0,allocator,&name));check(api->SessionGetOutputName(session,0,allocator,&out_name));
    check(api->CreateCpuMemoryInfo(OrtArenaAllocator,OrtMemTypeDefault,&memory));
    check(api->CreateTensorWithDataAsOrtValue(memory,samples,(size_t)bytes,dims,rank,ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,&input));
    const char *input_names[]={name},*output_names[]={out_name};const OrtValue *inputs[]={input};
    check(api->Run(session,NULL,input_names,inputs,1,output_names,1,&output));api->ReleaseValue(output);output=NULL;
    double times[5];
    for(int i=0;i<5;i++){double t=now();check(api->Run(session,NULL,input_names,inputs,1,output_names,1,&output));times[i]=now()-t;if(i<4){api->ReleaseValue(output);output=NULL;}}
    OrtTensorTypeAndShapeInfo *shape=NULL;size_t size=0;void *result=NULL;
    check(api->GetTensorTypeAndShape(output,&shape));check(api->GetTensorShapeElementCount(shape,&size));
    check(api->GetTensorMutableData(output,&result));file=fopen(argv[4],"wb");if(!file)return 1;
    if(fwrite(result,sizeof(float),size,file)!=size)return 1;fclose(file);
    printf("{\"runtime\":\"%s\",\"load_seconds\":%.6f,\"threads\":%d,\"output_floats\":%zu,\"run_seconds\":[",OrtGetApiBase()->GetVersionString(),load,atoi(argv[5]),size);
    for(int i=0;i<5;i++)printf("%s%.6f",i?",":"",times[i]);printf("]}\n");
    api->ReleaseTensorTypeAndShapeInfo(shape);api->ReleaseValue(output);api->ReleaseValue(input);
    check(api->AllocatorFree(allocator,name));check(api->AllocatorFree(allocator,out_name));
    api->ReleaseMemoryInfo(memory);api->ReleaseSession(session);api->ReleaseSessionOptions(options);api->ReleaseEnv(env);free(samples);return 0;
}
