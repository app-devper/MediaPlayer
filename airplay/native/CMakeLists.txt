# Native AirPlay receiver: UxPlay's protocol library (vendored in uxplay/) + BoringSSL + libplist,
# wrapped by airplay_core.c (plain C API) and airplay_jni.c (JNI for Android and Desktop).
cmake_minimum_required(VERSION 3.22)
project(mediaplayer_airplay C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)

option(AIRPLAY_BUILD_PROBE "Build the airplay_probe host executable (manual testing)" OFF)
option(AIRPLAY_BUILD_JNI "Build the JNI library" ON)
option(AIRPLAY_BUILD_TESTS "Build the native tests (host only)" OFF)

include(FetchContent)

# --- BoringSSL (libcrypto only) -------------------------------------------------------------
FetchContent_Declare(
    boringssl
    URL https://github.com/google/boringssl/archive/refs/tags/0.20260929.0.tar.gz
    URL_HASH SHA256=04da9ba0664e0a7f028e961c38d604f2cc6dac852a84ca0e51dc1fa051d4c8fe
)
FetchContent_GetProperties(boringssl)
if(NOT boringssl_POPULATED)
    FetchContent_Populate(boringssl)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    add_subdirectory(${boringssl_SOURCE_DIR} ${boringssl_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

# --- libplist (C sources only, built here instead of autotools) -----------------------------
FetchContent_Declare(
    libplist
    URL https://github.com/libimobiledevice/libplist/releases/download/2.8.0/libplist-2.8.0.tar.bz2
    URL_HASH SHA256=b1f59f7634c58b2481325a23ff4e3bf51574a42d868cbe466d2b39b04550752a
)
FetchContent_GetProperties(libplist)
if(NOT libplist_POPULATED)
    FetchContent_Populate(libplist)
endif()

include(CheckSymbolExists)
check_symbol_exists(gmtime_r "time.h" HAVE_GMTIME_R)
check_symbol_exists(localtime_r "time.h" HAVE_LOCALTIME_R)
check_symbol_exists(timegm "time.h" HAVE_TIMEGM)
check_symbol_exists(strndup "string.h" HAVE_STRNDUP)
check_symbol_exists(memmem "string.h" HAVE_MEMMEM)
set(PLIST_CONFIG_DIR ${CMAKE_CURRENT_BINARY_DIR}/plist-config)
file(WRITE ${PLIST_CONFIG_DIR}/config.h.in [=[
#define PACKAGE_VERSION "2.8.0"
#define HAVE_STDINT_H 1
#define HAVE_STRING_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRDUP 1
#define HAVE_STRERROR 1
#cmakedefine HAVE_GMTIME_R 1
#cmakedefine HAVE_LOCALTIME_R 1
#cmakedefine HAVE_TIMEGM 1
#cmakedefine HAVE_STRNDUP 1
#cmakedefine HAVE_MEMMEM 1
]=])
configure_file(${PLIST_CONFIG_DIR}/config.h.in ${PLIST_CONFIG_DIR}/config.h)

set(PLIST_SRC ${libplist_SOURCE_DIR}/src)
add_library(plist STATIC
    ${PLIST_SRC}/base64.c ${PLIST_SRC}/bytearray.c ${PLIST_SRC}/hashtable.c ${PLIST_SRC}/ptrarray.c
    ${PLIST_SRC}/time64.c ${PLIST_SRC}/common.c ${PLIST_SRC}/xplist.c ${PLIST_SRC}/bplist.c
    ${PLIST_SRC}/jsmn.c ${PLIST_SRC}/jplist.c ${PLIST_SRC}/oplist.c ${PLIST_SRC}/out-default.c
    ${PLIST_SRC}/out-plutil.c ${PLIST_SRC}/out-limd.c ${PLIST_SRC}/plist.c
    ${libplist_SOURCE_DIR}/libcnary/node.c ${libplist_SOURCE_DIR}/libcnary/node_list.c
)
target_compile_definitions(plist PRIVATE HAVE_CONFIG_H LIBPLIST_STATIC)
target_include_directories(plist
    PRIVATE ${PLIST_CONFIG_DIR} ${PLIST_SRC} ${libplist_SOURCE_DIR}/libcnary/include
    PUBLIC ${libplist_SOURCE_DIR}/include
)

# --- UxPlay protocol library ----------------------------------------------------------------
set(UX ${CMAKE_CURRENT_SOURCE_DIR}/uxplay)
file(GLOB UX_SRC ${UX}/*.c ${UX}/playfair/*.c ${UX}/llhttp/*.c ${UX}/mdnsd/*.c)
# src/hls_master.c: our master-playlist trimming, called from uxplay/http_handlers.h
add_library(uxplay STATIC ${UX_SRC} src/hls_master.c)
target_include_directories(uxplay PUBLIC ${UX} PRIVATE ${UX}/playfair ${UX}/llhttp ${UX}/mdnsd src)
# Same defines as upstream lib/CMakeLists.txt; libplist >= 2.3.
target_compile_definitions(uxplay PRIVATE NOHOLD PLIST_210 PLIST_230 LIBPLIST_STATIC
    STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX _REENTRANT)
if(UNIX AND NOT APPLE)
    target_compile_definitions(uxplay PRIVATE _LINUX _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64)
endif()
target_link_libraries(uxplay PUBLIC crypto plist)
if(WIN32)
    target_link_libraries(uxplay PUBLIC ws2_32 iphlpapi wsock32)
elseif(ANDROID)
    target_link_libraries(uxplay PUBLIC log)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(uxplay PUBLIC Threads::Threads)
endif()

# --- Apple ALAC decoder (Android has none; used on every platform for one code path) -------
set(CMAKE_CXX_STANDARD 11)
add_library(alac STATIC
    alac/ALACDecoder.cpp alac/ALACBitUtilities.c alac/EndianPortable.c
    alac/ag_dec.c alac/dp_dec.c alac/matrix_dec.c src/alac_decoder.cpp
)
target_include_directories(alac PUBLIC alac src)
# Upstream only recognises x86 as little-endian; every target we build for is little-endian.
target_compile_definitions(alac PRIVATE TARGET_RT_LITTLE_ENDIAN=1)

# --- Our wrapper ----------------------------------------------------------------------------
add_library(airplay_core STATIC src/airplay_core.c)
target_include_directories(airplay_core PUBLIC src)
target_link_libraries(airplay_core PUBLIC uxplay alac)

if(AIRPLAY_BUILD_JNI)
    if(NOT ANDROID)
        find_package(JNI REQUIRED)
    endif()
    add_library(mediaplayer_airplay SHARED src/airplay_jni.c)
    target_link_libraries(mediaplayer_airplay PRIVATE airplay_core)
    if(NOT ANDROID)
        target_include_directories(mediaplayer_airplay PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
endif()

if(AIRPLAY_BUILD_PROBE)
    add_executable(airplay_probe src/airplay_probe.c)
    target_link_libraries(airplay_probe PRIVATE airplay_core)
endif()

if(AIRPLAY_BUILD_TESTS)
    add_executable(alac_roundtrip_test tests/alac_roundtrip_test.cpp
        alac/ALACEncoder.cpp alac/ag_enc.c alac/dp_enc.c alac/matrix_enc.c)
    target_compile_definitions(alac_roundtrip_test PRIVATE TARGET_RT_LITTLE_ENDIAN=1)
    target_link_libraries(alac_roundtrip_test PRIVATE alac)

    add_executable(hls_master_test tests/hls_master_test.c src/hls_master.c)
    target_include_directories(hls_master_test PRIVATE src)
endif()
