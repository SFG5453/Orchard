# Android's streaming resampler. Analysis, model frontends, and transitions are
# all provided by the shared Rust/Earmark library built by Gradle.

cmake_minimum_required(VERSION 3.22.1)
project(orchard_resampler LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(orchard_resampler SHARED
    analyzer/resampler.cpp
    jni/resampler_jni.cpp
)

target_include_directories(orchard_resampler PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_options(orchard_resampler PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(orchard_resampler ${log-lib})
