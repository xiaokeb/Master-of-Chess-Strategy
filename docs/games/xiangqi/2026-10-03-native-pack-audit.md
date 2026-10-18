# 象棋 31 关正式资源的 NDK 规则审计

日期：2026-10-03。目标是补足新四关在正式原生规则中的全防守证据；不代替 App 仪器解析和玩家棋盘视觉验收。

## 方法

新增 `engine-native/src/main/cpp/tests/xiangqi_release_pack_test.cpp`，直接读取 v5 正式文本，以 MOCX v2 夹具编码交给 `ChineseChessEngine::restore`。31 关均逐着回放主变化并确认限步内红胜；17 道“唯一一步杀”用项目作者工具穷举全部合法首着并核对唯一将死首着；9 道“两步强制胜”先排除一步胜，再穷举首着、黑方全部合法应手及可获胜后着。证明必须完整，预算耗尽即失败；审计过程中初始局面不得变异。其他主题只核对主变化。资源结构仍由独立 Python 审核与 Gradle 内容门禁负责。

## 实测

- 现有 Android NDK 30 LLVM x86_64 CMake 树定向构建 `mocs_xiangqi_release_pack_test` 成功。arm64 Android 24 Clang 以 `-std=c++17 -Wall -Wextra -Wpedantic -Werror -fsyntax-only` 通过；arm64 可执行文件未运行。
- 将二进制与正式资源推入 Pixel_7_Pro x86_64 AVD，运行输出 `release-pack levels=31 native-proof-nodes=13337`，退出码 0。首次覆盖文件后可执行位丢失，`chmod 755` 后重跑得到此成功结果；先前退出码 126 只由测试二进制权限造成。
- 源文件、当前 `app-debug.apk` 内 `assets/endgames/chinese_chess/endgames-v5.txt` 和设备测试文件的 SHA-256 均为 `523522889b77a27b6d98e809b9da12da2b170cbd294e6f7952fbb63136ed0875`。因此本次原生规则测试针对与当前 APK 同字节的资源，不是旧 27 关夹具。
- `:app:compileDebugAndroidTestKotlin --offline --max-workers=2` 仍仅在国标麻将专属测试第 4、5 行无效 Compose 导入处失败；按用户决定由该棋种对话修复，本对话不修改其源码。

## 验收边界

本次原生审计完成时，新四关的原生规则和主变化已有独立设备证据，但 App 资产解析、APK 内 JNI 测试、四张新棋盘逐关触控/视觉和难度趣味性尚未完成。因此当时本地“完整验收关卡”仍按旧 27 关统计，不把 31 关说成完整交付。此原生测试是象棋专属切片；六棋种共享节点未冻结，不单独提交或推送。

后续 Debug APK 已使用正式解析器与 Native/ViewModel 打开四张新棋盘，并完成一关实际人机终局；该后续证据见 [Debug 实装视觉核验](2026-10-03-debug-visual-check.md)。本节原测试结论不追溯改写为 App AndroidTest 已通过。
