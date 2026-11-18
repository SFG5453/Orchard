# Tree-free YouTube player extractor. Standalone for host tests; the Android
# build includes it through add_subdirectory.

cmake_minimum_required(VERSION 3.22.1)
if(NOT PROJECT_NAME)
    project(orchard_youtube_extractor LANGUAGES C)
endif()

find_package(Threads REQUIRED)
add_library(orchard_youtube_extractor STATIC
    ${CMAKE_CURRENT_LIST_DIR}/yte_defs.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_expr.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_extract.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_func.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_lex.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_primary.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_scope.c
    ${CMAKE_CURRENT_LIST_DIR}/yte_stmt.c
)
set_target_properties(orchard_youtube_extractor PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON)
target_include_directories(orchard_youtube_extractor PUBLIC ${CMAKE_CURRENT_LIST_DIR})
target_compile_definitions(orchard_youtube_extractor PRIVATE _POSIX_C_SOURCE=200809L)
target_compile_options(orchard_youtube_extractor PRIVATE -Wall -Wextra -O2)
target_link_libraries(orchard_youtube_extractor PUBLIC Threads::Threads)
if(NOT ANDROID AND NOT APPLE)
    target_link_libraries(orchard_youtube_extractor PUBLIC m)
endif()

if(NOT ANDROID AND PROJECT_SOURCE_DIR STREQUAL CMAKE_CURRENT_LIST_DIR)
    enable_testing()
    add_executable(test_youtube_extractor test/test_extractor.c)
    target_link_libraries(test_youtube_extractor PRIVATE orchard_youtube_extractor)
    add_test(NAME youtube_extractor COMMAND test_youtube_extractor)
    # Player CLI and sanitizer stress tools.
    add_executable(yte_cli test/yte_cli.c)
    target_link_libraries(yte_cli PRIVATE orchard_youtube_extractor)
    add_executable(yte_stress test/yte_stress.c)
    target_link_libraries(yte_stress PRIVATE orchard_youtube_extractor)
endif()
