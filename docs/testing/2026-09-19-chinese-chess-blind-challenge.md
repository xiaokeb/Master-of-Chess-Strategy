# 2026-09-19 中国象棋盲棋模式测试记录

## 自动化门禁

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（22 个执行、188 个保持最新）。34 份 XML 测试套件共 156 个 JVM 测试，0 失败、0 错误、0 跳过。App Lint 为 0 错误、1 条锁定 Kotlin 版本升级提醒。Debug 主 APK、App 测试 APK 与 engine-native 测试 APK 均构建成功。

Debug APK 为 100,923,658 字节，SHA-256：

    04000734eaaadaaec0987f1dab53bc2aaa77fdd5158ae1483a1e05023780ec7f

## 覆盖范围

- 新对局只能选择已解锁难度；锁定难度的旧活动对局不会作为继续入口暴露。
- 盲棋对局使用正式 AI、保存活动快照、生成专项棋谱，且不写标准排位结果。
- 全部 AI 难度的盲棋辅助策略均为 0 次悔棋、0 次提示；棋盘不绘制合法落点或提示标记。
- Room 要求有效难度和空会话变体，并拒绝缺失难度或附带隐藏状态的快照。
- MOCS-BACKUP v1 可同时往返盲棋活动对局与上次选择。
- 稳定难度路由、首页快速进入、扩展目录到盲棋设置页及对局禁用辅助按钮均有自动化覆盖。
- Debug Preview 编译覆盖盲棋设置页和实际背面棋子对局页。

## 资源与设备边界

`verifyGameSounds`、`verifyBundledLegalDocuments` 与 `verifyPikafishNetwork` 均通过。盲棋复用已校验的落子、吃子、胜、负、和音效及现有 Pikafish 法律边界，没有新增第三方资源。

本节点未修改 C++。Gradle 继续使用实际已配置的 `E:\Backend_Env\SDK`、NDK 30 LLVM 与双 64 位 ABI，未调用 MinGW。当前没有已连接 Android 设备或 AVD，因此 Compose、Room、JNI 与 Pikafish 仪器测试只完成编译，未宣称设备运行；背面棋子对比度、触控和音效仍需设备验收。
