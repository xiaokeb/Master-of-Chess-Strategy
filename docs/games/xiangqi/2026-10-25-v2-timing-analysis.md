# 象棋预算v2：纯耗时观察投影

本切片补齐 [G0进度契约](2026-10-25-v2-progress-contract.md) 的本地分析层：仅消费已经通过既有`observe`验证的`ProgressObservation`、当次验证所用外部`ProgressContext`，以及可选外部同进程单调时钟采样。模块可由Python导入，没有CLI、文件读写、进程创建、ADB、计时器、搜索、注册或真实producer装配，不改变冻结G0源码。

## 接口与可报告事实

`calibration_v2_timing.summarize(observation, validated_context, sample=None)`返回不可变`TimingObservation`。`ClockSample(run_id, runtime_identity, monotonic_ns)`的纳秒值须为0至2⁶³−1的整数；布尔、浮点、负数和越界值拒绝。

- 已配对返回请求报告`completed_requests`、总时、最大值和精确`Fraction`均值；只代表这份侧写已观察到的完整choose区间，不是纯搜索时间、全样本性能或棋力结果。
- 没有已配对请求时，均值和最大值均为`None`，不能以0代表缺测。已返回且确实零耗时的请求仍可报告0，与无样本不同。
- 在途请求保留原`PendingRequest`。只有显式同run/runtime采样且不早于请求开始时，才报告`sample.monotonic_ns - pending.started_ns`为已等待**下界**；缺采样为`pending-unmeasured`和`None`，不虚构完成耗时或用门限填补。
- LF未提交的返回断尾仍属在途，不能加入已配对计数/总时。前缀已返回样本与尚未返回样本分别列出；未完成前缀的均值不是完整运行的均值。
- G0只保留总和/最大值，没有各请求分布。因此p95始终为`None`，`full_latency_distribution_available=false`，不从sum/max反推分位数。

输入的请求配对数、已完成耗时、当前game/ply/phase、状态和结束声明须相互一致，否则明确抛出`TimingError`；不通过补回包、改状态、丢样本或猜终局修复。`sidecar_ended`只转录侧写结束观察，不证明正式结果或运行成功。

## 时钟归属与权限边界

采样必须来自该producer进程的**同一单调时钟**，主机墙钟、主机单调时钟、另一子进程或设备重启后的新时钟都不能直接代入。声明的run_id/runtime_identity与外部context不匹配、或采样早于已知请求开始时会拒绝。

现有G0观察结果本身不携带run身份或物理时钟证据，因此模块不能认证调用者是否把另一份观察结果和context错误配对，也不能验证一个声称同run的数值真的来自该进程。调用者必须保留同次`observe(stream, context)`关系并由未来执行器建立实际进程/时钟来源；`physical_clock_origin_verified`始终false。身份匹配只校验声明，不构造安全凭据。

所有输出固定`search_authorized=false`、`strength_accepted=false`、`next_slot_authorized=false`。本模块不使用2秒或10秒提案作为判断，不产生超时、停止、通过、选卡、完整分布或下一槽结论；没有生成数值卡、seed、预算、协议实例或新评测。G1终止权限仍须另审，不能由此模块或广义开发授权推导。

## 实际定向验证

2026-10-25，既有Conda `MasterofChessStrategy`、Python3.13.15标准库，首次定向**15/15通过**，0.005秒、退出0。测试以真实G0观察器读取自编替身JSONL，覆盖精确均值、零样本/零耗时、在途下界/缺采样、时钟倒退/最大int64、声明跨run/runtime拒绝、前缀与未提交返回断尾、跨局身份、零请求声明、非法投影与不可变性。这些不是实际冷/热choose、设备性能或物理时钟验证。

```text
E:/Backend_Env/Python/miniconda3/envs/MasterofChessStrategy/python.exe -B -m unittest discover -s tools/games/xiangqi -p test_calibration_v2_timing.py -v
```

本地收据 `.build/xiangqi-v2-timing-20261025/r1/`保留解释器身份、命令、测试日志、源前后SHA；三件新增模块/测试/本文最终摘要见`handoff-freeze.json`。旧G0 progress原始SHA前后保持不变；不重复21项旧测试或无关Android全量验证。Root审查后才能独立集成，当前未接producer/执行器、App/JNI/CMake或发布流程。许可沿用项目GPL-3.0-or-later，无新增依赖、权重或媒体。
