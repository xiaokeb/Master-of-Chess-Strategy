# 国标麻将弃牌训练「留东听七对」

第 14 题给出六对、单张东风和一张红中。玩家选择弃牌后，Native 执行该弃牌并重放三家依次摸切的固定教学变化。玩家下一次摸到东风后，可确认自摸，由 Native 检查七对和起和门槛。手牌、固定牌墙和教学变化为项目自编。[国标竞赛规则](../../requirements/competition-mahjong-rules.md)

测试枚举了八种合法弃牌。只有弃红中能在本题固定变化中合法自摸。错误弃牌保留初始手牌，正确弃牌和摸牌暂不计入进度。确认自摸成功后才写入首通奖励 1 星、80 分。前 13 题及已有进度格式兼容，训练不写正式赛事成绩。

候选 `cmdiscard1` 通过 10 项 JVM、NDK 30 双 ABI、Debug APK、仪器 APK、Release Manifest 和两次 Pixel 7 Pro 模拟器测试，Lint 0 错误、25 警告。核看两档字号的错误弃牌、下一摸和确认自摸共 6 张截图。[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-discard-practice.1.json)

此题验证固定教学变化。分支防守、其他牌型的听牌操作和实体设备另行验收。
