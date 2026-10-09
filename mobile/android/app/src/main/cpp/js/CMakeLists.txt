# Desktop's QuickJS and provider host, built for Android and for
# host unit tests. Configured on its own, this also builds the host qjsc.

cmake_minimum_required(VERSION 3.22.1)
project(orchard_js LANGUAGES C CXX)

get_filename_component(ORCHARD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
set(QUICKJS_DIR "${ORCHARD_ROOT}/third_party/quickjs")

add_library(orchard_js SHARED
    "${QUICKJS_DIR}/quickjs.c"
    "${QUICKJS_DIR}/dtoa.c"
    "${QUICKJS_DIR}/libregexp.c"
    "${QUICKJS_DIR}/libunicode.c"
    "${ORCHARD_ROOT}/core/native/provider_host/provider_host.cpp"
    "${ORCHARD_ROOT}/core/native/provider_host/provider_host_media.cpp"
    provider_jni.cpp
)
set_target_properties(orchard_js PROPERTIES C_STANDARD 11 CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(orchard_js PRIVATE "${QUICKJS_DIR}" "${ORCHARD_ROOT}/core/native")
target_compile_definitions(orchard_js PRIVATE _GNU_SOURCE QUICKJS_NG_BUILD)
# An -O0 interpreter makes every debug-build transition feel like vinyl at 16 rpm.
target_compile_options(orchard_js PRIVATE -O2 -fvisibility=hidden
    $<$<COMPILE_LANGUAGE:C>:-funsigned-char -w>)
target_link_libraries(orchard_js PRIVATE m ${CMAKE_DL_LIBS})

if(ANDROID)
    target_link_libraries(orchard_js PRIVATE log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(orchard_js PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(orchard_js PRIVATE pthread)
    # Provider bytecode must come from this exact engine, so build its compiler too.
    set(QJS_BUILD_LIBC OFF CACHE BOOL "" FORCE)
    add_subdirectory("${QUICKJS_DIR}" quickjs EXCLUDE_FROM_ALL)
endif()
