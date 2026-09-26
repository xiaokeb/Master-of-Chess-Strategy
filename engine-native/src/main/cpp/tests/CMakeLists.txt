add_executable(mocs_engine_health_check_test health_check_test.cpp)
target_link_libraries(mocs_engine_health_check_test PRIVATE mocs_engine_core)
target_compile_options(
    mocs_engine_health_check_test
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

add_test(
    NAME mocs_engine_health_check
    COMMAND mocs_engine_health_check_test
)

add_executable(mocs_engine_chinese_chess_test chinese_chess_test.cpp)
target_link_libraries(mocs_engine_chinese_chess_test PRIVATE mocs_engine_core)
target_compile_options(
    mocs_engine_chinese_chess_test
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

add_test(
    NAME mocs_engine_chinese_chess
    COMMAND mocs_engine_chinese_chess_test
)

add_executable(mocs_generate_xiangqi_mates generate_xiangqi_mates.cpp)
target_link_libraries(mocs_generate_xiangqi_mates PRIVATE mocs_engine_core)
target_compile_options(
    mocs_generate_xiangqi_mates
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

add_executable(mocs_benchmark_xiangqi_ai benchmark_xiangqi_ai.cpp)
target_link_libraries(mocs_benchmark_xiangqi_ai PRIVATE mocs_engine_core)
target_compile_options(mocs_benchmark_xiangqi_ai PRIVATE -Wall -Wextra -Wpedantic)

add_executable(
    mocs_engine_registry_test
    native_engine_registry_test.cpp
)
target_link_libraries(mocs_engine_registry_test PRIVATE mocs_engine_core)
target_compile_options(
    mocs_engine_registry_test
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

add_test(
    NAME mocs_engine_registry
    COMMAND mocs_engine_registry_test
)
