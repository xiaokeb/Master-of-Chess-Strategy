# 2026-09-19 中国象棋自由摆局测试记录

## 自动化验证

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（25 个执行、185 个保持最新）。JVM 共 128 个测试，0 失败、0 错误、0 跳过；App Lint 0 错误，仅保留项目锁定 Kotlin 版本的升级提醒。Debug 主 APK、App 测试 APK 与 engine-native 测试 APK 均构建成功。

指定 NDK 验证：

    E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe --build .build/native-android

结果：`ninja: no work to do.`，现有 Android ARM64 原生目标保持最新。构建继续使用 NDK 30 LLVM，未调用 MinGW。

## 覆盖范围

- 标准局面通过编码与引擎校验；空棋盘说明缺少双方将帅；宫外士仕被拒绝。
- 已保存自由摆局能够恢复不可变原始局面和难度。
- 自由摆局导航参数规范往返，首页快速进入路由到摆局页。
- Room 只接受规范自由摆局会话变体；备份编解码覆盖自由摆局活动会话。
- 人机模式完成 AI 应答并保存会话变体；终局生成棋谱但不累计排位胜场。
- Compose 导航覆盖中国象棋模式、扩展目录和摆局页面，棋盘与开始按钮具备稳定测试标记。

## 设备边界

当前没有已连接 Android 设备或 AVD。Compose、Room、JNI 与 Pikafish 仪器测试已经编译进测试 APK，但没有宣称在设备运行；正式音效也仅完成资源、哈希、加载与事件链路验证，仍需设备试听。
