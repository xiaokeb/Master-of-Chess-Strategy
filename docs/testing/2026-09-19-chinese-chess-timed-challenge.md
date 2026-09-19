# 2026-09-19 中国象棋限时挑战测试记录

## 自动化门禁

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（25 个执行、185 个保持最新）。32 份 XML 测试套件共 138 个 JVM 测试，0 失败、0 错误、0 跳过。App Lint 为 0 错误、1 条锁定 Kotlin 版本升级提醒。Debug 主 APK、App 测试 APK 与 engine-native 测试 APK 均构建成功。

Debug APK 为 100,914,782 字节，SHA-256：

    982c929fffd26d9d0805ca15db63c42e1bba52277f82f5793cf814d042859f29

## 覆盖范围

- 10/30/60 秒会话变体规范往返，前导零和未定义档位被拒绝。
- 选择状态只允许已解锁难度，并只暴露模式、难度、时钟均兼容的活动挑战。
- 正式 AI 完成双方走子后，玩家新回合恢复完整时限；超时判负、播放失败音效、生成棋谱且不写标准排位结果。
- Room 拒绝超过会话档位的剩余时间和非规范变体。
- MOCS-BACKUP v1 可往返限时活动会话，并在编码前拒绝越界棋钟。
- 导航路由携带稳定难度码和秒数；首页快速进入回到设置页；Compose 测试覆盖扩展目录到限时选择页。

## 环境与设备边界

本节点未修改 C++。Gradle 继续使用实际已配置的 `E:\Backend_Env\SDK`、NDK 30 LLVM 与双 64 位 ABI，未调用 MinGW。当前没有已连接 Android 设备或 AVD，因此 Compose、Room、JNI 与 Pikafish 仪器测试只完成编译，未宣称设备运行；音效事件与资源链路已自动验证，仍需设备试听。
