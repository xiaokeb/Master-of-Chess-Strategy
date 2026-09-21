# 2026-09-21 中国象棋棋力评测测试记录

## 自动化门禁

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（28 个执行、182 个保持最新）。36 份 XML 测试套件共 167 个 JVM 测试，0 失败、0 错误、0 跳过。App Lint 为 0 错误、1 条锁定 Kotlin 版本升级提醒。Debug 主 APK、App 测试 APK 与 engine-native 测试 APK 均构建成功。

Debug APK 为 100,929,906 字节，SHA-256：

    a90c6e31ad45095f3306f9f88504bccaa17c110414c7cf971ddff788826350cd

## 覆盖范围

- 五局系列的胜升、负降、和棋保持及简单/大师难度边界。
- 1200 初始分、分难度增减、400/2400 上下界和胜和负计数一致性。
- 进行中、终局待下一局和五局完成三种规范阶段往返；前导零和非法状态被拒绝。
- 正式 AI 完成首局后按实际中等难度生成棋谱，再把下一局切换为困难；专项结果不写标准排位。
- 旧导航参数重建 ViewModel 时从 Room 恢复最新对手、评分和已完成局数。
- Room 与 MOCS-BACKUP v1 校验有效难度和规范系列状态，并往返活动评测与快速进入选择。
- Compose 测试覆盖扩展目录到评测设置页及终局专用下一局按钮；Debug Preview 覆盖设置页和正式对局页。

## 资源、许可与设备边界

`verifyGameSounds`、`verifyBundledLegalDocuments` 与 `verifyPikafishNetwork` 均通过。评测复用现有四级 AI、正式音效和 Pikafish/NNUE 许可边界，没有新增第三方资源。

本节点未修改 C++。Gradle 继续使用实际已配置的 `E:\Backend_Env\SDK`、NDK 30 LLVM 与双 64 位 ABI，未调用 MinGW。当前没有已连接 Android 设备或 AVD，因此 Compose、Room、JNI 与 Pikafish 仪器测试只完成编译，未宣称设备运行；交互、音效和 AI 性能仍需设备验收。
