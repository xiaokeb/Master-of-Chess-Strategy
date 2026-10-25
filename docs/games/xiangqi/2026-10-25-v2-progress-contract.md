# 象棋 v2 完整 choose 调用进度契约

本切片属于 [v2 下一阶段计划](2026-10-24-calibration-v2-next-plan.md) 的 G0 无搜索编码。新增纯验证器和替身单测，不接入已发布的准入入口、旧 runner 或真实引擎。当前代码契约尚须统筹审查；它不是已批准的运行协议，不授予 G1／G2／G4 搜索或终止权限。

## 外部上下文与身份

调用者必须提供不可变 `ProgressContext`，来自未来独立审核、注册的规格，不能从同一 sidecar 读取身份后反构 context。模块只接收已打开的二进制流；不打开或写入文件、不注册槽位、不启动进程、不联网、不使用 ADB，也没有自动超时或终止动作。

context 固定 `protocol_id`、`protocol_sha256`、`purpose`、`pair_id`、`run_id`、`runtime_identity`、`expected_games` 和 `maximum_requests_per_game`。SHA 均为小写 64 位十六进制。runtime 身份是未来外部规格所绑定的实际运行闭包身份；侧写验证不证明进程、ELF、时钟或设备真实可信。

| 用途 | 协议名空间 | 单进程边界 | pair_id |
| --- | --- | --- | --- |
| diagnostic | `calibration-budget-v2-diagnostic-<id>` | 1 组，恰好 2 次 choose | `diagnostic` |
| training | `calibration-budget-v2-training-<id>` | 12 局，每局外部固定 1～600 请求上限 | `M0-hard`、`M1-hard`、`E0-M0`、`E1-M0`、`E1-M1`、`hard-master` |
| holdout | `calibration-budget-v2-holdout-<id>` | 96 局，每局外部固定 1～600 请求上限 | `easy-medium`、`medium-hard`、`hard-master` |

上述训练名称仅区分计划中六份唯一作业；没有实例化候选数值卡。现有只准入的旧 protocol_id 不在该名空间内。跨作业／进程的固定顺序仍须由未来注册和 runner 校验，本模块不允许一次流交叉多个 run。

## 原始记录与状态机

采用 UTF-8、原生 LF 结尾的 JSONL。每条已提交记录的公共字段必须恰为 `schema`、`event`、`protocol_id`、`protocol_sha256`、`purpose`、`pair_id`、`run_id`、`runtime_identity`、`seq`、`monotonic_ns`，并只增加下表指定字段。`schema` 固定为 `mocs-xiangqi-progress-v2-20261025`。

| event | 额外字段 | 约束 |
| --- | --- | --- |
| run_started | 无 | 必须第一条；不冒充已核实的原生创建或就绪时点 |
| game_started | game_index | 从 1 递增；上局结束后才可开始 |
| request_started | game_index、request_index、ply、call_phase | 同时最多一个请求；仅表示即将进入完整 choose 调用 |
| request_returned | 同上 | 与在途请求身份完全一致；仅表示完整 choose 已返回 |
| game_finished | game_index、request_count | 无在途请求；计数等于本局已配对返回数 |
| run_finished | game_count、request_count | 外部要求的各局均已观察结束，累计数完全一致 |

允许顺序为 `run_started → (game_started → (request_started → request_returned)* → game_finished)* → run_finished`。`seq` 从 0 连续递增；`monotonic_ns` 必须是 0～2⁶³−1 的整数且不倒退，允许相等。所有整数拒绝 bool 和浮点；原始解析拒绝重复键、NaN／Infinity、未知字段／事件、非对象、非法 UTF-8、CRLF、超限或乱序。每条身份均须与外部 context 完全相等。

诊断两次调用采用 `request_index=1/2`、`call_phase=cold/warm`，均为 `game_index=1, ply=0`，表示同一权威局面；不是走了两步。训练／holdout 每局 `request_index` 和 `ply` 均从 1 同步递增，`call_phase=play`；四步开局不计入搜索 ply，跨局才复位。已声明终局但无需搜索的局可观察为 0 次请求；其合法性完全交正式结果审核，侧写不证明这种声明正确。

`request_returned.monotonic_ns - request_started.monotonic_ns` 只表示观测到的完整 choose 耗时。没有 NNUE 初始化、实际 go 或 join 字段；冷减热也不能解释为实测初始化时间。未来 producer 必须在调用前后按此边界输出并明确 flush；本模块没有 producer 实现或真实调用证据。

## 截断、上限和权限

流式读取每次最多 4097 字节，单行上限 4096 字节，总量上限 128 MiB，动态事件上限为 `expected_games × (2 × maximum_requests_per_game + 2) + 2`，最大 115394 条。这可容纳 96×600 次调用的开始／返回及边界，不把完整侧写误限在几 MiB。状态只保留计数、一个在途请求、耗时总和／最大值及 SHA，不积累全部记录。

EOF 缺回包时保留 `pending-request` 及开始时间，不生成完成耗时；缺边界时为 `partial-observation`。无 LF 的最后片段始终不消费，即使刚好能解析完整 `run_finished` 也返回 `partial-tail`，保留尾部字节数／SHA 和完整原始流 SHA。调用者须保留原始输入；观测对象不复制尾部原文。已提交的完成记录若存在未配对请求则拒绝；跨游戏跳号、重复请求、结束后附加字节均拒绝。

`sidecar-ended`／`sidecar_ended=true` 只说明该侧写语法和顺序已观察结束，`game_finished` 不证明合法终局或胜负。全部输出始终 `search_authorized=false`、`strength_accepted=false`、`next_slot_authorized=false`。侧写和墙钟都不能替代正式 config／game／summary／exit／audit／finished 审核，不能据此释放锁、允许下一槽或宣布梯度达标。模块的可构造 context 和结果对象本身也不是安全凭据。

## 定向验证与冻结

2026-10-25 使用既有 Conda `MasterofChessStrategy`、Python 3.13.15 标准库，首次定向执行 21 项单测全部通过，耗时 1.520 秒，退出 0：

```text
E:/Backend_Env/Python/miniconda3/envs/MasterofChessStrategy/python.exe -B -m unittest discover -s tools/games/xiangqi -p test_calibration_v2_progress.py -v
```

覆盖冷／热、12 局跨局复位、96×600 请求惰性完整流、外部身份、严格 JSON／整数／字段、有效形状和序号下的非法状态转移、缺回包、各记录边界 EOF、可解析与不可解析断尾、容量及 LF。替身侧写不算真实诊断、训练、比赛或设备测试。没有新增依赖、数值卡、seed、开局、机器冻结 JSON、生产参数或许可变更；旧异常 53 局及旧准入文件没有读取作调参或修改。

| 文件 | 单测前后相同的原始 SHA-256 |
| --- | --- |
| `tools/games/xiangqi/calibration_v2_progress.py` | `4e882b3f000309c5b3d2705e2510fe008b936a4429a339066c9ba0a7778d9f5c` |
| `tools/games/xiangqi/test_calibration_v2_progress.py` | `20e8f08369132506a318c439d4529efaf4b3fd2f06cbe05077f6e913c8737fc4` |

解释器 SHA 为 `cf81d9b404cc58be49f336b7f8c84667ab0a22430889c15d8a9513cdcd722eb0`。本地证据在 `.build/xiangqi-v2-progress-20261025/r1/`：`source-before.json`、`receipt.json`、`unittest.log`；该私有目录不作为干净源前置。后续 Root 审查本契约后，再单独实现 producer、注册规格与正式结果审核集成。
