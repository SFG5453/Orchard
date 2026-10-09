# Orchard Connect for Android: the desktop's Connect core (core/native/connect)
# behind a thin JNI layer, loaded as liborchard_connect_jni.so. Listening Party peers
# share the same library.

get_filename_component(ORCHARD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
add_subdirectory("${ORCHARD_ROOT}/core/native/connect" connect_core)

add_library(orchard_connect_jni SHARED connect_jni.cpp party_jni.cpp)
set_target_properties(orchard_connect_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(orchard_connect_jni PRIVATE -fvisibility=hidden)
target_link_libraries(orchard_connect_jni PRIVATE orchard_connect)
if(ANDROID)
    target_link_libraries(orchard_connect_jni PRIVATE log)
    # Every dependency is static; keep only what Connect reaches.
    target_link_options(orchard_connect_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()
