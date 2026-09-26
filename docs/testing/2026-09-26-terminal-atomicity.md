# 中国象棋终局原子性与中断恢复验收

日期：2026-09-26。环境：Pixel_7_Pro Android 17 x86_64 AVD、真实 Room/SQLite、Native/Pikafish/NNUE、指定 NDK 30、JDK 17。

## 修复与范围

十一种正式象棋对局统一把活动终局、棋谱、普通人机成绩及适用的残局奖励放入同一事务。
失败保留原待提交检查点，禁止当前对局重开/续局并提供重试；成功后才刷新统计和目录。
重复提交保留原结算时间及手动收藏状态，冲突结果拒绝覆盖。设计见 `../development/chinese-chess-terminal-commit.md`。

冷启动回归还发现并修复了前台服务“请求启动后、通知发布前被取消”的真实崩溃：
恢复/保存阶段不发起新服务；独立观察启动资格，已发出的请求先发布通知再处理撤销。

## 最终结果

| 范围 | 结果 | 证据内容 |
| --- | --- | --- |
| App JVM 全量 | 235/235 | 40 类；终局失败/原检查点重试、自动续局保护及恢复/保存阶段启动资格 |
| CompletedGameSessionRepositoryInstrumentedTest | 4/4 | 最后写入失败回滚棋谱/成绩/奖励、重复提交保留时间/收藏、冲突拒绝 |
| ChineseChessTerminalCommitNavigationTest | 1/1 | 正式导航注入 SQL 失败，返回/设置/重开禁用，重试按钮提交完整终局 |
| TutorialEndgameNavigationTest | 1/1 | 原子提交后的真实残局首通、奖励与教程返回链路 |
| AppNavigationScreenTest | 22/22 | 既有首页与模式导航回归 |
| ChineseChessBackgroundServiceTest | 4/4 | 通知/熄屏、令牌替换、备份隔离、同主线程请求后立即撤销服务 |
| 跨进程最终 verify | 4/4 | 四组独立三阶段验收，详见下表 |
| Lint | 0 错误、3 警告 | 既有依赖版本提醒 |
| APK 与发布资产门禁 | 通过 | 两个 64 位 ABI、法律文本、NNUE、正式音效与发布内容 |

普通设备专项合计 32 项，各类单独执行并核对 XML；跨进程另有 4 项最终 JUnit 验证。
刻意终止的 prepare/resume 阶段不算 JUnit 通过。未重复未修改的 C++/JNI、Room 迁移和所有历史设备用例。

## 实际进程中断证据

本轮完整脚本 runId：`36c1d12610ac45adb1680177a3539705`，原始日志和收据位于本地 `.build/process-restart/<runId>/`。

| 场景 | prepare → resume → verify PID | 关键结果 |
| --- | --- | --- |
| auto | 19809 → 19998 → 20184 | 2 → 3 → 3 步，暂停/1.75 倍速度/双方棋钟保持，暂停冷启动无前台服务崩溃 |
| human | 20402 → 20556 → 20726 | 同一两步棋局，关闭期间棋钟扣减；认输后记录 1、成绩 1，再启动不重复 |
| timed | 20898 → 21122 → 21271 | 关闭至少 12 秒后十秒挑战判负，记录 1、成绩 0，再启动保持原终局 |
| transaction | 21465 → 21594 → 21717 | 未提交事务中 SIGKILL；新进程看到原非终局存档、记录/成绩均 0；完整重试后再启动记录 1、成绩 1 |

`transaction` 在外层 Room 事务内调用正式提交函数并确认写入，然后发布检查点收据、保持事务未提交，
由主机结束进程。首次收据中的记录/成绩是该事务内部可见数据，不代表持久化成功；新进程先断言全部回滚，
才重新提交并发出 resume 收据。此方案不需要在正式仓储中加入测试故障钩子。

另以 SQLite BEFORE INSERT 触发器使最后的活动存档写入失败，验证前面已经执行的棋谱、成绩和残局奖励全部回滚；
去掉触发器后成功重试，首通奖励只有一份。使用真实初始状态/残局主变验证，不依赖空实现。

## 发现与修正

- DAO 新增按 ID 查询后，旧单元测试替身缺少方法，首次编译失败；补齐替身后全量通过。
- 首轮冷启动 auto/verify 真实触发 `ForegroundServiceDidNotStartInTimeException`，系统日志指出在等待前台通知时停止服务。
  修复请求撤销顺序，新增在同一主线程区间请求服务并释放租约的确定性设备回归。
- 初版延迟启动仅检查恢复/保存标志，但观察源只包含聚合“忙碌”，恢复切换到 AI 时可能不触发更新，服务回归超时。
  将 `canStartService` 加入可观察需求数据后，最终服务 4/4、完整冷启动四组均通过；未通过放宽超时或删除断言规避。

## 复现与剩余边界

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.data.CompletedGameSessionRepositoryInstrumentedTest' --no-configuration-cache --console=plain
.\tools\android\test-process-restart.ps1 -AdbPath 'E:\Backend_Env\SDK\platform-tools\adb.exe' -DeviceSerial emulator-5554 -AllowEmulatorDataReset
```

其他设备类按表中类名逐一运行；跨进程脚本不可与 connected 测试并发，后者会卸载 APK。
脚本只清空指定模拟器的本项目测试数据（不可恢复），不处理真机或其他应用。

本节点证明数据库事务失败与指定未提交窗口的突发进程终止保持一致，不宣称覆盖每条 SQLite 指令、
设备断电/存储硬件损坏或所有厂商回收策略。下一步推进长局/自动续局稳定性和性能；真机音效、触控与功耗仍待验收。
自然限着口径、AI 棋力梯度、内容规模及其他棋种仍按总进度推进；没有改变 Pikafish/NNUE 许可或宣称全项目完成。
