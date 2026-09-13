cmake_minimum_required(VERSION 3.22.1)

project(mocs_engine LANGUAGES CXX ASM)

if(NOT ANDROID)
    message(FATAL_ERROR "MasterofChessStrategy native code requires the Android NDK LLVM toolchain")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(
    PIKAFISH_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/pikafish/src"
)
set(
    PIKAFISH_SOURCES
    "${PIKAFISH_ROOT}/attacks.cpp"
    "${PIKAFISH_ROOT}/benchmark.cpp"
    "${PIKAFISH_ROOT}/bitboard.cpp"
    "${PIKAFISH_ROOT}/engine.cpp"
    "${PIKAFISH_ROOT}/evaluate.cpp"
    "${PIKAFISH_ROOT}/memory.cpp"
    "${PIKAFISH_ROOT}/misc.cpp"
    "${PIKAFISH_ROOT}/movegen.cpp"
    "${PIKAFISH_ROOT}/movepick.cpp"
    "${PIKAFISH_ROOT}/position.cpp"
    "${PIKAFISH_ROOT}/score.cpp"
    "${PIKAFISH_ROOT}/search.cpp"
    "${PIKAFISH_ROOT}/thread.cpp"
    "${PIKAFISH_ROOT}/timeman.cpp"
    "${PIKAFISH_ROOT}/tt.cpp"
    "${PIKAFISH_ROOT}/tune.cpp"
    "${PIKAFISH_ROOT}/uci.cpp"
    "${PIKAFISH_ROOT}/ucioption.cpp"
    "${PIKAFISH_ROOT}/external/common/debug.cpp"
    "${PIKAFISH_ROOT}/external/common/entropy_common.cpp"
    "${PIKAFISH_ROOT}/external/common/error_private.cpp"
    "${PIKAFISH_ROOT}/external/common/fse_decompress.cpp"
    "${PIKAFISH_ROOT}/external/common/pool.cpp"
    "${PIKAFISH_ROOT}/external/common/threading.cpp"
    "${PIKAFISH_ROOT}/external/common/xxhash.cpp"
    "${PIKAFISH_ROOT}/external/common/zstd_common.cpp"
    "${PIKAFISH_ROOT}/external/decompress/huf_decompress.cpp"
    "${PIKAFISH_ROOT}/external/decompress/zstd_ddict.cpp"
    "${PIKAFISH_ROOT}/external/decompress/zstd_decompress_block.cpp"
    "${PIKAFISH_ROOT}/external/decompress/zstd_decompress.cpp"
    "${PIKAFISH_ROOT}/nnue/network.cpp"
    "${PIKAFISH_ROOT}/nnue/nnue_accumulator.cpp"
    "${PIKAFISH_ROOT}/nnue/nnue_misc.cpp"
    "${PIKAFISH_ROOT}/nnue/features/full_threats.cpp"
    "${PIKAFISH_ROOT}/nnue/features/half_ka_v2_hm.cpp"
)

add_library(mocs_pikafish STATIC ${PIKAFISH_SOURCES})
target_include_directories(mocs_pikafish PUBLIC "${PIKAFISH_ROOT}")
if(ANDROID_ABI STREQUAL "x86_64")
    target_sources(
        mocs_pikafish
        PRIVATE
        "${PIKAFISH_ROOT}/external/decompress/huf_decompress_amd64.S"
    )
endif()
if(CMAKE_SIZEOF_VOID_P EQUAL 8)
    target_compile_definitions(mocs_pikafish PUBLIC IS_64BIT)
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(
        mocs_pikafish
        PUBLIC
        USE_NEON=8
        USE_POPCNT
    )
endif()

add_library(
    mocs_engine_core
    STATIC
    src/chinese_chess.cpp
    src/health_check.cpp
    src/native_engine_registry.cpp
    src/pikafish_adapter.cpp
)
target_include_directories(
    mocs_engine_core
    PUBLIC
    include
)
target_compile_options(
    mocs_engine_core
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)
target_link_libraries(mocs_engine_core PUBLIC mocs_pikafish)
set_target_properties(
    mocs_engine_core
    PROPERTIES
    POSITION_INDEPENDENT_CODE ON
)

add_library(
    mocs_engine_native
    SHARED
    src/jni_bridge.cpp
)
target_link_libraries(
    mocs_engine_native
    PRIVATE
    mocs_engine_core
)
target_compile_options(
    mocs_engine_native
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

option(MOCS_BUILD_TESTS "Build Android native test targets" OFF)

if(MOCS_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
