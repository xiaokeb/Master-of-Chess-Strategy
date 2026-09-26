# 中国象棋后台恢复隔离验收

日期：2026-09-26。环境：Pixel_7_Pro Android 17 x86_64 AVD、真实 Native/Pikafish/NNUE、JDK 17、指定 NDK 30。

## 修复范围

- 备份状态改为 Compose 可观察状态，处理进度与成功/失败反馈无需依靠其他页面事件刷新。
- 确认恢复后先捕获旧对局及六类应用数据模型的在途任务，关闭旧引擎/后台租约，取消任务并等待收尾，之后才进入数据库替换事务。
- 页面退出动画不再承担写入隔离；处理中使用不可点击外部关闭、不可返回关闭的模态提示，阻止新增设置写入和导航操作。
- 完成或失败后显式重新读取设置、上次选择、教程、统计、棋谱和残局进度；不使用会保留旧 ViewModel 的 Activity.recreate 代替数据刷新。
- 失败不替换数据库；已退出的旧对局不自动重启，可从原有一致存档重新进入。

## 结果

| 检查 | 结果 | 覆盖 |
| --- | --- | --- |
| App JVM 全量 | 232/232 | 40 类，0 失败/错误/跳过；新增可观察状态、恢复等待与回调顺序、失败刷新、不可取消写入收尾 |
| 后台服务设备类 | 3/3 | 通知/短时熄屏、旧令牌隔离、真实引擎运行中的备份替换及设置界面刷新 |
| 正式 Activity 设备类 | 2/2 | 重建/通知返回、强制 Doze 前后同一会话与合法检查点 |
| 备份仓储设备类 | 2/2 | 七表原子替换、损坏备份拒绝且保留原数据 |
| Lint | 0 错误、3 警告 | 既有依赖版本提醒 |
| Debug APK 与资产门禁 | 通过 | 两个正式 ABI；法律文件、NNUE、正式音效及发布内容校验 |

设备类分别执行并核对 XML 类名、数量及跳过数，共 7 项。未重复未修改的 C++/JNI、迁移与全部历史设备测试。

## 关键证据与边界

1. 写入屏障的确定性测试让两个旧任务在取消后仍执行 100 ms 收尾，确认全部取消、全部收尾之后才开始恢复；数据模型仍可加载新数据。
2. 设备上导出暂停检查点，继续真实 AI 行棋后恢复旧备份。旧引擎失效、服务/唤醒锁释放，旧通知不能恢复写入；等待后存档仍与备份一致。新模型可加载同一棋局和步数，保留暂停状态。
3. 设置模型保留不重建，实际 Compose 文本从旧设置刷新为备份设置，并显示恢复成功。本次未自动点击系统文件选择器，文档流由测试直接提供；不宣称覆盖所有文档提供商。
4. Doze 用例仅允许 ranchu/goldfish 模拟器：拔除模拟电源、熄屏、force-idle，断言命令成功且 PowerManager 报告休眠；3.5 秒后检查原会话与 Native 可恢复状态。finally 中 unforce、battery reset、唤醒并解锁。
5. 退出 Doze 后正式 Activity 仍使用原 ViewModel，棋局有效且可继续行棋；最后退出对局并确认后台资源释放。执行后设备 deep 状态为 ACTIVE，电池服务无模拟值冻结提示。
6. 不要求 Doze 中持续走子。AVD 中 instrumentation 存活与短时强制状态不等于硬件真实深睡、无限后台运行或功耗合格。平台边界参见 [Android Doze 文档](https://developer.android.com/training/monitoring-device-state/doze-standby)。

首次 JVM 编译发现测试直接构造抽象 ViewModel，改为匿名测试子类后通过；没有跳过该用例。

## 复现

```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessBackgroundServiceTest' --no-configuration-cache --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessBackgroundNavigationTest' :app:lintDebug --no-configuration-cache --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.data.LocalDataBackupRepositoryInstrumentedTest' --no-configuration-cache --console=plain
```

下一步：真实进程终止/冷启动恢复、终局幂等结算和长时稳定性。真机功耗、音效试听与触控体验仍需实体设备验收；本节点不修改规则口径、AI 棋力参数或 Pikafish/NNUE 法律边界。
