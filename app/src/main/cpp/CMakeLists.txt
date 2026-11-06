cmake_minimum_required(VERSION 3.22)
project(transcribe_whisper C CXX)

# The Android Gradle plugin builds debug variants as Debug. Unoptimised ggml takes minutes per clip,
# and the native code is not what gets debugged here, so it is always built optimised.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# CPU only, as the plan specifies; the Vulkan build is a later, optional engine.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml's own thread pool, so no OpenMP runtime has to be packaged.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# The Pixel 10's Tensor G5 is Armv9.2: dot-product and int8 matrix instructions carry the q5/q8
# kernels, fp16 the rest. Every arm64 phone this targets in practice has all three.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+i8mm+fp16" CACHE STRING "" FORCE)
# KleidiAI was tried and left off: on a 3-minute note with q8 it made the encoder ~13% faster per
# window but the decoder slower, and its different rounding cost one extra decode pass. Net 103 s
# against 101 s without it, and a build-time download.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(transcribe_whisper SHARED whisper_jni.c)
target_compile_options(transcribe_whisper PRIVATE -O3 -Wall)
target_link_libraries(transcribe_whisper PRIVATE whisper log)
