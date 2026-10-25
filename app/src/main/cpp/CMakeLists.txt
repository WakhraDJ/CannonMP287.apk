cmake_minimum_required(VERSION 3.22.1)
project(canonscan C)

add_library(canonscan SHARED
    jni/scan_jni.c
    io/pixma_io_android.c
    pixma/pixma_common.c
    pixma/pixma_mp150.c)

target_compile_options(canonscan PRIVATE -Wall -Wno-unused-function -Wno-unused-variable)
target_link_libraries(canonscan PRIVATE m)
if(ANDROID)
    target_link_libraries(canonscan PRIVATE log)
endif()
