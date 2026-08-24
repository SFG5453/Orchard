# Native analysis front end for Smart Crossfade.
#
# Only the DSP that has to match a trained model's input contract exactly lives
# here. The mel front end is a straight port of Orchard desktop's
# native/analyzer, and deliberately unchanged: the Beat This! model was trained
# on these exact window, hop, mel and log conventions, so a front end that
# differs even slightly feeds the network something it has never seen.
#
# The shared DSP sources are compiled directly from Orchard's root native tree.
# Keep Android-specific JNI and resampling code in this directory, but do not
# maintain a second copy of the analyzer implementation here.
#
# Transitions are no longer built here at all. They are rendered by the earmark
# engine, a Rust crate shared with the desktop app and cross-compiled by the
# `buildEarmarkLibrary` Gradle task, which is also what retired this library's
# vendored copy of Rubber Band.

cmake_minimum_required(VERSION 3.22.1)
project(orchard_analysis LANGUAGES CXX)

get_filename_component(ORCHARD_NATIVE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../native"
    ABSOLUTE)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(orchard_analysis SHARED
    ${ORCHARD_NATIVE_DIR}/analyzer/mel_spectrogram.cpp
    analyzer/resampler.cpp
    ${ORCHARD_NATIVE_DIR}/analyzer/audio_analysis.cpp
    ${ORCHARD_NATIVE_DIR}/analyzer/tempo_analysis.cpp
    ${ORCHARD_NATIVE_DIR}/analyzer/vocal_spectrogram.cpp
    jni/mel_jni.cpp
    jni/analysis_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(orchard_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ORCHARD_NATIVE_DIR}
    ${ORCHARD_NATIVE_DIR}/analyzer
)

# The spectrogram is O(n log n) over a whole analysis window and runs off the
# main thread, so it is worth optimizing hard; -ffast-math is deliberately NOT
# enabled, because the model contract depends on the exact log/mel arithmetic.
target_compile_options(orchard_analysis PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(orchard_analysis ${log-lib})
