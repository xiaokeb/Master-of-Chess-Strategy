add_executable(mocs_engine_health_check_test health_check_test.cpp)
target_link_libraries(mocs_engine_health_check_test PRIVATE mocs_engine_core)

add_test(
    NAME mocs_engine_health_check
    COMMAND mocs_engine_health_check_test
)
