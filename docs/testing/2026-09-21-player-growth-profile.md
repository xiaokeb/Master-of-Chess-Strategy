# 2026-09-21 本地成长体系与个人中心测试记录

## 自动化门禁

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（41 个执行、169 个保持最新）。
38 份 XML 测试套件共 181 个 JVM 测试，0 失败、0 错误、0 跳过。
App Lint 为 0 错误、1 条锁定 Kotlin 版本升级提醒。Debug 主 APK、App
测试 APK 与 engine-native 测试 APK 均构建成功。

Debug APK 为 100,938,509 字节，SHA-256：

    45a7f0850785a807a3951772bd9b8291557ce09b7484ea55c8f95d1cd0dda86f

## 覆盖范围

- 三条成长路径取最高段位，残局奖励参与星级与积分但不伪造排位胜场。
- 分棋种胜和负来自幂等终局账本，六枚成就从现有账本和残局进度实时推导。
- 六套形象按段位解锁，设置层拒绝 0–5 之外的代码，保存失败沿用整行回滚。
- Room 9→10 迁移测试验证既有设置不变且默认选择首套人物。
- MOCS-BACKUP v1 往返保留人物选择；解码继续接受没有人物字段的早期 v1
  SETTINGS 记录。
- Compose 仪器测试新增首页到个人中心的正式导航；第 17 个 Debug Preview
  编译通过。
- verifyGameSounds、verifyBundledLegalDocuments 与
  verifyPikafishNetwork 均通过，未改变 Pikafish 与 NNUE 法律边界。

## 设备与工具链边界

ADB 当前没有已连接设备或 AVD，因此新增 Compose 导航与 Room 迁移仪器测试
仅完成编译，未宣称设备运行。本节点没有修改 C++；Gradle 仍只使用实际配置
的 E:\Backend_Env\SDK、NDK 30 LLVM 和双 64 位 ABI，未调用 MinGW。
