# 2026-09-19 中国象棋连胜模式测试记录

## 自动化门禁

执行：

    .\gradlew.bat testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest --stacktrace

结果：构建成功，共 210 个 Gradle 任务（23 个执行、187 个保持最新）。33 份 XML 测试套件共 147 个 JVM 测试，0 失败、0 错误、0 跳过。App Lint 为 0 错误、1 条锁定 Kotlin 版本升级提醒。Debug 主 APK、App 测试 APK与 engine-native 测试 APK 均构建成功。

Debug APK 为 100,920,494 字节，SHA-256：

    8a9c9f589f4c33d5892e635304d43ce2ee55b1cf87fa04d08ed7056959d953cd

## 覆盖范围

- 连胜规范状态往返，前导零、未知难度和越界计数被拒绝。
- 3 胜升级、2 负降级、简单/大师边界、和棋中断、当前/最佳连胜均有单元测试。
- 终局先按实际对局难度生成棋谱，再更新下一局难度；专项终局不写标准排位结果。
- 下一局保留系列统计并重建标准局面；进程以旧路由重建时仍从 Room 恢复最新难度和连胜状态。
- Room 拒绝缺失难度和非规范变体；MOCS-BACKUP v1 往返活动系列与快速进入选择。
- 导航路由携带规范系列状态；首页快速进入回到设置页；Compose 测试覆盖扩展目录到连胜设置页及终局专用下一局按钮。

## 法律与设备边界

`verifyBundledLegalDocuments` 与 `verifyPikafishNetwork` 均通过。连胜模式只复用现有直接链接 Pikafish 能力，不改变已归档的上游源码、GPL-3.0-or-later 对应源码义务或 NNUE 非商业限制。

本节点未修改 C++。Gradle 继续使用实际已配置的 `E:\Backend_Env\SDK`、NDK 30 LLVM 与双 64 位 ABI，未调用 MinGW。当前没有已连接 Android 设备或 AVD，因此 Compose、Room、JNI 与 Pikafish 仪器测试只完成编译，未宣称设备运行；音效事件与资源链路已自动验证，仍需设备试听。
