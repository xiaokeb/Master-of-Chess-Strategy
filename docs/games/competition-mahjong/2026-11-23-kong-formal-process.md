# 国标麻将新版正式赛事进程恢复

本节点覆盖使用新版 WMO 杠计分的正式赛事。首次启动在独立测试数据库中生成通过原生结算的第 15 或第 16 盘。随后从正式赛事入口打开牌桌，终止专用 Debug 进程并再次打开。测试保留旧版本场景和用户数据库。

- 第 15 盘结算后，核对比分、赛事 ID、版本、归档数和存盘散列。恢复后点击「下一盘」，第 16 盘以同一赛事身份开始，累计分与已结盘数保持。
- 第 16 盘归档后，核对同一组字段。恢复后只有一份归档。

测试进程由 `CompetitionMahjongFormalProcessCheckActivity` 提供。通过仪器选择器 `CompetitionMahjongFormalProcessInstrumentedTest#seedWmoKongsBetweenHands` 或 `#seedWmoKongsFinishedSeries` 准备 UUID 独占数据库。证据使用同一 APK、前后 PID、存盘收据和恢复画面。

## 验收

Pixel 7 Pro 模拟器两次终止并重新启动独立 QA 进程。第 15 盘重启后继续到同一赛事第 16 盘，积分和已结盘数一致。第 16 盘重启后仍只保留一份归档。前后原生存档版本、榜单和持久化摘要均已核对，并抽查五张设备画面。

NDK 30 双 ABI、Debug 主 APK、测试 APK、Release 主清单与 9 项 JVM 测试通过。Lint 0 错误、25 警告。候选构建基线为 8c2a3b3。源码预发布，实体设备待验收。

[验收清单](../../releases/manifests/v0.1.0-competition-mahjong-kong-process.1.json)
