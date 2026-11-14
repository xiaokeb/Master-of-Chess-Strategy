# 国标麻将相邻档位训练对照

同一批八个牌墙根种子和四次换座用于两组候选及基线复跑。每组独立完成 96 场、1536 盘，退出码均为 0，四席总分逐行验证为零和。复跑基线的全部分值、流局数和动作数与原始基线一致。[诊断协议](2026-11-14-strength-diagnostic-protocol.md) · [候选参数](2026-11-14-strategy-versioning-plan.md)

| 策略 | 中等对简单 | 困难对中等 | 大师对困难 |
| --- | ---: | ---: | ---: |
| 已发布基线 | 100% | 75% | 56.64% |
| 候选 v2 | 84.77% | 75% | 50% |
| 候选 v3 | 64.84% | 75% | 60.55% |

候选 v3 缩小了中等与简单的落差，并拉开了大师与困难的差距。样本已用于两轮调整，不能用来验收正式 7:3 目标。正式入口保留已发布策略。后续继续在训练用样本检验大师在向听阶段选择可达八番路线的收益，再冻结独立未见种子和策略版本。

冻结候选通过 NDK 双 ABI、Debug 与仪器 APK、Release Manifest。Lint 0 错误、25 条警告。Pixel_7_Pro 同源 APK 的三项正式赛事私有重放和两项自动演局回放测试通过。

[v2 原始记录](../../testing/results/2026-11-14-competition-mahjong-strength-diagnostic-v2.tsv) · [v3 原始记录](../../testing/results/2026-11-14-competition-mahjong-strength-diagnostic-v3.tsv)
