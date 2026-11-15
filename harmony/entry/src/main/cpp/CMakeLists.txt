cmake_minimum_required(VERSION 3.5.0)
project(ppocr)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ORT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/onnxruntime)
set(PREBUILT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a)

include_directories(${ORT_DIR}/include)

add_library(ppocr SHARED ppocr_engine.cpp napi_init.cpp)

target_link_libraries(ppocr PUBLIC
    ${PREBUILT_DIR}/libonnxruntime.so.1
    libace_napi.z.so
    libhilog_ndk.z.so)

target_compile_options(ppocr PRIVATE -O2 -Wall -Wno-unused-parameter)
target_link_options(ppocr PRIVATE -Wl,-rpath,\$ORIGIN)
