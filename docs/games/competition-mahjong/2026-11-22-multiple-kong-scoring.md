# 国标麻将三杠和四杠的暗杠计分

WMO《麻将竞赛规则》附件一第 5、17 项允许三杠及四杠另计暗杠，第 67 项规定单个暗杠为 2 分。当前 `legacy_v1` 和 `wmo_2021` 在三杠、四杠分支均未输出该番种。[WMO 规则附件一](https://mahjongclublausanne.ch/wp-content/uploads/2024/03/Greenbook2021_Chinese-EN.pdf)

用 NDK 30 的 x86_64 原生计分程序在 Pixel 7 Pro 模拟器复现。两副牌例共用一万暗杠、一条明杠、红中明杠和五万将，均为点和。三杠牌例第四组为四五六筒，四杠牌例第四组为一筒明杠。

| 牌例 | 当前 WMO 总分及番种 ID | 按附件一应计 |
| --- | --- | --- |
| 三杠含一暗杠 | 39 分，17、59、65、73 两次、79 | 41 分，另计 67 一次 |
| 四杠含一暗杠 | 109 分，5、32、59、73 三次 | 111 分，另计 67 一次 |

原始输出保存在 `.build/cm-kong-wmo-probe.log`，SHA-256 为 `8830ab2db78e5dc750d58afeb4c159e7ec927977619092e21e9e79b1469c992c`。模拟器二进制 SHA-256 为 `a3c77f25184284099ebe8b3955a60449c2794c48b59fe399f6af639fbea82ca9`。

## 原生修复与验证

新版采用 MOCM v3、MCMA v4。一暗杠计暗杠 2 分，两暗杠计双暗杠 8 分并去除由同两组构成的双暗刻。三、四暗杠计相应的三暗刻或四暗刻，按就高计分处理同组暗杠。修复前的 MOCM v1／v2 和 MCMA v1／v2／v3 保留各自计分语义。[WMO 就高计分原则及附件一](https://mahjongclublausanne.ch/wp-content/uploads/2024/03/Greenbook2021_Chinese-EN.pdf)

NDK 30 双 ABI 编译通过，Pixel 7 Pro 实跑 137 个计番场景（含 9 个杠组合）及 8 组自动演局测试（含 6 局完整对局）。用例验证新版与旧 WMO 自动对局各自恢复、结算一致，以及跨版本拒绝后状态不变。[原生证据](../../releases/manifests/v0.1.0-competition-mahjong-kong-scoring-native.1.json)

## Android 接入与验证

标准正式赛事的新局使用 MCMS／MOCM v3，赛事检查点使用 MCMT v8。新标准自动演局使用 MCAU／MCMA v4。存档按原版本恢复，指定教学开局沿用其随档计分版本。正式备份沿用 MCTX，既存三种归档策略和新增策略分别编码。

106 项定向 JVM 测试通过，双 ABI、Debug 主包、仪器包和 Release 主清单构建通过，Lint 0 错误、25 警告。Pixel 7 Pro 默认和 150% 字号各验证正式新局出牌与续局、自动新局单步与续局。另通过旧 WMO 十六盘重放、生产备份恢复和自动棋谱交换，共 7 项仪器测试。已核看 16 张画面。[Android 证据](../../releases/manifests/v0.1.0-competition-mahjong-kong-scoring.1.json)

## 完整正式赛事备份

Pixel 7 Pro 分别以旧、新 WMO 计分跑完十六盘正式赛事。核对每盘原生日志的会话与计分版本，验证第八盘磁盘重开、末盘归档失败重试、撤回结算与重新结算。旧赛事和新版进行中赛事同包恢复后，旧、新计分策略仍分别匹配原始记录。新版完整赛事也通过生产备份恢复，重新生成的十六盘结果逐盘一致。错盘日志和损坏档案均拒绝导入，原库字节一致。

9 项 JVM 测试及 2 项模拟器测试通过，双 ABI、Debug 主包、仪器包和 Release 主清单构建通过，Lint 0 错误、25 警告。该专项只改验收用例，生产界面证据见上一节点。[完整备份证据](../../releases/manifests/v0.1.0-competition-mahjong-kong-backup.1.json)
