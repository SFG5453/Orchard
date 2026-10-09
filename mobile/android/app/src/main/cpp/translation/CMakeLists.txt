# On-device lyric translation: the desktop's core/native/translation behind JNI.
# libLiteRt.so is Google's prebuilt runtime, packaged from vendor/litert and opened with dlopen.

get_filename_component(ORCHARD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
set(TRANSLATION_DIR "${ORCHARD_ROOT}/core/native/translation")

add_library(orchard_translation SHARED
    "${TRANSLATION_DIR}/litert_api.cpp"
    "${TRANSLATION_DIR}/lyric_language.cpp"
    "${TRANSLATION_DIR}/marian_model.cpp"
    "${TRANSLATION_DIR}/sentencepiece_tokenizer.cpp"
    "${TRANSLATION_DIR}/translation_pack_table.cpp"
    translation_jni.cpp
)
set_target_properties(orchard_translation PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_include_directories(orchard_translation PRIVATE "${ORCHARD_ROOT}/core/native" "${ORCHARD_ROOT}/vendor/litert/include")
# Greedy decode runs a vocab-wide argmax per token; -O0 would make debug builds crawl.
target_compile_options(orchard_translation PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(orchard_translation PRIVATE ${CMAKE_DL_LIBS})
if(ANDROID)
    target_link_options(orchard_translation PRIVATE -Wl,--gc-sections)
endif()
