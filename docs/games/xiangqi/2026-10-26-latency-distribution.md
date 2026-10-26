# 象棋 v2：完整调用耗时分布

新增 `tools/games/xiangqi/calibration_v2_latency.py`。`analyze(raw: bytes, context)`先以既有G0观察器严格验证原始快照，再从同一不可变字节读取已提交记录，不在验证后重开路径。只接收原始bytes及外部固定context；128MiB总量、4096字节单行及请求上限沿用G0。最多保留96×600份返回样本，不改冻结G0代码。

每个样本保存game/request/ply/phase及同一producer单调时钟的开始、返回时间。统计包含返回数、整数总时、精确Fraction均值、最大值、nearest-rank p95（第`ceil(0.95×N)`个）、严格超过2秒的数量。冷/热只使用诊断记录明确声明的cold/warm；训练/保留集统一标play，不猜缓存、档位或引擎内部阶段。未来按档位合并必须另核权威调用配置。

没有样本时均值/p95/最大值为None，实际零耗时保留为0。缺返回请求只保留pending，不填成10秒，也不进入分位数；无LF断尾不消费。部分侧写仍可给已返回前缀的分位数，但`sidecar_distribution_complete=false`；完整侧写只说明语法结束，不证明合法终局、全程成功或物理时钟来源。2秒只是产品响应观察目标，不触发取消或通过裁定。

输出与样本均不可变，附原始流SHA；样本数量/总时/最大值再次与G0独立累计核对。所有搜索、棋力、下一槽权限固定false，没有CLI、文件写入、进程、ADB、真实choose或NNUE加载。本模块补上[纯耗时投影](2026-10-25-v2-timing-analysis.md)刻意不提供的分布；两个接口分别消费原始流和聚合观察，不能由sum/max反推p95。

## 实际验证

2026-10-26，项目Conda Python3.13.15标准库首次定向 **14/14通过，0.005秒，退出0**；代码、测试与G0直接依赖前后SHA相同。命令：

```text
E:/Backend_Env/Python/miniconda3/envs/MasterofChessStrategy/python.exe -B -m unittest discover -s tools/games/xiangqi -p test_calibration_v2_latency.py -v
```

自编JSONL覆盖20样本的第19个分位点、跨12局、冷/热分组、严格2秒边界、未返回/未提交返回、缺样本与真实零值、外部身份、重复键/bool/NaN/CRLF/非法配对/倒退时钟、结束后附加数据、不可变输入输出及int64极值。原始记录由真实G0观察器验证；测试不调用引擎，不是设备性能数据。证据位于 `.build/xiangqi-v2-latency-20261026/r1/`。

既有timing模块的15项测试及全部直接Python依赖/解释器SHA与原收据一致，明确继承10月25日结果，不重复运行或将其计成新测试。两个模块合计29项，G0旧21项不累加。只有Python工具与文档变化，Android、JNI、C++、模型、生产参数及资源均继承已发布基线；没有重跑Gradle、NDK、App、模拟器或视觉，也不作这些范围的新验收声明。

真实producer、独立构建闭包、32次诊断和异常终止策略继续按[实施计划](2026-10-24-calibration-v2-next-plan.md)推进。新源不授权G1搜索，不读取异常53局作分析或调参，不生成候选卡、seed、曝光账本或一次性运行槽。源码发布沿用项目许可；Pikafish/NNUE及KataGo现有许可限制不变，无新增第三方内容和二进制附件。
