# 国标麻将正式赛事跨进程恢复

候选 `cmformalproc1` 基于 `b8f8538`。Pixel 7 Pro 模拟器在普通字号下使用正式赛事入口、Native 牌桌和独立 UUID Room 数据库。测试从 10 场合格简单胜局恢复中等难度，初始提示、悔棋均剩 3 次。Debug 双 ABI 和测试 APK 构建通过，Lint 0 错误、24 警告。专用 Activity 仅进入 Debug 构建。

1. 从赛事概要进入牌桌，点击提示后保存，提示余额变为 2。终止专用 PID `13716` 后以 `13840` 恢复。核对原赛事、同盘、分数、牌局检查点和完整档案摘要一致。实际点击「查看已保存提示」，画面显示原建议，余额仍为 2。
2. 出 7 条后进入待认领阶段，保存相同赛事。终止 PID `13840` 后以 `14016` 恢复，两个存盘摘要、待认领阶段和辅助余额一致。实际点击「过」后同一赛事继续存盘，提示仍剩 2 次，悔棋仍剩 3 次。

种子测试、三张截图和两次恢复前后收据在 `.build/cmformalproc1/device-b2377d4caf6542b6bac714d3a248539b/`。截屏已核对。中间换盘、末盘结算时的进程终止与实体设备待验收。[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-formal-process-recovery.1.json)

复现使用 `CompetitionMahjongFormalProcessInstrumentedTest#seedMediumTable` 和唯一 `mahjong_process_token`。以 ADB 启动 `.games.competition_mahjong.CompetitionMahjongFormalProcessCheckActivity` 并传入 `owned_database=competition-mahjong-process-qa-<UUID>.db`。收据保存在 `files/competition-mahjong-process-qa/<UUID>.json`。只终止已核对的 `:competition_mahjong_formal_qa` PID，每次从赛事概要进入并等待新 PID 收据。
