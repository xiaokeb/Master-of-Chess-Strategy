# 围棋换色对弈终局审校

用 [终局审校工具](../../../tools/android/go/go-strength-score-review.cpp) 在独立副本上查看双虚后的落点和数子结果。工具先按 Native 规则回读原始 MOCG，检查尺寸、贴目和末尾两次确认。原始记录始终作为后续入参保留。

- 传入棋盘规格、原始 MOCG 路径可打印带 0 基坐标的棋盘与原始数子。结果依次为胜负编码、黑方面积、白方面积、黑方贴子和死子数。面积和贴子以四分之一子为单位。
- 再提供新的输出路径和零个或多个 `x,y` 坐标，可以核实已审校的死子组。每组使用一个落点。Native 对同一新提议重做双方确认，随后在另一实例回读新 MOCG。有争议的局只保留原始观察。
- 使用 Android NDK 30 的 C++17 编译器，加入 `engine-native/src/main/cpp/include`，链接同基线 CMake 生成的 `libmocs_engine_core.a`。在相同 ABI 的 Android 测试设备运行可执行文件，输入、输出均放在隔离测试目录。

NDK 30 两个 ABI 已编译和链接。Pixel 7 Pro 模拟器对六场诊断记录重做空死子确认并比对全部字节，验证显示坐标及一例显式整组标记。错误规格、同组重复标记和已有输出文件均被拒绝。显式标记用于验证工具行为，正式样本的死子结论需逐局审校。

[换色协议](2026-11-15-search-strength-protocol.md) · [验收清单](../../releases/manifests/v0.1.0-go-strength-score-review.1.json)
