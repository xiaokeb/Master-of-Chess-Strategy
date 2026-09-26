# Android 跨进程验收

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
