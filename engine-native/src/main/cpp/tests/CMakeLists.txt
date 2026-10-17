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

add_library(mocs_xiangqi_authoring STATIC xiangqi_forced_win.cpp)
target_link_libraries(mocs_xiangqi_authoring PUBLIC mocs_engine_core)
target_compile_options(mocs_xiangqi_authoring PRIVATE -Wall -Wextra -Wpedantic)

add_executable(mocs_xiangqi_forced_win_test xiangqi_forced_win_test.cpp)
target_link_libraries(mocs_xiangqi_forced_win_test PRIVATE mocs_xiangqi_authoring)
target_compile_options(mocs_xiangqi_forced_win_test PRIVATE -Wall -Wextra -Wpedantic)
add_test(NAME mocs_xiangqi_forced_win COMMAND mocs_xiangqi_forced_win_test)

add_executable(mocs_generate_xiangqi_mates generate_xiangqi_mates.cpp)
target_link_libraries(mocs_generate_xiangqi_mates PRIVATE mocs_xiangqi_authoring)
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

add_executable(mocs_pikafish_search_history_test pikafish_search_history_test.cpp)
target_link_libraries(mocs_pikafish_search_history_test PRIVATE mocs_engine_core)
target_compile_options(mocs_pikafish_search_history_test PRIVATE -Wall -Wextra -Wpedantic)

# Independent safety checks require an explicit verified NNUE path at runtime.
# They must never replace a frozen strength-evaluation executable.
foreach(budget_suite IN ITEMS safety runtime)
    add_executable(
        mocs_pikafish_budget_${budget_suite}_test
        ../games/xiangqi/pikafish_budget_${budget_suite}_test.cpp
    )
    target_link_libraries(mocs_pikafish_budget_${budget_suite}_test PRIVATE mocs_pikafish)
    target_compile_options(
        mocs_pikafish_budget_${budget_suite}_test
        PRIVATE -Wall -Wextra -Wpedantic -Werror -UNDEBUG
    )
    target_link_options(mocs_pikafish_budget_${budget_suite}_test PRIVATE -static-libstdc++)
endforeach()

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

# Keep registry assertions active in Release builds as well.
target_compile_options(mocs_engine_registry_test PRIVATE -UNDEBUG)

# Tiger-goat has independent rule/exception executables and constexpr-only topology/step targets.
add_executable(mocs_tiger_goat_game_test ../games/tiger-goat/tiger_goat_game_test.cpp)
target_link_libraries(mocs_tiger_goat_game_test PRIVATE mocs_engine_core)
target_compile_options(mocs_tiger_goat_game_test PRIVATE -Wall -Wextra -Wpedantic -UNDEBUG)
add_test(NAME mocs_tiger_goat_game COMMAND mocs_tiger_goat_game_test)

add_executable(mocs_tiger_goat_exception_test ../games/tiger-goat/tiger_goat_exception_test.cpp)
target_link_libraries(mocs_tiger_goat_exception_test PRIVATE mocs_engine_core)
target_compile_options(mocs_tiger_goat_exception_test PRIVATE -Wall -Wextra -Wpedantic -UNDEBUG)
add_test(NAME mocs_tiger_goat_exception COMMAND mocs_tiger_goat_exception_test)

add_library(mocs_tiger_goat_topology_compile_test OBJECT ../games/tiger-goat/tiger_goat_topology_test.cpp)
target_link_libraries(mocs_tiger_goat_topology_compile_test PRIVATE mocs_engine_core)
target_compile_options(mocs_tiger_goat_topology_compile_test PRIVATE -Wall -Wextra -Wpedantic)

add_library(mocs_tiger_goat_step_compile_test OBJECT tiger_goat_step_test.cpp)
target_link_libraries(mocs_tiger_goat_step_compile_test PRIVATE mocs_engine_core)
target_compile_options(mocs_tiger_goat_step_compile_test PRIVATE -Wall -Wextra -Wpedantic)

# Go foundation: rules, records, adjudication and real KataGo lifecycle.
foreach(go_test IN ITEMS go go_sgf)
    add_executable(mocs_${go_test}_test ${go_test}_test.cpp)
    target_link_libraries(mocs_${go_test}_test PRIVATE mocs_engine_core)
    target_compile_options(mocs_${go_test}_test PRIVATE -Wall -Wextra -Wpedantic -UNDEBUG)
    add_test(NAME mocs_${go_test} COMMAND mocs_${go_test}_test)
endforeach()
foreach(go_test IN ITEMS go_agreement_regression go_handicap)
    add_executable(mocs_${go_test}_test ../games/go/${go_test}_test.cpp)
    target_link_libraries(mocs_${go_test}_test PRIVATE mocs_engine_core)
    target_compile_options(mocs_${go_test}_test PRIVATE -Wall -Wextra -Wpedantic -UNDEBUG)
    add_test(NAME mocs_${go_test} COMMAND mocs_${go_test}_test)
endforeach()
foreach(go_test IN ITEMS history search runtime)
    add_executable(mocs_go_katago_${go_test}_test ../games/go/katago-probe/go_katago_${go_test}_test.cpp)
    target_link_libraries(mocs_go_katago_${go_test}_test PRIVATE mocs_engine_core)
    target_include_directories(mocs_go_katago_${go_test}_test PRIVATE ../games/go/katago-probe)
    target_compile_options(mocs_go_katago_${go_test}_test PRIVATE -Wall -Wextra -Wpedantic -UNDEBUG)
endforeach()
