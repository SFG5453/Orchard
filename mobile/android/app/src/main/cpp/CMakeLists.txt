# Native analysis front end for Smart Crossfade.
#
# Only the DSP that has to match a trained model's input contract exactly lives
# here. The mel front end is a straight port of Orchard desktop's
# native/analyzer, and deliberately unchanged: the Beat This! model was trained
# on these exact window, hop, mel and log conventions, so a front end that
# differs even slightly feeds the network something it has never seen.
#
# The sources are dependency-free -- each carries its own radix-2 FFT and
# includes nothing outside the STL -- so there is no third-party build here and
# nothing to vendor beyond the two files themselves.

cmake_minimum_required(VERSION 3.22.1)
project(orchard_analysis LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(orchard_analysis SHARED
    analyzer/mel_spectrogram.cpp
    analyzer/resampler.cpp
    analyzer/audio_analysis.cpp
    analyzer/tempo_analysis.cpp
    analyzer/vocal_spectrogram.cpp
    transition/transition_render.cpp
    transition/rubberband_stretch.cpp
    # Rubber Band's own single-file build unit (vendored from breakfastquay/rubberband,
    # GPL-2-or-later -- which is why this repository is AGPL). It #includes the rest of the
    # library's sources itself via paths relative to its own directory, so only this one file
    # is listed here.
    vendor/rubberband/single/RubberBandSingle.cpp
    jni/transition_jni.cpp
    jni/mel_jni.cpp
    jni/analysis_jni.cpp
    jni/vocal_jni.cpp
)

target_include_directories(orchard_analysis PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/vendor/rubberband
)

# Rubber Band selects an FFT and resampler at compile time. Android has neither vDSP nor IPP, so
# it falls back to its own built-in implementations, which is what these select explicitly rather
# than leaving to autodetection.
target_compile_definitions(orchard_analysis PRIVATE
    USE_BUILTIN_FFT
    NO_THREADING
)

# The spectrogram is O(n log n) over a whole analysis window and runs off the
# main thread, so it is worth optimizing hard; -ffast-math is deliberately NOT
# enabled, because the model contract depends on the exact log/mel arithmetic.
target_compile_options(orchard_analysis PRIVATE -O3 -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(orchard_analysis ${log-lib})
