# 标准开局终局测试夹具

两份夹具由项目自有方棋规则和AI源码，从 `Game()` 的标准14黑、14红开局经合法动作生成；没有手改局面、计数或结果。用途仅为正式来源、首次结算和备份的功能测试，不构成AI强度评测或人类对局证明。

| Android测试资源（`games/lanzhou_fangqi/ranked/`） | 生成双方 | 动作数 | 真实终局 | SHA256 |
| --- | --- | --- | --- | --- |
| `standard-black-win.mocs-lfq` | 双方easy | 53 | 黑胜 | `1afa0d83676f6a45a50134539d1190291b19623c45d791b398abfdcacc9516ba` |
| `standard-red-win.mocs-lfq` | 红medium、黑easy | 50 | 红胜 | `3315682d4bd316752faf16dc90aab00c2b036c3b8a722eea8029ed60e9110ab2` |

生成工具以NDK30 LLVM编译为Android x86_64，运行于现有Pixel_7_Pro模拟器，内部上限30秒、2048动作；完整历史由新的真实 `Game::deserialize` 重放，确认序列化相等与终局相同后才输出。模拟器应用数据未清空，不涉及象棋任务。

原始生成证据位于 `.build/fq-ranked-fixture-20261030/`，红胜位于其 `red/` 子目录。黑胜/红胜生成ELF SHA分别为 `1873c59d313dcbda8ce7397f9b8840f261fdec5ae95b82b3fe55211688173c27`、`5596d90a17d84993633bffe90501b188d3fb16fe90cef4e7edb106cb9d6966dd`；编译器SHA为 `46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be`。正式JNI与Room测试结果另列于节点验收报告，生成通过不代替这些验收。

生成源码原样保存于 `tools/games/lanzhou-fangqi/ranked-fixtures/generate-black.cpp` 与 `generate-red.cpp`。编译使用NDK30 `clang++ --target=x86_64-linux-android28 -std=c++20 -O2 -static-libstdc++`，包含 `engine-native/src/main/cpp/include`，链接项目的 `lanzhou_fangqi.cpp`、`lanzhou_fangqi_ai.cpp`；在Android x86_64运行，stdout为完整history，stderr为动作数/终局。不同平台或后续AI实现可生成不同合法对局；已有夹具以本表SHA与真实规则重放为准，不要求重复自弈才能运行测试。
