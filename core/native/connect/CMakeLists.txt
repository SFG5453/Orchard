# Orchard Connect core, shared by desktop and Android. Builds libdatachannel on
# Mbed TLS so both platforms carry the same WebRTC, WebSocket and crypto stack.

cmake_minimum_required(VERSION 3.22)

get_filename_component(ORCHARD_CONNECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)
set(ORCHARD_THIRD_PARTY "${ORCHARD_CONNECT_ROOT}/third_party")

# Android links these archives into a shared library.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# libdatachannel's option() honours this; it would otherwise flip the global default to shared.
set(BUILD_SHARED_LIBS OFF)

# Mbed TLS 3.6 still asks for CMake 3.5; treat it as 3.10 so newer CMake stays quiet.
set(CMAKE_POLICY_VERSION_MINIMUM 3.10)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(DISABLE_PACKAGE_CONFIG_AND_INSTALL ON CACHE BOOL "" FORCE)
set(MBEDTLS_USER_CONFIG_FILE "${CMAKE_CURRENT_LIST_DIR}/mbedtls_user_config.h" CACHE FILEPATH "" FORCE)
add_subdirectory("${ORCHARD_THIRD_PARTY}/mbedtls" mbedtls EXCLUDE_FROM_ALL)

# libdatachannel only creates its own MbedTLS::MbedTLS through find_package.
add_library(MbedTLS::MbedTLS INTERFACE IMPORTED)
set_target_properties(MbedTLS::MbedTLS PROPERTIES INTERFACE_LINK_LIBRARIES "mbedtls;mbedx509;mbedcrypto")

set(USE_MBEDTLS ON CACHE BOOL "" FORCE)
set(USE_GNUTLS OFF CACHE BOOL "" FORCE)
set(USE_NICE OFF CACHE BOOL "" FORCE)
set(PREFER_SYSTEM_LIB OFF CACHE BOOL "" FORCE)
set(NO_MEDIA ON CACHE BOOL "" FORCE)
set(NO_WEBSOCKET OFF CACHE BOOL "" FORCE)
set(NO_EXAMPLES ON CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
add_subdirectory("${ORCHARD_THIRD_PARTY}/libdatachannel" libdatachannel EXCLUDE_FROM_ALL)

add_library(orchard_connect STATIC
    "${CMAKE_CURRENT_LIST_DIR}/device.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/event_loop.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/lan_transport.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/node.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/node_hub.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/node_links.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/node_rpc.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/node_streams.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/playback.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/protocol.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/secure_channel.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/session.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/session_handshake.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/stream.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/webrtc_transport.cpp"
)
set_target_properties(orchard_connect PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(orchard_connect PUBLIC
    "${CMAKE_CURRENT_LIST_DIR}/.."
    "${ORCHARD_THIRD_PARTY}/json/include"
)
target_link_libraries(orchard_connect PUBLIC datachannel-static PRIVATE MbedTLS::MbedTLS)
