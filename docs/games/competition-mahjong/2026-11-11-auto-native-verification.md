# 国标麻将四席自动演局原生验收

自动会话复用国标麻将单盘规则，按固定种子和四席策略推进。观察席由创建配置固定，切换观察视角不会改变同一开局的走牌轨迹。JNI 使用与人机牌桌一致的公开帧，行牌期间只向观察者展示本席暗手。

每次推进有事件预算，终局停止。私有 MCMA v1 封装会话配置及底层回合记录。恢复会重放并校验记录，拒绝错误归属、损坏数据和跨类型句柄。失败保留当前局面。自动会话支持撤回最后一步并按原决策重放。

## 验收结果

- 以 `508d80a` 和 14 个冻结文件构建候选 `cmautocore1`。Android NDK 30 构建 x86_64 和 arm64-v8a，生成 Debug 应用 APK 与原生测试 APK。Debug Lint 为 0 错误、24 警告。
- 11 项 JVM 测试通过，覆盖四席配置和 JNI 推进结果解码。
- Pixel 7 Pro 模拟器运行 8 项真实 JNI 测试，覆盖观察席、批量推进、隐私、损坏记录、句柄隔离、并发关闭及终局公开帧。
- 同一候选的 Android C++ 测试在模拟器执行 7 组断言，包含四盘完整对局及其终局恢复。

本节点覆盖原生单盘和 Kotlin 接口。十六盘调度、正式 Room 存储、后台许可、归档、备份、视觉验收及实体设备均为后续节点。四席策略强度仍需独立评测。本次只发布源码，候选和测试产物散列见[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-auto-native.1.json)。

提交 `85ac7b53f864d01cb0c75a80841b21b03ac830a1` 已推送到 `main`。[v0.1.0-competition-mahjong-auto-native.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-competition-mahjong-auto-native.1)
