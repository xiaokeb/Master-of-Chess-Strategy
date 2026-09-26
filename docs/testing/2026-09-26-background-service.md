# 2026-09-26 中国象棋后台服务核心验收

## 环境与范围

Windows、JDK 17、既有 NDK 30.0.16138531，Pixel_7_Pro Android 17 x86_64 AVD。
直接在项目开发，没有 worktree、MinGW、新依赖或第三方资产。通知小图标为项目自编矢量。

| 检查 | 结果 | 实际覆盖 |
| --- | --- | --- |
| App JVM 全量 | 228/228，39 类，0 失败/错误/跳过 | 后台调度/租约/停止/唤醒预算专项共 15 项，包含上一阶段 8 项 |
| 服务设备专项 | 2/2 | 通知 app-op 拒绝时不启动；允许后实际 FGS；真实熄屏仍提交至少三着、同一 sessionId、Native 状态可恢复；通知停止后暂停/存档且实际服务与唤醒锁释放；旧通知不能停止替换后的会话 |
| 正式页面导航 | 1/1 | 正式 MainActivity、真实 Room/导航/Pikafish；锁屏导致页面重建，通知返回仍是同一 ViewModel 与会话；界面停止、状态文案和返回释放验证 |
| 让子导航回归 | 3/3 | 跨设置切换、执黑应答、整回合悔棋、无排位结算、新局隔离与备份往返 |
| Lint | 0 错误、3 条既有版本提醒 | 应用上下文单例显式说明生命周期；没有新增未处理告警 |
| Debug APK 与资产门禁 | 通过 | GPL 法律副本、NNUE 哈希、原创音效及发布内容来源均通过 |
| APK 权限 | 通过 | aapt 核对四项后台/通知相关权限，无 INTERNET 或电池优化豁免权限 |

设备专项合计 6 项，每类独立执行并核对 XML，不把逗号过滤器或 Gradle 成功字样当作覆盖证据。
未重复未修改的 C++/JNI、Room 迁移或所有历史设备类。

## 执行命令

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessBackgroundServiceTest' --no-configuration-cache --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessBackgroundNavigationTest' --no-configuration-cache --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessHandicapNavigationTest' --no-configuration-cache --console=plain
```

## 失败记录与修正

- 首轮服务测试在唤醒后等待页面可见超时。系统日志确认测试用裸 ComponentActivity 因锁屏旋转重建，
  不会自动安装之前的测试 setContent；修正夹具只重挂 UI，并断言仍取得原 ViewModel。
  随后另增正式 MainActivity 测试，不以夹具行为替代正式页面验收。
- 正式导航脚本先后在进入对局、返回难度页的动画期间超时。超时诊断显示前者尚未创建会话，后者
  已停止服务并截图、但导航项尚未释放。改用 Compose 感知的等待推进测试动画，未修改生产导航绕过问题。
- 截图前增加状态文字断言与渲染等待，避免把暂停前的旧帧作为停止状态截图。
- 审查补充了真实服务资源状态回报、恢复/保存中延迟停止以及无进展唤醒预算；最终设备专项基于修正后的实现执行。

## 运行边界

- 服务不创建引擎，也不自动恢复新棋局；它借用导航 ViewModel 的有界生命周期租约。
- 通知停止不认输，自动演局暂停；恢复/保存中的请求在安全点执行，人机棋钟不因此重置。
- 后台 CPU 唤醒锁有 60 秒无进展预算；只在新着/新局后补充预算，30 秒检查不能让卡住的操作无限续租。
- 服务不导出，停止 PendingIntent 不可变且绑定令牌。Android 启动拒绝不会形成重试循环；无开机启动或静默保活重启。
- 只验证短时熄屏（确认屏幕非交互、后台新着和锁释放），没有强制 Doze，也没有宣称真机长期功耗通过。
- 冷启动、系统杀进程、备份恢复并发及长时稳定性仍是下一阶段；不能据此宣称后台全部边界或象棋全部完成。
- Pikafish/NNUE 许可不变；新 Android 服务类型声明不等于已获得应用商店审核批准。

实际界面截图：本地 `.build/background-controls.png`，不作为发布资产进入仓库。
