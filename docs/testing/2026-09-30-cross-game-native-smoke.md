# 并行棋种原生测试协调记录

日期：2026-09-30。统筹对话使用 Android NDK 30 LLVM 的 x86_64 Android 24 编译器，将各专属测试单独链接为可执行文件，推入 Pixel_7_Pro Android 17 AVD，加载同一 NDK 的 `libc++_shared.so` 后执行。未改动任何其他棋种源码、状态文件或公共 CMake/JNI。

| 棋种切片 | 模拟器执行结果 | 边界 |
| --- | --- | --- |
| 围棋规则 `go_test.cpp` | 退出码 0 | 规则核心，未接应用 |
| 围棋 SGF `go_sgf_test.cpp` | 退出码 0 | 单主线转换，未接应用 |
| 国标麻将 `competition_mahjong_test.cpp` | 退出码 0 | 配牌/认领基础切片，非完整番种 |
| 国标麻将 `competition_mahjong_settlement_test.cpp` | 退出码 0 | 接受外部已核验番数的结算公式，非和牌合法性 |
| 兰州麻将 `lanzhou_mahjong_test.cpp` | 退出码 0 | 基础回合与封闭手牌候选，非完整本地规则 |

兰州方棋和老虎吃羊棋现有测试均为 `static_assert` 编译期检查，没有运行时 `main`；其双 ABI 语法检查记录由各棋种文档维护，不把它们记成模拟器运行通过。上述五个可执行文件不经过主 CMake、JNI、Gradle、界面或持久化，不能作为可玩验收。测试时各棋种源码仍属对应对话的未提交工作，后续变动及正式集成后须重新执行受影响测试。

下一共享窗口顺序建议：先稳定四人麻将公共结果格式与围棋动作契约，再由统筹串行修改 CMake/注册/JNI；各棋种专属规则仍可并行开发。国标麻将竞赛规则选择已记于其专属正式规则文档；老虎吃羊棋棋盘与开局决定见 `../development/coordination-decisions-2026-09-30.md`。
