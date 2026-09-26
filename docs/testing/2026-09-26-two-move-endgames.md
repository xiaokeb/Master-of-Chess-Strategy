# 两步强制胜残局验收

日期：2026-09-26。环境：指定 NDK 30 / Clang 21、Android x86_64
Pixel_7_Pro AVD、项目 Conda Python；未使用 MinGW，未新增 Python 依赖。

## 本节点内容

现有 v5 追加 `xq-easy-009`、`010`，总数 16。原十四关文件前缀 SHA-256
仍为 `c651394d69d0bf55db4c2df709f67e2335a9af16dcf1e56dd10ad6eb33a2d20b`。
格式、旧关卡会话变体及主线不变；两关均为简单奖励支线、限两次红方走子。
新文件 SHA-256 为 `7c694f769a10768f406b9df3117208fb506616c9e51db15e07e2a94cfcb82166`。

离线证明器只编入 `MOCS_BUILD_TESTS` 目标，不进入正式 APK，不替代四档 Pikafish。
在红方分支寻找一个可胜着，在黑方分支要求全部应手均可胜；按正式挑战语义，
红方步数用尽而尚未获胜即失败。终局胜负（包括困毙）优先判断，和棋不算获胜。
每个根着分别求证，只有搜索完成且获胜根着仅一个时才声明首着唯一。
预算耗尽返回独立状态，不能作为无解、唯一或失败证明。

## 已验证

| 范围 | 实际结果 |
| --- | --- |
| 原生 `mocs_xiangqi_forced_win_test` | AVD 退出 0；一步/两步、困毙、非法参数、不适用起点、预算精确边界、输入状态不变通过 |
| 独立固定深度穷举 | 两个局面各仅一制胜首着；均无一步胜着；拒绝 20 个仅在部分配合应手下可胜的首着 |
| 候选生成 | 固定种子 20260925，attempt 19 / 28；证明节点 2355 / 1706；无预算耗尽候选被接纳 |
| CLI 边界 | 4 组非法参数退出 2；搜索 1 个候选尝试但未凑足请求退出 1；旧一步杀模式退出 0 |
| JVM 残局与进度 | 15/15：包解析 9、残局 ViewModel 3、进度仓库 3 |
| Android 实际 APK 资产 | 3/3：全部 16 关主变化、九关唯一一步杀、两关全部防守应手与首着唯一性 |
| Compose 界面 | 2/2：新关两步目标/奖励可见且选择 ID 正确，原奖励支线锁定说明仍可见 |
| Conda 内容审核 | 10/10：结构、重复、落点、旧 v5 前缀字节级兼容等 |
| Gradle 构建门禁 | Debug APK、双 ABI 构建、来源哈希、GPL/法律副本、NNUE 和音效资产门禁通过 |

本轮按受影响范围测试，未重复全量 App/JNI/Lint，也未将历史全量结果记为本轮结果。
两关尚不等于职业难度标定或完整逆向可达性证明；16 关仍远未完成全棋种 9000+ 内容目标。
未改自然限着、长将长捉或第三方许可策略；自然限着选择仍见对应决策文档。

## 复现

使用项目已配置的 SDK CMake/ADB：

```powershell
cmake --build .build/native-x86_64 --target mocs_xiangqi_forced_win_test mocs_generate_xiangqi_mates --parallel 4
adb push .build/native-x86_64/tests/mocs_xiangqi_forced_win_test /data/local/tmp/mocs_xiangqi_forced_win_test
adb push .build/native-x86_64/tests/mocs_generate_xiangqi_mates /data/local/tmp/mocs_generate_xiangqi_mates
adb shell chmod 755 /data/local/tmp/mocs_xiangqi_forced_win_test /data/local/tmp/mocs_generate_xiangqi_mates
adb shell /data/local/tmp/mocs_xiangqi_forced_win_test
adb shell /data/local/tmp/mocs_generate_xiangqi_mates 2 2 10000
```

生成器默认参数仍为 `1 20 20000`（一步杀、20 个候选、最多 20000 次摆位）。
两步模式先排除一步胜局，再在每局 200000 节点预算内求证；输出的主变化
只是已验证策略中的一个示例，不是完整防守树或最优杀棋距离声明。
