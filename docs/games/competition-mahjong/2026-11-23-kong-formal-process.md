# 国标麻将新版正式赛事进程恢复

本节点覆盖使用新版 WMO 杠计分的正式赛事。首次启动在独立测试数据库中生成通过原生结算的第 15 或第 16 盘。随后从正式赛事入口打开牌桌，终止专用 Debug 进程并再次打开。测试保留旧版本场景和用户数据库。

- 第 15 盘结算后，核对比分、赛事 ID、版本、归档数和存盘散列。恢复后点击「下一盘」，第 16 盘以同一赛事身份开始，累计分与已结盘数保持。
- 第 16 盘归档后，核对同一组字段。恢复后只有一份归档。

测试进程由 `CompetitionMahjongFormalProcessCheckActivity` 提供。通过仪器选择器 `CompetitionMahjongFormalProcessInstrumentedTest#seedWmoKongsBetweenHands` 或 `#seedWmoKongsFinishedSeries` 准备 UUID 独占数据库。证据使用同一 APK、前后 PID、存盘收据和恢复画面。

## 验收

Pixel 7 Pro 模拟器两次终止并重新启动独立 QA 进程。第 15 盘重启后继续到同一赛事第 16 盘，积分和已结盘数一致。第 16 盘重启后仍只保留一份归档。前后原生存档版本、榜单和持久化摘要均已核对，并抽查五张设备画面。

NDK 30 双 ABI、Debug 主 APK、测试 APK、Release 主清单与 9 项 JVM 测试通过。Lint 0 错误、25 警告。候选构建基线为 8c2a3b3。源码预发布，实体设备待验收。

[验收清单](../../releases/manifests/v0.1.0-competition-mahjong-kong-process.1.json)

## 2026-11-28 小米真机复验

小米 25042PN24C（API 37、arm64-v8a）与 Pixel 7 Pro 使用同一主 APK 和测试 APK。新仪器用例通过无障碍操作打开正式牌桌，外部驱动按存盘收据核对 PID 后只终止隔离 QA 进程。

| 场景 | 小米进程 PID | 验收结果 |
| --- | --- | --- |
| 第 15 盘结算后 | 21491 → 21788 | 重启后逐字段匹配原收据。点击「下一盘」进入同一赛事第 16 盘，已结 15 盘，四家累计分仍为 −118、50、−32、100。 |
| 第 16 盘归档后 | 22548 → 22789 | 恢复同一赛事、四家分数、原生存档摘要及正式快照摘要，仍只有一份归档。 |

第 16 盘的真机原生样本生成超过 300 秒，原日志保留。恢复复验使用 Pixel 7 Pro 原生结算后导出的独立数据库，导入前通过 SQLite 完整性检查，导入后核对文件散列。真机恢复收据除 PID 外与模拟器一致。末盘在真机直接生成和结算仍待复验。已复核标准字号横屏的结算、换盘和归档画面。

以 `CompetitionMahjongSeriesRecoveryActionInstrumentedTest#openSeriesAtSettlementBoundary` 运行，传入种子测试使用的 `mahjong_process_token` 及 `mahjong_settled_hands`（15 或 16）。首次获得收据后只终止匹配的专用进程，再传入相同参数和 `mahjong_process_reopen=true`，检查恢复后收据。第 15 盘用例会继续操作「下一盘」。

[验收证据清单](../../releases/manifests/v0.1.0-competition-mahjong-kong-process-xiaomi.1.json)

## 从进行中的第 16 盘直接结算

新增 `settleResumedWmoKongsFinalHand` 专项，使用「下一盘」已保存的隔离检查点。从第 16 盘直接继续合法动作，核对原赛事配置与前 15 盘结果，确认末盘结算后才新增一份归档。复现时结束已核对的专用牌桌进程，再以同一 `mahjong_process_token` 运行专项；随后用上节仪器用例打开、终止并重开归档状态。

Pixel 7 Pro 冷启动保留原测试库。结算、打开归档和跨进程恢复共 3 项通过，PID 3934 → 4045。累计分 −126、42、−54、138，存盘摘要与归档数一致，已复核横屏结算画面。本轮 ADB 未发现两款指定真机，末盘直接结算的真机验收仍待补齐。

[专项证据清单](../../releases/manifests/v0.1.0-competition-mahjong-resumed-final-hand.1.json)
