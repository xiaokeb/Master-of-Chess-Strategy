# 围棋 KataGo 19 路长局

Pixel 7 Pro 模拟器使用 `golong19`，基于 `69fd16b`，沿用正式牌桌和随应用加载的 KataGo 模型。测试新增 `long19` 种子入口，从空棋盘启动简单档自动演局，系列为第 2／2 局。此局验证持续行棋、恢复和末局归档，棋力分级与实体设备仍待验收。

- 初次运行的种子测试通过。设备驱动在收据创建前读取失败，修正等待条件后使用同一个测试存档与 APK 开始演局。
- 第 82 条事件暂停后终止专用进程，PID `9730` → `10692`。同一对局 ID、棋盘检查点、模式与系列状态恢复。实际点击「继续思考」后继续行棋。
- 从开始自动行棋到进入计分用时 396.54 秒，含中盘暂停、进程终止与重开。此时 355 条事件。双方确认后共 357 条事件，页面显示白方获胜，达到系列上限。
- Room 中只有一份归档、棋谱和投影，三者记录 ID 一致，成长结果为零。七次进程 PSS 采样介于 300161 至 307434 KiB。

复现沿用 [围棋正式对局跨进程恢复](2026-11-10-formal-process-recovery.md)，种子参数 `go_process_case` 使用 `long19`。原始证据见 `.build/golong19/device-9789767ee56b4c05a8ab232c1d797718/seed.log` 和 `.build/golong19/device-038e553d39964aa097afa4ed75064943/`。[发布清单](../../releases/manifests/v0.1.0-go-katago-long-game.1.json)

发布提交为 `b8f853818a863e208acfd684280fc8482f3f52fc`。[源码预发布 v0.1.0-go-katago-long-game.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-katago-long-game.1)
