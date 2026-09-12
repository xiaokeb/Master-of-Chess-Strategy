cmake_minimum_required(VERSION 3.22.1)

project(mocs_engine LANGUAGES CXX)

if(NOT ANDROID)
    message(FATAL_ERROR "MasterofChessStrategy native code requires the Android NDK LLVM toolchain")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    mocs_engine_core
    STATIC
    src/chinese_chess.cpp
    src/health_check.cpp
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
