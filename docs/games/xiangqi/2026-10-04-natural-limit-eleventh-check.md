# 自然限着：第十一次将军的真实历史边界

日期：2026-10-04。补齐自然限着计划中尚未通过真实历史验证的第 10/11 次将军边界；不修改生产规则、JNI 契约、NNUE 或正式关卡。采用已批准的 2020 申请审核口径，第 22.2 条审核申请方的将军次数，不能把对方的次数扣到本方。[规则依据](https://cnchess.net/rules/ChapterFiveSection22.html)。

## 夹具与结果

从精简合法 FEN `4k1r2/9/9/9/5P3/9/R8/9/9/5K3 w - - 0 1` 开始，车在 6、7、8、9、5 五个横线各作两次将军，间隔改变横线和对方车的位置，避免进入三次循环；随后作第 11 次将军，再进入长度 12 和 10 的静着路线。红方及旋转 180°、交换阵营的黑方夹具均由正式引擎逐着执行，不伪造将军计数或历史记录。

| 核验点 | 实际结果 |
| --- | --- |
| 28 半回合，申请方恰有 10 次将军 | 有效着数仍为 28 |
| 30 半回合，申请方恰有 11 次将军 | 有效着数为 28 |
| 120 半回合，申请方有 11 次将军 | 有效 118，不具资格；申请只记首次误申请，对局继续 |
| 同一历史，对方没有将军 | 对方有效着数 120，不继承申请方的扣着；测试仅查询，不越过行棋方申请 |
| 保存首次误申请后经新引擎恢复 | 字节一致，审核字段一致 |
| 再完成一个无吃子回合 | 原始 122、有效 120，合法申请判和，不因先前一次误申请而判负 |
| 判和后悔棋、重走最后一着 | 恢复有效 119 的进行中状态；重走后再次合法申请判和 |
| 120 半回合历史另行吃子 | 新阶段原始/记录着数及将军次数均清零；旧误申请不继承，新阶段申请仍为首次误申请 |

## 已运行证据

- NDK 30 LLVM 的 `mocs_engine_chinese_chess_test` 重编后，在 Pixel_7_Pro x86_64 AVD 运行退出 0；新增夹具分别输出 `claimant=red/black raw=122 effective=120 passed`。二进制上传后需重新 `chmod 755`；曾因未恢复执行位而得到 `Permission denied`，修正运行权限后执行通过，不是规则断言失败。
- 新增 C++ 测试同时以 NDK 30 ARM64 Android 24 编译器执行 `-std=c++17 -Wall -Wextra -Wpedantic -Werror -fsyntax-only`，退出 0；这是严格编译检查，不是 ARM64 设备运行。
- 正式 Kotlin→JNI→C++ 专项在同一 AVD 真实运行 **1/1**，该一个测试内部覆盖红、黑两组；JUnit XML `tests=1 failures=0 errors=0 skipped=0`，名称为 `eleventhCheckIsDeductedOnlyForTheClaimantAcrossJniRestore`。没有把仅编译或构建成功当作运行证据。
- 定向命令：

```powershell
& 'E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe' --build .build/native-x86_64 --target mocs_engine_chinese_chess_test -j 2
& 'E:\Backend_Env\SDK\platform-tools\adb.exe' -s emulator-5554 shell 'LD_LIBRARY_PATH=/data/local/tmp /data/local/tmp/mocs-xq-natural-check11-test'
.\gradlew.bat :engine-native:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.engine.NativeChineseChessNaturalLimitInstrumentedTest#eleventhCheckIsDeductedOnlyForTheClaimantAcrossJniRestore' --offline --max-workers=2 --console=plain
```

原生命令需先上传对应测试可执行文件和 `libmocs_engine_native.so` 到 `/data/local/tmp/` 并赋予测试执行位。当前源码 SHA-256：

- `engine-native/src/main/cpp/tests/chinese_chess_test.cpp`：`f56e35089419cfe67b8605f7a97b4bb1a00ff7b4a1cc1c99b2524f009579db1b`。
- `engine-native/src/androidTest/kotlin/com/masterofchessstrategy/engine/NativeChineseChessNaturalLimitInstrumentedTest.kt`：`86d14fc1d8563717cc0fd03801e3860d47c2c70ec2128bc8858a1c436caac583`。

## 边界与下一步

本轮是规则及 JNI 专项，无界面修改，不追加无关视觉或全量 App 构建。没有清空本应用数据；测试后模拟器返回系统首页，窗口释放。App AndroidTest 的国标麻将两处导入仍由该棋种对话修复，本专项使用独立 engine-native 测试包，不过滤或豁免失败的 App 测试。

反将、双方同时超额将军、阈值附近实际 AI 棋力及更多职业循环棋例仍待后续，不宣称自然限着全部边界完成。六棋种尚未冻结统一节点，未提交或推送。
