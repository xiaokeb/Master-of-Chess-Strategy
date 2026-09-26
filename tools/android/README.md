# Android 运行稳定性验收

## 连续完整对局

`test-continuous-play.ps1` 使用正式 MainActivity、导航、Room、音效组件和真实 Pikafish，
从标准初始局面连续完成三局简单档自动演局。开启正式续局设置，上限三局，观看速度 4 倍，
不限棋钟；不使用认输、模拟终局、换引擎或缩短时限让测试提前结束。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
.\tools\android\test-continuous-play.ps1 -AdbPath 'E:\Backend_Env\SDK\platform-tools\adb.exe' -DeviceSerial emulator-5554 -AllowEmulatorDataReset
```

与下方冷启动脚本相同，执行前须确认该模拟器的本项目数据可丢弃；脚本安装 APK 并清空这些数据，
不可恢复。不能与 Gradle connected 测试或冷启动脚本同时运行。普通设备测试默认跳过该类，
只有显式 `continuousHarness=true` 和 runId 才启用。

覆盖真实暂停按钮及存档稳定、90 秒熄屏跨唤醒锁检查周期、返回相同 ViewModel、三次自然终局
原子存档、续局上限、无排位污染和退出资源释放。每份终局棋谱由原生规则层恢复并逐步撤回到
标准初始局面，核对实际结果与历史长度。20 分钟对局等待超时算失败，不生成和棋记录。

证据位于 `.build/continuous-play/<runId>/`；设备 `files/continuous-play.json` 持续更新阶段与采样。
主机同时要求最终 `OK (1 test)` 和同 runId 的 `passed` 报告，失败保留棋局，不自动重跑掩盖故障。
每约 15 秒记录 PSS、原生已分配内存、线程、文件描述符及服务/唤醒锁状态；这是包含调试器材的
AVD 观测，不设置任意“内存无泄漏”阈值，也不证明 Release 性能、真机耗电或音效试听合格。

## 跨进程恢复

`test-process-restart.ps1` 协调真实进程终止和重新启动，不把 Activity 重建当作进程死亡。
仅允许 `emulator-*` 且 `ro.kernel.qemu=1` 的模拟器；Android 测试另校验 ranchu/goldfish。

## 执行

先构建两个 APK，再运行脚本。脚本会安装 APK，并在每组场景前清空该模拟器中
`com.masterofchessstrategy` 的全部测试数据；这些数据不会自动备份，清空后不可恢复。
不要在保存个人棋局的模拟器上使用，也不要同时运行会卸载 APK 的 Gradle connected 测试。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
.\tools\android\test-process-restart.ps1 -AdbPath 'E:\Backend_Env\SDK\platform-tools\adb.exe' -DeviceSerial emulator-5554 -AllowEmulatorDataReset
```

可用 `-Scenarios timed` 单独执行未通过的场景。默认四组：

- `auto`：真实 Pikafish 走子后 SIGKILL，重新打开续弈；暂停并设置 1.75 倍速度后再次强制停止，核对暂停、棋钟和速度。
- `human`：人类走子、真实 AI 应答后 SIGKILL；恢复扣除关闭期间的棋钟时间，认输后再强制停止，核对棋谱和成绩只生成一次。
- `timed`：十秒挑战尚未超时时强制停止，确认至少 12 秒无应用进程，再打开应判超时负；第二次启动不重复棋谱，也不产生排位成绩。
- `transaction`：在外层真实 Room 事务中调用正式终局提交，写入未提交收据后保持事务打开并 SIGKILL；新进程必须只读到此前活动存档，棋谱/成绩均为空；完整重试后再启动，结算只保留一份。该组使用合法初始棋局上的认输终局夹具，检验存储原子性，不作为棋力样本。

每组包含 prepare、resume、verify 三个独立进程；前两阶段在检查点落盘后刻意被主机终止，
其 `Process crashed` 是预期结果，**不能记作两项 JUnit 通过**。最终 verify 必须有有效收据及
`OK (1 test)`；报告同时保存 PID、棋局 ID、步数、完整原生状态和不可变结算时间。
默认普通设备测试不启用该类，必须通过 `restartHarness=true` 和完整参数显式运行。

输出位于 `.build/process-restart/<runId>/`。脚本失败会停止本项目应用，保留日志和当前存档；
不会自动重新建局掩盖失败。ADB 中断时同时检查 `.log`、`.stderr.log` 与实际设备/进程状态。

## 保留检查点补验

仅当确认原 verify 进程没有启动或已经退出、ADB 恢复，且应用数据未被清空/替换时，
可以使用原 runId 和 scenario 显式补验最后阶段。不得在原进程仍存活时重复启动。

```powershell
& 'E:\Backend_Env\SDK\platform-tools\adb.exe' -s emulator-5554 shell am instrument -w -r `
  -e class com.masterofchessstrategy.ui.ChineseChessProcessRestartTest `
  -e restartHarness true -e restartRunId '<原32位runId>' `
  -e restartScenario timed -e restartPhase verify `
  com.masterofchessstrategy.test/androidx.test.runner.AndroidJUnitRunner
```

测试会读取原收据、检查不同 PID，并在创建正式 Activity 前验证持久化棋局历史。
补验日志应单独保留，不覆盖最初失败日志。SIGKILL 是受控突发终止，不等于已经覆盖
所有低内存回收策略、每一个 SQLite 指令中断时刻、设备重启或实体设备长期功耗。
