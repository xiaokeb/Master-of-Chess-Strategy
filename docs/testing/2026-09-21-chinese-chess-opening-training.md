# 2026-09-21 中国象棋定式学习测试记录

## 自动化门禁

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（27 个执行、183 个保持最新）。37 份 XML 测试套件共 175 个 JVM 测试，0 失败、0 错误、0 跳过。App Lint 为 0 错误、1 条锁定 Kotlin 版本升级提醒。Debug 主 APK、App 测试 APK 与 engine-native 测试 APK 均构建成功。

Debug APK 为 100,933,465 字节，SHA-256：

    55264e0c906e17e12a9b98a2c1890e1cb83df504a09f0930282184ff061c925f

## 覆盖范围

- 三条项目自编种子线路具有唯一稳定 ID、四步讲解和内容版本。
- 训练 ViewModel 从标准局面逐步应用走法，生成初始帧与每步帧；任一步拒绝时整条线路失败关闭。
- 自动演局端点使用防御复制，只允许当前已解锁难度。
- 指定开局双 AI 对局复用正式自动演局，并在终局继续时通过恢复原始 MOCX 状态回到同一开局，而非标准局面。
- Room 与 MOCS-BACKUP v1 要求规范小写十六进制原始开局，并同时验证当前和原始引擎状态。
- 稳定路由、首页快速进入、扩展目录到训练页、自动演局按钮与第 16 个 Preview 均已编译覆盖。
- 设备端 `ChineseChessOpeningLibraryInstrumentedTest` 会用 Native 引擎逐着验证所有内置线路。

## 许可与设备边界

线路坐标和讲解为项目自编内容，按项目 GPL-3.0-or-later 发布；没有新增第三方棋谱文本。`verifyGameSounds`、`verifyBundledLegalDocuments` 与 `verifyPikafishNetwork` 均通过。

本节点未修改 C++。Gradle 继续使用实际已配置的 `E:\Backend_Env\SDK`、NDK 30 LLVM 与双 64 位 ABI，未调用 MinGW。当前没有已连接 Android 设备或 AVD，因此新增 Native 开局线路测试以及现有 Compose、Room、JNI、Pikafish 仪器测试只完成编译，未宣称设备运行。
