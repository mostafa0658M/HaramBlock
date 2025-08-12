cmake_minimum_required(VERSION 3.22.1)

project("imageclassification")

# Enable RTTI and exceptions
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -frtti -fexceptions -std=c++17 -O3 -ffast-math")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fopenmp")

# OpenCV
set(OpenCV_STATIC off)
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/OpenCV-android-sdk/sdk/native/jni)
find_package(OpenCV REQUIRED)

# ncnn
set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

# Define lib
add_library(
        imageclassification
        SHARED
        inference.cpp
        #native-lib.cpp
)

# Include directories for headers
target_include_directories(imageclassification PRIVATE
    ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/include
    ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/include/ncnn
)

# Links libs
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
        imageclassification
        ncnn
        ${OpenCV_LIBS}
        ${jnigraphics-lib}
        ${log-lib}
)