cmake_minimum_required(VERSION 3.22)
project(myrec_qwen C)
set(CMAKE_C_STANDARD 11)
get_filename_component(ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(RUNTIME_ROOT "${ROOT}/.build/runtime-sources" CACHE PATH "External runtime repositories")
set(QW "${RUNTIME_ROOT}/qwen-asr-main")
add_library(qwen_asr SHARED "${ROOT}/app/src/main/c/qwen.c"
    "${ROOT}/app/src/main/c/gguf.c" "${CMAKE_CURRENT_LIST_DIR}/threads.c"
    "${QW}/qwen_asr.c" "${QW}/qwen_asr_kernels_generic.c"
    "${QW}/qwen_asr_kernels_neon.c" "${QW}/qwen_asr_kernels_avx.c"
    "${QW}/qwen_asr_audio.c" "${QW}/qwen_asr_encoder.c" "${QW}/qwen_asr_decoder.c"
    "${QW}/qwen_asr_tokenizer.c" "${QW}/qwen_asr_safetensors.c")
target_include_directories(qwen_asr PRIVATE "${QW}")
target_compile_definitions(qwen_asr PRIVATE _GNU_SOURCE)
target_compile_options(qwen_asr PRIVATE -O3 -Wall -Wextra)
set_source_files_properties("${CMAKE_CURRENT_LIST_DIR}/threads.c" PROPERTIES COMPILE_OPTIONS "-ffast-math;-fno-finite-math-only")
target_link_libraries(qwen_asr PRIVATE m)
if(ANDROID)
    target_link_options(qwen_asr PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
else()
    target_link_libraries(qwen_asr PRIVATE pthread)
endif()
