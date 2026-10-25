# 象棋 v2：固定诊断位置的规则准入

本切片完成 [下一阶段计划](2026-10-24-calibration-v2-next-plan.md) G0 中的四位置准入，不执行G1冷/热choose，不实例化协议、候选卡、采样seed或新评测槽。

`budget_v2_diagnostic_positions.hpp/.cpp` 提供固定四项只读目录和 `make_diagnostic_position(id)`。前两项复制旧训练suite1第1、2条四步线路，并从标准开局实际逐步重放，保留完整历史；不是只导入终点FEN。后两项保持既有 `pikafish_budget_safety_test.cpp` 的两个原始FEN。标准初态由正式规则构造；受将军诊断局面使用实际MOCX v4/revision1初态序列化结构、替换明确六枚棋子并重新计算CRC，交正式restore规范校验，不降级为旧规则版本。

该导入器仅接受四个固定枚举，未知ID拒绝。目录逐项保留来源、原始FEN、历史长度与预期红方受将军状态；工厂核对精确FEN、红先、非终局、有合法根及历史长度。没有通用任意FEN导入、搜索或局面筛选接口。规则版本或序列化结构改变时明确失败，须重新审查固定夹具。

## 真实验证

2026-10-25，指定NDK30 LLVM/Clang，x86_64-linux-android24、C++17/O2，`-Wall -Wextra -Wpedantic -Werror` 编译通过。链接方棋最终候选 `fqsp3` 中实际AGP构建、源身份已核验的core/Pikafish/KataGo静态库；使用段回收，不以该链接宣称诊断入口具备真实搜索或权重加载能力。四个象棋输入与库来源均记录于本地证据，不修改任何库或旧预算结果。

同一ELF在 Pixel_7_Pro / API37 / x86_64 / 16KiB页模拟器退出0，**413项检查通过**：

| 固定位置 | 保留历史半回合 | 正式合法根 | 红方受将军 |
| --- | --- | --- | --- |
| suite1-opening-1 | 4 | 34 | 否 |
| suite1-opening-2 | 4 | 38 | 否 |
| qsearch-initial | 0 | 44 | 否 |
| qsearch-checked | 0 | 3 | 是 |

逐项验证规范序列化恢复、完整搜索历史不变、非行棋方未受将，以及每一个正式合法根都可执行并撤销到完全相同的历史字节。前两项再撤销原始四步，确认回到正式标准初态；未知ID拒绝。这里“搜索历史”是规则快照数据，没有调用搜索算法。

ELF SHA256 `30802a68aa97227c54ae9b08a2f47908583c339ce9869d0c7fbf413cbcb142e2`；stdout SHA256 `73b982aa8f8791a6b2b305af30eaa475f1fbc2203824469f030190a29ef35b7c`，stderr为空。证据根 `.build/xiangqi-v2-diagnostic-positions-20261025/r1/`：三件源码副本/前后摘要、完整编译argv/工具摘要、三库摘要及设备收据。第一次部署时ADB无设备，程序未执行；核实AVD进程不存在后保留数据启动同一AVD，首次规则程序执行通过，不把部署缺设备记为规则测试结果。

## 后续边界与复现

本轮只新增独立目录、实现和测试，无App/JNI/CMake/生产参数修改；未清应用数据、加载NNUE、生成新位置、读异常53局或运行旧评测。未重复无关UI、音效或全量Gradle。三个新源可在干净标签的NDK构建所得同ABI库上独立编译：包含目录为 `engine-native/src/main/cpp/include`，编译本目录实现与测试，静态库组链接并加 `--gc-sections`、z/log/atomic/dl/pthread；具体本轮实参见收据。私有 `.build` 文件不是源码前置。

未来真实诊断仍须绑定这些位置的完整初态/历史、来源和曝光账本，完成进度writer及独立运行协议的无搜索审查；冷/热耗时、返回着、choose不改变局面、真实进程异常与终止权限均尚未验收。`search_authorized=false`、`strength_accepted=false`；四位置准入不会授权G1、G2或G4运行。
