# 国标麻将「七对起手」验收

第 13 题「七对起手」采用项目自编牌墙，含 144 张牌及完整物理库存。Native 从固定东家观察席恢复牌局、发牌、列出合法和牌动作并完成结算。题目仅展示本机 14 张手牌。七对为规则第 19 项，计 24 番，达到八番起和门槛。[世界麻将组织竞赛规则](https://www.mindmahjong.com/adobe/MCR2021.pdf)

前 12 道题的顺序、进度和备份格式保持兼容。新题按大师档发放 1 星、80 分，答错后仍可重试，训练不写赛事结果。

候选 `cmpractice1` 通过 10 项 JVM、NDK 30 双 ABI、Debug APK、仪器 APK、Release Manifest 和两次 Pixel 7 Pro 模拟器测试。Lint 0 错误、25 警告。普通和 150% 字号均验证手牌隐私、错误选项和 Native 确认后的首通。核看 6 张截图。首次测试发现底层记录缺 MCMS 外层封装，补齐后通过。[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-native-seven-pairs.1.json)

本节点交付基于固定牌局的和牌识别题。实战出牌、听牌操作和实体设备另行验收。
