cmake_minimum_required(VERSION 3.22.1)
project(cueflow LANGUAGES C CXX)

include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Debug APKs are used to measure live following on-device. Keep native inference optimized.
add_compile_options("$<$<CONFIG:Debug>:-O3>")

set(whisper_archive "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.cueflow-local/whisper-v1.9.5.tar.gz")
if(NOT EXISTS "${whisper_archive}")
    set(whisper_archive https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/v1.9.5)
endif()
FetchContent_Declare(whisper
    URL "${whisper_archive}"
    URL_HASH SHA256=ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720)
FetchContent_MakeAvailable(whisper)

add_library(cueflow_whisper SHARED whisper_jni.cpp)
target_compile_features(cueflow_whisper PRIVATE cxx_std_17)
target_link_libraries(cueflow_whisper PRIVATE whisper)
