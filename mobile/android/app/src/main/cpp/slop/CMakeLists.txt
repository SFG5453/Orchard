get_filename_component(ORCHARD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../.." ABSOLUTE)
add_library(orchard_slop SHARED
    "${ORCHARD_ROOT}/core/native/slop/slop_fingerprint.cpp"
    slop_jni.cpp
)
set_target_properties(orchard_slop PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
target_include_directories(orchard_slop PRIVATE "${ORCHARD_ROOT}/core/native")
target_compile_options(orchard_slop PRIVATE -O2 -fvisibility=hidden)
