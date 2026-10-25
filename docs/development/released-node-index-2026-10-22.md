# 已验证节点与后续集成

## 2026-10-25最新节点（优先于下方历史规划）

| 节点 | 提交 | tag/Release | 实际验证摘要 |
| --- | --- | --- | --- |
| 方棋正式本地对局/完整历史 | `f3f92364a2a01a7bd8f7b2c25066b11b14110578` | [v0.1.0-lanzhou-fangqi-local-session.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-lanzhou-fangqi-local-session.1) | 79JVM、5Native程序、25设备方法＋6大字体调用、共享备份2方法、实际杀进程2次、23图；初轮截图超时保留、失败方法同APK复核通过 |
| 象棋预算v2准入 | `d64deddb83447b4cc0c419604aa440aca49c2fe6` | [v0.1.0-xiangqi-budget-v2-admission.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-xiangqi-budget-v2-admission.1) | 71测试、41对象/2库/45命令、12设备准入检查；不搜索、不授棋力 |
| 围棋正式AI入口 | `141ed92c75796735f73ae708cd146f225d292a84` | [v0.1.0-go-ai-entry.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ai-entry.1) | 141同源JVM、74设备方法/107调用、双字号64图、真实NN与保留VM；完整棋力/内容另验 |

下一按正式依赖推进象棋G0观测契约与未来有限诊断协议，方棋学习持久化/资格，国标v4正式Room/备份，兰州24倍版本兼容与App，虎羊AI宿主。当前不开放方棋新四档入口，不恢复旧象棋异常评测；全六棋种目标保持active。后续发布仍由Root串行冻结/集成/提交，无APK附件。

## 历史节点与当时规划

本索引维护已发布源码节点，不代表完整产品；阶段Release均无APK附件。完整需求与各棋种未验事项继续按PRD/任务分级执行，不设80%跨棋种门槛。

| 节点 | 提交 | tag/Release | 实际验证摘要 |
| --- | --- | --- | --- |
| 围棋AI完成棋谱 | `a0b10fe8f97c1784c618abd68a18657dedd52550` | [v0.1.0-go-ai-completed-records.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ai-completed-records.1) | 72 JVM同生产输入继承、最终36设备方法/40调用、双字号16图；schema17及来源/备份配对 |
| 围棋AI生产存储 | `0c4de78481dcb710d506919198de8f3f1be5ceb0` | [v0.1.0-go-ai-production-storage.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ai-production-storage.1) | 126受影响JVM继承，最终17设备方法/19调用，双字体12图；四源事务及完整备份切换 |
| 围棋AI生命周期 | `40f290539acfe07aa3ab13ccf28cc308c3529e65` | [v0.1.0-go-ai-lifecycle.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ai-lifecycle.1) | 156正式JVM、4真实Native设备、双ABI/Lint；多错保真修复 |
| 象棋35关内容 | `9f0f598f163f3dbf3fbafd6da122da3fa8b0bf78` | [v0.1.0-xiangqi-endgame-content.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-xiangqi-endgame-content.1) | 71JVM、35关两种独立证明、20设备方法＋2大字号复跑、80当前图 |
| 围棋AI raw来源 | `bffe998e4d2f874404ef69f63a0532ac6d040c25` | [v0.1.0-go-ai-raw-data.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ai-raw-data.1) | 同生产/JVM输入140继承、最终27设备；schema16与完整GAI/来源图 |
| 象棋规则修订 | `c0541e4b23c435cbd8e060aac7346bc66e4537f9` | [v0.1.0-xiangqi-rule-revision.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-xiangqi-rule-revision.1) | 新旧规则身份、连续杀证明、旧局兼容；最终窄补测及同输入继承见专属报告 |
| 虎羊原生AI | `359dba06ab40dc43a83d1735596c3bbf72674e5e` | [v0.1.0-tiger-goat-ai-native.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-tiger-goat-ai-native.1) | 20JVM、13JNI、同源10原生组、双ABI；尚无App AI入口 |

每个节点的精确输入、失败/修订和验收限制以相应tag内manifest与专属报告为准，不把重复执行累加为不同用例或把旧APK结果套新代码。

## 下一依赖

围棋生命周期、生产Room与完整备份、唯一完整棋谱/首次完成事务现均已发布；下一为版本化可信参赛创建来源及首次成长、资格与模式导航，当前尚未开放生产AI。公共象棋完成DTO显式拒绝GO且强制人机outcome，不能直接套用或给混合/遗留局发奖励；现有专属投影及schema17关联都不授排名资格。国标提示v4/公开信息Advisor、虎羊App owner、兰州24倍旧局兼容、象棋预算v2无搜索准入都已分别取得私有或独立原生证据，仍待各自正式集成，不纳入本表已发布范围。

原象棋medium-hard已自行异常退出，无完整汇总；未由统筹停止、未重跑，不用53局前缀宣告梯度。新v2基础不授权搜索或消费新的保留集。兰州炸胡已获用户确认各家24倍底分；正式补充规格见 `../requirements/lanzhou-mahjong-false-win-v2.md`，新旧规则身份必须保留，不把项目桌规说成地方官方通则。

尚未证明任一棋种全部规划功能、最终9000+内容量、专业难度、实体设备或完整资源/性能验收。远程源码发布也不扩大Pikafish/NNUE、KataGo等独立权重或第三方许可。
