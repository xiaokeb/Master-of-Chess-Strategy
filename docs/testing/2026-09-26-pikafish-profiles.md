# 四档 Pikafish 接入与基线

## 验证范围

正式规则层不变；将简单、中等、困难从过渡本地搜索切换到同一已集成 Pikafish。深度、节点、思考时间、多候选和受限随机偏差分别按 profile v1 配置。大师仍为 1200 ms 限时。测试环境沿用 NDK 30 / Clang 21、Pixel_7_Pro Android 17 x86_64 AVD 和项目 Conda。

## 功能证据

- C++：完整象棋规则与旧搜索回归通过；新纯参数用例覆盖深度/节点递增、低档 75%/20% 偏差抽样、候选窗口、单候选退化、困难/大师零偏差及杀棋保护。
- JVM：engine-native、app 单元测试通过。四档均把正确难度码与已验证权重路径传给桥接层；四档在提供器缺失时明确拒绝。
- JNI 模块仪器测试 6/6：保持规则/存档/将军查询回归，并验证没有权重时不会走回过渡搜索。
- App 专项 3/3：顺序切换大师→简单→中等→困难→简单，所有返回着均在权威合法列表内，实际局面不变；直接兑现一步困毙；单车胜局对所有红帅合法应手在 11 半回合内获胜。
- 失败用例修订后，最终 App 全量 53/53，0 失败、0 错误、0 跳过。JVM 报告合计 205（app 181、engine-native 19 本轮运行通过，未修改的 engine-api 5 沿用原通过报告）。
- 双正式 ABI、测试 APK/Debug APK 构建及法律/权重资产门禁通过。Lint 0 错误、3 条依赖版本提醒；正式 Release 性能和体积未验收。

### 如实记录一次失败

首次 App 回归中，沿用旧诊断搜索的“必须在三半回合内困毙”断言失败。直接记录上游搜索可见其选用更长的将死路线（深度 9/10 给出 mate 7），该断言不是正式功能要求。保留 C++ 旧搜索三步回归不变；正式引擎改为验证所有应手下兑现胜局，并另设独立的一步困毙测试。不是把败局、未完局改记成成功。

## 对弈测量

首组 `results/2026-09-26-ai-pikafish-easy-medium-v1.jsonl` 为全 Pikafish 的六开局双向 12 局：中等 11 胜、简单 1 胜，未达到 7:3。它与旧本地搜索结果分开保存。

第二组 `results/2026-09-26-ai-pikafish-medium-hard-v1.jsonl` 为困难 12 胜，仍未达到 7:3。两组共 24 局、1848 次搜索，全部有终局。困难搜索平均 616.97 ms、P95 989.76 ms、最大 2071.48 ms（含首次初始化）；不能据此宣布 ≤2s 或真机性能通过。当前全 Pikafish 的困难对大师尚需测量，旧“本地困难对大师”报告不适用。

新报告配置包含 `backend=pikafish`、`profile_version=1` 和 `selection_seed=20260926`。每局抽样器以种子加开局/颜色偏移初始化，搜索缓存每局重置，行棋开始时也按档位切换重置。240 半回合上限仍单列未完局。此轮与编译/仪器验证并行，耗时是开发观察而非隔离性能基准。

复现（程序路径按 `environment.md` 使用指定 SDK/Conda）：

```powershell
cmake --build .build/native-x86_64 --target mocs_benchmark_xiangqi_ai --parallel 4
adb push .build/native-x86_64/tests/mocs_benchmark_xiangqi_ai /data/local/tmp/mocs_benchmark_xiangqi_ai
adb shell chmod 755 /data/local/tmp/mocs_benchmark_xiangqi_ai
# 权重须先按 app/build.gradle.kts 中的大小和 SHA-256 校验后复制。
adb shell '/data/local/tmp/mocs_benchmark_xiangqi_ai easy-medium 6 240 /data/local/tmp/mocs-pikafish.nnue pikafish > /data/local/tmp/pikafish-profile-v1.jsonl'
adb pull /data/local/tmp/pikafish-profile-v1.jsonl .build/pikafish-profile-v1.jsonl
python tools/python/audit_xiangqi_ai.py .build/pikafish-profile-v1.jsonl
```

显式传 `legacy` 可运行保留的本地诊断搜索；默认后端为 Pikafish，不能将 legacy 报告用于现行产品棋力验收。旧基线的精确实现保存在 `b0decad` 及更早提交。

## 冻结保留开局

suite 2 的 12 条项目自编线路独立于原六条调参开局。入口 `validate-openings` 无需 NNUE，逐着调用权威规则，拒绝非法、提前终局、错误先手或重复局面。已在 AVD 验证全部 18 条，FEN 清单保存在 `results/2026-09-26-ai-openings-v2.jsonl`。

本轮仅冻结与校验，不测量保留集胜率。候选参数确定后，用命令末尾附加 `2` 并指定 `12` 个开局，形成 24 局配对验收；任何结果都不能成为删除某条保留线路的理由。12 条仍不是大规模统计样本，不预先宣称胜率置信度足够。

Conda 全部离线审核 23/23（内容 9、AI 14），包含旧报告兼容、新后端/profile/seed 门禁和 suite 2 配对结构验证。
