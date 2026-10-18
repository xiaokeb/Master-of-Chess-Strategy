# 自然限着：反将与双方超额将军

日期：2026-10-04。承接 [第 10/11 次将军真实历史专项](2026-10-04-natural-limit-eleventh-check.md)，本轮补两类不同边界，不重复该专项或全量 App 回归。依据已批准的 [2020 竞赛规则第 22.2 条](https://cnchess.net/rules/ChapterFiveSection22.html)，审核扣着只针对申请方；反将也是其行棋方实际给出的将军。

## 覆盖与实测

| 场景 | 真实行棋与预期 | 已核验证据 |
| --- | --- | --- |
| 双方各 11 次将军 | 70 半回合前缀全部由引擎走出；双方均为有效 68，不扣成 66 | NDK、JNI 红黑旋转对照通过 |
| 双方超额后的 120 半回合 | 两方各自有效 118、均未达限着；不是合并扣减后的 116 | NDK、JNI 通过 |
| 双方各自误申请 | 第一方在 120 半回合首次误申请；恢复后另一方在 121 半回合也只记自己的首次 | 保存/新引擎恢复后通过 |
| 122 半回合 | 双方各自有效 120；当前方正确申请判和，不被此前一次误申请判负 | NDK、JNI 通过 |
| 应将同时反将 | 车 `(0,6)→(4,6)` 将军，炮 `(5,2)→(4,2)` 挡住此将军并露出己方 `(5,0)` 的车反将 | 双方各 1 次将军、原始/有效均 2，通过 |
| 反将的恢复与悔棋 | 恢复保留反将；撤销第二着只清掉第二方那一次将军；重走重新计一次 | NDK、JNI 通过 |

两类夹具均另作旋转 180°、交换阵营的黑先对照。没有填造将军次数或历史；每着检查正式引擎接受且局面仍在进行中，排除途中长将/循环终局掩盖阈值验证。

首版反将夹具误用车作挡子，使初始局面已经将军，第一着被拒绝；实际故障定位后改成无炮架的炮。新夹具的挡将与露车反将均经真实规则层检查，不修改生产规则以迁就测试。

## 实际执行

- 新专属原生目标 `mocs_xiangqi_natural_limit_counter_check_test` 在 Pixel_7_Pro x86_64 AVD 输出两组 `both-checks ... passed` 和两组 `counter-check ... passed`，退出 0。检查用异常而非可被 `NDEBUG` 禁用的断言。
- 新 JNI 类 `com.masterofchessstrategy.engine.games.xiangqi.NativeXiangqiCounterCheckInstrumentedTest` 定向真实运行 **2/2**；JUnit XML 为 `tests=2 failures=0 errors=0 skipped=0`，每项内部覆盖红黑两组。双 ABI 生产库构建通过，但没有 ARM64 设备运行。
- C++ 专属测试经 NDK 30 ARM64 Android 24 `-std=c++17 -Wall -Wextra -Wpedantic -Werror -fsyntax-only` 退出 0。最终只统一数字坐标列表空格，语义未变。

```powershell
& 'E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe' --build .build/native-x86_64 --target mocs_xiangqi_natural_limit_counter_check_test -j 2
# 将该测试和 libmocs_engine_native.so 上传 /data/local/tmp，并赋予测试执行位后：
& 'E:\Backend_Env\SDK\platform-tools\adb.exe' -s emulator-5554 shell 'LD_LIBRARY_PATH=/data/local/tmp /data/local/tmp/mocs-xq-counter-check-test'
.\gradlew.bat :engine-native:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.engine.games.xiangqi.NativeXiangqiCounterCheckInstrumentedTest' --offline --max-workers=2 --console=plain
```

最终源码 SHA-256：

- `engine-native/src/main/cpp/games/xiangqi/natural_limit_counter_check_test.cpp`：`9ee3fccb99c6baca9e2df5e1f93136568329e2192525c5a022b6e0e31e9d501c`。
- `engine-native/src/androidTest/kotlin/com/masterofchessstrategy/engine/games/xiangqi/NativeXiangqiCounterCheckInstrumentedTest.kt`：`63860efbd26424d891ada8da8bfa77ad80ec29f391d3c7637ec72463c3896c13`。

## 边界和交接

只新增象棋专属测试，以及统筹串行登记的测试 CMake 目标；没有修改生产源列表、规则实现、JNI 契约、第三方引擎、权重或关卡。没有清空 App 数据；模拟器回到系统首页，Gradle/NDK/AVD 窗口释放。

App 仪器测试仍由国标麻将对话修复其两处无效导入，不因独立 engine-native 专项通过而豁免。带反将历史的真实对局 UI/Room、阈值附近 AI 棋力、复杂职业棋例和实体设备仍待验收；不宣称自然限着全部完成。下一开发项转向连续将军成杀与其他历史判定缺口，避免继续重复已通过计数夹具。六棋种节点尚未冻结，未提交或推送。
