# 国标麻将四档策略诊断结果

Pixel_7_Pro 上以 Android NDK 30 的 x86_64 LLVM 构建并运行原生自动演局。规则和策略来自 `96667d7c0bac699c6770e0a5c41a63d042827ef0`。完整执行 96 场、1536 盘，进程退出码 0，标准错误为空。四席总分逐行验证为零和。[冻结协议](2026-11-14-strength-diagnostic-protocol.md) · [原始结果](../../testing/results/2026-11-14-competition-mahjong-strength-diagnostic-v1.tsv)

| 相邻档 | 高档比较得分 | 8 个牌墙种子的范围 | 高档胜／平／负比较数 | 512 盘流局数 |
| --- | ---: | ---: | ---: | ---: |
| 简单与中等 | 100% | 100%–100% | 128／0／0 | 237 |
| 中等与困难 | 75% | 62.5%–100% | 96／0／32 | 42 |
| 困难与大师 | 56.64% | 50%–75% | 72／1／55 | 14 |

简单档目前随机选弃牌且跳过吃碰杠。可先诊断降低完全随机的比例及是否让简单档识别明显改善牌形的认领。大师档当前仅在听牌弃牌估值中额外计算八番可和进张，和困难档的差距偏小。下一轮先单独验证提前考虑达标牌型与牌形改善的收益，再确定候选参数。现有样本已公开，只用于后续训练诊断。正式验收保留独立未见种子。

策略调整前需冻结策略版本的兼容方案。活动、私有重放及可信成绩的归档准入都依赖可重复的历史。新旧行为都需真实恢复与回放验证。本节点发布诊断工具与证据。

驱动 SHA-256 为 `5f0fb42c8d0f8c3851b54b5f76a122c030a01098ca83b2b97d97d250eaa283c8`，设备可执行文件为 `26535067182b808720de1aea7ce4fd511ba9c864efdfeb48dc6ef211ad252938`，原始 TSV 为 `1ffa73a2bb68da7f35ab6b55b3df13bbb7e73cf08aa82964aa9e1116f79c505c`。

复现使用同一版本原生源码构建 x86_64 Debug 的 `libmocs_engine_core.a`。用 NDK 30 的 `x86_64-linux-android24-clang++` 以 `-std=c++17 -O2 -Wall -Wextra -Werror` 编译 [诊断驱动](../../../tools/android/competition-mahjong/competition-mahjong-strength-driver.cpp)，加入 `engine-native/src/main/cpp/include` 和 `engine-native/src/main/cpp/games/competition-mahjong` 头文件目录，再以 `-static-libstdc++` 链接该静态库。在模拟器的独立测试目录执行驱动，参数为 `diagnostic-v1`。耗时列受运行环境影响，其余字段由冻结的种子与原生代码决定。
