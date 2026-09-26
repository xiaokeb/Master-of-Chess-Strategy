# 2026-09-26 后台调度基础验收

## 范围

Windows、JDK 17、既有 NDK 30.0.16138531；Pixel_7_Pro Android 17 x86_64 AVD。
本阶段只修改 Kotlin 调度及 Compose 生命周期联动，不新增系统权限、服务、第三方库或资产。

| 检查 | 结果 | 证据范围 |
| --- | --- | --- |
| App JVM 全量 | 221/221，39 类，0 失败/错误/跳过 | 包含新增后台专项 8 项 |
| 后台调度 JVM | 8/8 | 前后台刷新、截止点、终局无空转、停调度补算、自动演局节奏/原难度、手动暂停、重复可见性事件不重启 AI、释放后无残留任务 |
| 实际生命周期 | 2/2 | Activity 从 RESUMED 到 CREATED 后真实 Pikafish 继续至少三着，保持同一 sessionId 且 Native 可恢复；返回后保持当前状态，暂停后不走子/扣时且保留 1.75× 设置 |
| AI 先手导航 | 1/1 | 真实设置切换、执黑应答、整回合悔棋、继续身份与重开规则回归 |
| Lint | 0 错误、3 条既有版本提醒 | 无新增告警 |
| Debug APK/资产门禁 | 通过 | 许可副本、NNUE 哈希、正式音效及发布内容来源检查通过 |

## 执行命令

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessRuntimeLifecycleTest' --no-configuration-cache --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessAiFirstNavigationTest' --no-configuration-cache --console=plain
```

设备类分别执行并核对 XML 类名/数量，设备专项合计 3 项，不与 JVM 重复计数。
未重复未变更的 C++/JNI、迁移和其他设备全量。

首轮新增测试错误地使用产品不支持的 1 分钟时长，导致引擎初始化被拒绝，
4 项断言失败。修正夹具为合法 5 分钟，并在所有夹具入口断言引擎可用后通过；
未放宽生产时长规则。随后增加暂停棋钟无空转用例，最终为上表 8 项。

## 调度边界

- 前台默认 250 ms；后台至少 1000 ms，但剩余时间少于间隔时在截止点唤醒判负。
- 后台自动演局的观看等待至少 2000 ms；不改变搜索难度、节点/时间预算或用户存储的速度。
- 已开始的单次观看等待完成后才使用新节奏，不取消并重复发起 AI。
- 返回可见界面立即同步墙上时钟，切换可见性不创建第二引擎或新会话。
- 终局、手动暂停、关闭引擎时释放棋钟任务；显式重开和恢复自动演局会重新建立需要的计时任务。

## 尚未证明

这不是完整后台挂机验收。测试未强制进入 Doze，也未证明系统杀进程后继续计算；
前台服务、通知停止/权限边界、受控唤醒锁与长时间熄屏仍待实现。
本轮减少调度频率，但没有测量硬件频率、真实耗电或真机长期性能。
Pikafish/NNUE 授权边界不变，不宣称新增商业许可。
