# 象棋 AI 梯度：首轮基线

## 结论

正式需求要求相邻难度胜率约 7:3。本轮只建立测量基线，**未达到梯度验收条件**；未修改正式 AI 搜索深度、节点预算或候选窗口。功能接入完成不等于棋力校准完成。

| 较强档 / 较弱档 | 胜 / 负 / 和 / 未完 | 已完成局得分率 | 有胜负局胜率 | 较强档平均 / P95 毫秒 |
| --- | --- | --- | --- | --- |
| 中等 / 简单 | 11 / 0 / 1 / 0 | 95.83% | 100% | 24.81 / 55.93 |
| 困难 / 中等 | 9 / 1 / 1 / 1 | 86.36% | 90% | 245.66 / 477.51 |

每组 12 局不是 12 个独立开局样本；同一开局交换颜色形成配对。样本少，不能据此推断总体胜率或把它当作发布棋力结论。困难对大师尚未测量。

## 方法与边界

- 规则和正式 AI 源码基于 `ba52d7e`。新增测量入口为 `engine-native/src/main/cpp/tests/benchmark_xiangqi_ai.cpp`，不打入 APK。
- 固定 suite 1：六条项目自编开局各走四个半回合，再交换较强档执红/执黑。起点、终点 FEN 和其后每步 UCI 坐标保存在 JSONL；开局历史保留在原生引擎中。
- 每局最多再搜索 240 个半回合；上限仍未终局的单列 unfinished，不推定胜负、不当作和棋。
- 得分率 =（胜 + 和 × 0.5）/ 已完成局数；有胜负局胜率 = 胜 /（胜 + 负）。无对应分母时为 null。
- 每步必须经正式规则引擎接受；终局由同一规则引擎判定。这不是独立规则裁判，尚不能排除底层棋例判定缺陷。
- 耗时仅包围选着调用，不含走子应用、日志、UI 和动画；P95 使用向上取整最近秩。未完局的搜索也计入耗时样本。
- 环境：NDK 30.0.16138531 / Clang 21，C++17，android-24 x86_64；Pixel_7_Pro AVD / Android 17。现有 CMake cache 的 build type 为空，规则目标未启用 `-O` 优化。耗时不代表 Release 或实体设备性能。
- 此轮未调用大师、未新增或替换第三方权重，Pikafish/NNUE 的现有发布限制不变。未来大师测量必须先通过现有权重来源、大小及 SHA-256 门禁；仅传文件路径不等于许可审查。

## 保存的证据

- `results/2026-09-26-ai-easy-medium-v1.jsonl`：12 局、873 次搜索。
- `results/2026-09-26-ai-medium-hard-v1.jsonl`：12 局、1566 次搜索；第 5 开局困难执黑达到上限。
- Conda `test_audit_xiangqi_ai.py`：11/11。覆盖缺失摘要、重复颜色、错分母、未完局误计和棋、空分母、截断、动作编码、开局不一致和耗时样本错误。
- 最终测量二进制：2 局、每局 16 个半回合的冒烟结果为 2 未完局、两种比率均 null；4 类非法参数均返回退出码 2（开局数越界、步数越界、未知档位、大师无权重路径）。
- NDK 目标构建及 `git diff --check` 通过。本轮不改变 App、JNI 或正式规则行为，不重复执行此前已通过的全量界面测试。

## 复现

使用 `docs/development/environment.md` 指定的 NDK 工具链，开启 `MOCS_BUILD_TESTS=ON`，x86_64 构建树完成配置后执行；以下 `cmake`、`adb`、`python` 分别指 SDK CMake、SDK platform-tools 与项目 Conda 环境中的程序，不使用 MinGW 或全局 Python。

```powershell
cmake --build .build/native-x86_64 --target mocs_benchmark_xiangqi_ai --parallel 4
adb push .build/native-x86_64/tests/mocs_benchmark_xiangqi_ai /data/local/tmp/mocs_benchmark_xiangqi_ai
adb shell chmod 755 /data/local/tmp/mocs_benchmark_xiangqi_ai
adb shell '/data/local/tmp/mocs_benchmark_xiangqi_ai easy-medium 6 240 > /data/local/tmp/mocs-ai-easy-medium-v1.jsonl'
adb pull /data/local/tmp/mocs-ai-easy-medium-v1.jsonl .build/ai-easy-medium.jsonl
python tools/python/audit_xiangqi_ai.py .build/ai-easy-medium.jsonl
python -m unittest discover -s tools/python -p 'test_audit_xiangqi_ai.py' -v
```

改为 `medium-hard` 可复现第二组。动作与结果在相同实现下应一致，耗时允许变化。CLI 也支持 `hard-master`，第四参数为已验证 NNUE 路径；大师基于限时搜索，动作还受设备速度和引擎缓存影响，不能承诺逐步一致。

## 后续门禁

先建立不参与调参的独立保留开局集，并补测困难对大师，再决定候选窗口、深度和节点预算的调整。每次调整保留版本、原始棋谱和双向样本；不能仅把这六个开局调到 7:3 就宣布达标。长杀等职业棋例、内容规模与实体音效/性能仍按进度表继续验收。
