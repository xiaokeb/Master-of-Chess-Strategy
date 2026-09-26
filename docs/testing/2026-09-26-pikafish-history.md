# Pikafish 搜索历史验收

## 已验证

- NDK 30 / Clang 21 x86_64 完整象棋规则测试在 AVD 退出码 0；新增快照测试覆盖标准起点、三着含吃子、无吃子计数归零、独立快照、悔棋、存档恢复、重开，以及黑先起点计数 118→119、完整回合 1→2。
- 长将判负后，从恢复的快照取完整八着重放，Pikafish 历史裁判仍返回黑胜；快照终局状态与规则层一致。
- 新 `mocs_pikafish_search_history_test` 使用已验证 NNUE 实际搜索。四种错误（非法历史着、删除历史、错误终点棋盘、错误行棋方）均拒绝，之后再次正常搜索通过；恢复存档后的搜索通过。和棋、红胜、黑胜即使仍有几何候选也不搜索。
- Android 的 `PikafishMasterAiInstrumentedTest` **5/5**，0 失败、0 错误、0 跳过：四档切换、单车胜局兑现、一步困毙、重复历史的恢复/悔棋/判和后空着、黑先自由摆局的原始自然限着计数。
- Gradle 双 ABI 原生构建、Debug APK、许可/权重/内容门禁及 Lint 通过。Lint 沿用 3 条依赖版本提醒；未改 UI/业务层，不重复本节点无关的全量界面测试。
- Conda 离线回归 24/24（内容 9、AI 审核 15），覆盖完整历史模式报告及未知模式拒绝。

## 发现并处理的问题

首次 Android 测试发现已重复判和后仍能返回建议着。原因是 `legal_actions()` 表示棋盘上的几何合法动作，不保证对局仍在进行。现快照携带规则层终局结果，适配器在终局直接返回空着。

另一处失败来自新测试摆位把未过河红兵放在奇数列，上游正确拒绝该非法摆位。改为合法中兵和中路将帅，保留“黑先、初始计数 118、走一步后再搜索”的原测试目标；并非放宽上游校验。

## 双向短局检查

`results/2026-09-26-ai-pikafish-history-smoke-v1.jsonl`：原 suite 1 的第一个开局，交换先后手，每局最多搜索 32 半回合。64 次搜索全部通过历史重放与合法性复核，2 局均未完，已完成局为 0，两项胜率/得分率均为 null。本检查只验证链路，不用于棋力达标判断。

报告新增 `history_mode=full`，旧报告缺省标识为 Pikafish `fen-only` 历史阶段；同一 profile 的两类测量不可不加说明地合并。受终局保护改动影响的路径由原生/Android 终局用例单独覆盖，短局只在 ongoing 状态发起搜索。

原生带权重测试复现：

```powershell
cmake --build .build/native-x86_64 --target mocs_pikafish_search_history_test --parallel 4
adb push .build/native-x86_64/tests/mocs_pikafish_search_history_test /data/local/tmp/mocs_pikafish_search_history_test
adb shell chmod 755 /data/local/tmp/mocs_pikafish_search_history_test
adb shell /data/local/tmp/mocs_pikafish_search_history_test /data/local/tmp/mocs-pikafish.nnue
```

使用已指定的 SDK CMake、NDK 和 ADB；权重复制前须经现有 SHA-256/大小门禁。当前结果仅来自 AVD，不替代 ARM64 真机长局与首次加载性能验收。
