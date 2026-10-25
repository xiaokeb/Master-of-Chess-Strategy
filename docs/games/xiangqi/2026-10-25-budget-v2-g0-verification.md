# 象棋预算v2：G0规则与进度工具验收

节点 `v0.1.0-xiangqi-budget-v2-g0.1`；基线 `d02bf5ae10550f0f8c04c1f873aa00177403ec10`，干净候选 `xg0a1`，1647源码文件、12份显式覆盖，另附本节点4份元数据。只交付无搜索前置，不代表已经运行诊断、训练、保留集或通过棋力验收。

## 实现与证据

- [固定位置准入](2026-10-25-v2-diagnostic-position-admission.md)：实际规则层重放旧suite1前两条四步线路；既有qsearch初态和应将FEN分别导入规则v4/revision1，不降级历史裁定。指定NDK LLVM编译，最终同源ELF在API37/x86_64/16KiB页设备执行 **413检查通过**，含119合法根的执行/撤销、精确历史恢复与四步初态回退。不是choose或NNUE测试。
- [进度纯验证器](2026-10-25-v2-progress-contract.md)：外部固定context、严格JSONL/字段/整数、单请求状态机与未完成断尾，128MiB流式上限。首次Conda **21项通过**，本候选两份Python源码的原始SHA与该执行前后相同，直接继承，不重复测试或修改断言。观察结果始终不授搜索/棋力/下一槽权限。
- [原生writer](2026-10-25-v2-progress-writer.md)：独立NDK C++17编译；同ELF设备 **12组、115567检查通过**，含最大115394事件、短写/flush/异常/分配失败、永久首错、析构不补终态与locale无关输出。没有链接引擎或创建文件/进程。
- 原生测试ELF输出三份确定性替身流，使用文档事前固定context由实际Conda Python验证器读取：diagnostic 8记录/2请求/3515字节；training 98记录/36请求/43686字节；holdout 115394记录/57600请求/54125403字节。三份全部通过，返回权限均false；这些规模不计作真实搜索或比赛样本。

位置检查ELF SHA256 `30802a68aa97227c54ae9b08a2f47908583c339ce9869d0c7fbf413cbcb142e2`；writer ELF SHA256 `8fe3def8a7303e00e084129b9c65bbe26e54967e7f153657d8ff8f7ac3624c09`。NDK实际包 `30.0.16138531-beta3`；Clang SHA256 `46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be`；Python仍为既有MasterofChessStrategy环境，无新增依赖或MinGW。

位置测试所链接三库来自已验方棋 `fqsp3` 的真实AGP构建，库与源码身份另存收据；不能把它们冒充新真实诊断运行入口或未来冻结运行协议。writer是仅标准库的独立可执行测试；实际编译argv、源码副本/前后摘要、工具及设备ELF身份均保留。无App/JNI/CMake/资源/生产参数变更，基线App构建/Lint/视觉/进程恢复证据的输入未变；本节点只运行受影响工具检查，不重复全量Gradle或把旧UI算作新增验收。

## 失败保留与数据边界

首次位置部署时设备缺失，规则程序尚未执行；核实AVD进程不存在后保留数据启动同一AVD，第一次程序执行通过。writer首次主机重定向捕获流出现每行CRLF，严格Python观察器拒绝；保留该原始失败流，未放宽协议或改writer。改从设备原始文件按字节拉取，逐份远端/本地SHA一致，实际LF流才通过。未来运行器必须保证原始字节捕获，不能把自动换行后的流当原始侧写，也不能规范化已失败的运行记录继续宣布成功。

本地证据：`.build/xiangqi-v2-progress-20261025/r1/`、`.build/xiangqi-v2-diagnostic-positions-20261025/r1/`、`.build/xiangqi-v2-writer-20261025/{r1,raw-fixtures-r2}/`、`.build/xg0a1/`；公开 [清单](../../releases/manifests/v0.1.0-xiangqi-budget-v2-g0.1.json) 固定源码和证据摘要。暂存树应与候选加4份元数据的1651文件干净导出逐个相同，不带方棋学习或其他棋种WIP。

## 尚未完成

没有把writer接入真实choose调用，没有新建诊断/训练/保留集规格、数值卡、seed、开局或执行槽，没有加载NNUE/新搜索/原异常评测重跑。计划中的32次有限冷/热诊断及异常终止权限需未来具体协议另审；当前三个观测权限均false。正式结果config/game/summary/exit/audit/finished、曝光账本、选卡、统计口径、完整梯度、专业规则棋例和最终内容规模均未因此验收。项目完整目标继续。
