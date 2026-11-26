# Xiaomi M2007J3SC 真机回归

设备为 Xiaomi M2007J3SC，Android API 31，arm64-v8a。围棋与国标麻将回归使用 `3bbd984` 的 Room schema 27 集成版，测试使用独立数据库。

| 棋种 | 通过 | 验收范围 |
| --- | ---: | --- |
| 围棋 | 9 | 检查点准入、已结算状态与历史校验、归档及活动局的原生备份、坏数据拒绝与事务回滚 |
| 国标麻将 | 6 | 九胜无法借用中等资格，累计 10、25、45 胜达到正式开局门槛并实际出牌。提示回执可恢复，起和建议遵守八番与公开信息边界 |

国标麻将难度批次首轮超过宿主等待时间，分开重跑后两项完整通过，用时 433.319 秒。提示另跑四项通过。这两组回归覆盖引擎、存储与规则，正式牌桌的真机界面和四档棋力单独验收。

回执存于 `.build/physical-priority-20261126/`。Debug APK SHA-256 为 `060f71252cc9f280915804f77bbd2ab51516894d4b482480cfd3f8c28725e151`，仪器 APK SHA-256 为 `f62a2c1246a8922b0733e8db04621b10a28f09a75841baa840e3653cdbcc8c04`。

| 回执 | SHA-256 |
| --- | --- |
| `go.log` | `6f171f7a38c686ae5b860c4dfc2168447a4fbdc5fe7ce64558d8282f817cf6ef` |
| `competition-mahjong-difficulty.log` | `fb3c1b4f318a2e6ebfd5bf5d7d8f06d53765ff209cecb56857f98479ee90c30c` |
| `competition-mahjong-hints.log` | `fd7e94803e5ed9bbebd1a4ebc66c76e591bf26d5502e0f43270c39c6e8c4173c` |

同日其他真机验收见 [兰州麻将棋谱](../games/lanzhou-mahjong/2026-11-25-archive-management.md)、[兰州方棋共享棋谱](../games/lanzhou-fangqi/2026-11-26-shared-records.md) 及 [围棋第 84 槽终局复核](../games/go/2026-11-26-mixed-color-score-review.md)。
