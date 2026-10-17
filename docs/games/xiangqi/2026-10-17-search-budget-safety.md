# Pikafish 搜索预算安全：独立修复节点

## 旧作业边界

原 medium-hard 已于2026-10-16 23:47:08非零退出1073807364；53条完整game不含summary或后验审核。Root未停止、重跑或改写该轮。一次性槽仍为已消耗且不具最终分析资格，不能跳过失败槽启动原hard-master。异常运行根因尚未确认。

旧上游 `search.cpp` SHA256为 `55bbb359f0f1f52663921401cec08e9d908ba8b5ad7e94a230b900089ea0e209`；原源码在已发布 `c62df0c7c8d87d7242e077052028cf5d484f6b48` 及独立冻结候选中保留，原训练/保留集库、报告和协议不改。新修复不授予约7:3或Elo验收，不与旧局混算。

## 实施范围

- qsearch在规则/TT/求值前轮询现有预算；主线程调用计时器，全部线程读取原子stop。不重复增加节点。
- 递归返回后先撤销走子及NNUE累加器，再传播中断。占位VALUE_ZERO不是和棋结果，禁止用其更新PV或搜索TT边界。
- 普通搜索补齐空步/verification、ProbCut、同层singular、LMR及PV重搜的传播，恢复对应状态后退出。
- 每轮主线程初始化重置callsCnt；不改变生产档位、权重、候选偏差或地方规则。
- 新专属测试位于 `engine-native/src/main/cpp/games/xiangqi/pikafish_budget_safety_test.cpp`。两份上游头仅增加friend测试访问，无对象布局或公开生产接口变化；GPL和NNUE许可边界不变，修改记录在 `third_party/pikafish/UPSTREAM.md`。

## 真实基线与后续验收

`.build/xiangqi-budget-safety-20261017/` 单独保存源码清单、构建和设备原始日志。NDK30/x86_64实际链接原冻结Pikafish库，NNUE设备SHA核对为 `7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e`。

补丁前真实qsearch 11项全部按预期失败：预先停止仍写root TT，过期时间/单节点均未设置stop。两诊断位置覆盖初态吃子及受将军后的非吃子应将，PV/NonPV；它们不进入未来棋力保留集。首次夹具因NodeType命名空间写错未编译，已修正并保留首次日志，不将编译失败冒充引擎失败。

提前约定普通预算端到端容差2秒（从go到join，不含网络加载/清表），外部stop到join容差1秒；它是本模拟器验收阈值而非操作系统硬实时保证。

## 15:27 正式原生验证

构建脚本 `tools/games/xiangqi/build_search_budget_safety.ps1` 使用新目录，拒绝覆盖既有结果和活动评测锁，固定NDK30 LLVM身份。两个新目标分别只链接真实Pikafish，不使用替身。真实双ABI库和两个可执行程序已完整编译链接；ARM64仅编译链接，未设备运行。Windows长路径警告涉及未构建的其他棋种测试目标，实际两目标均成功，不隐去日志。

输出目录 `.build/xiangqi-budget-safety-formal-20261017-r1/`，构建收据SHA256 `b22f3096c2242258c4c43017d9847c11301e756e0450d5fe76c7a12a4dae54b7`。本次 `search.cpp` SHA256 `b760fd489469fb095d8ac395164fc1b37ec1dece93872b13c6f1ff269665cab5`。

真实设备日志在该目录 `device-8e34ee5c0cb54ba99db4fd12a2cd040d/`，两个程序退出0：

- qsearch 11/11，新增NNUE栈顶地址恢复断言；同时验证FEN/key/state指针、根PV/TT和节点计数。先前手动链接的11/11保留为前快照，不累计成22项。
- Engine公开API 20/20：Threads1/2 × MultiPV1/4 × nodes1、nodes128、movetime10ms、外部stop、同实例resume64。每组重放真实两步历史、限制四个正式合法根，不在请求间清表；每次bestmove恰一次，PV不逃出根集合，所有worker局面/key/state回到根，公开历史端点不变，实际节点均大于0。
- 正式时间预算实测12ms；外部停止请求前等待约100ms，go至join为103–104ms，stop至join按毫秒取整为0ms（不宣称物理零延迟）。双线程可略超节点预算，未声称硬实时或精确全线程节点上限。
- 两程序设备SHA分别为 `c9325f015906eadf1051cf2e4320addf6c3679ab62c797e8b1144df9a19b1d98` / `f147a5ab75fa5b54d1c2a53612c29a91688bade8d93d8b9285b94a6e991ac1c8`。运行脚本 `tools/games/xiangqi/test_search_budget_safety.ps1` 核对源码清单、设备二进制及NNUE SHA，并逐程序保存退出码/日志摘要。

首runtime夹具因const ThreadPool无begin编译失败，修正夹具参数后通过，首次日志保留。随后手动链接20/20是前快照，本次正式20/20另有身份，不累计成40项。正式构建的qsearch增加栈断言及完整CMake链接，需要本次定向验证，未重复Go/其他棋种全量。

## 尚未验收

新停止传播代码已通过只读交叉审查，但有限专项不证明所有复杂静态树的延迟上界，更不证明旧评测异常根因。仍需精确干净候选上的正式APK/JNI、受影响历史回归与必要界面冒烟；实际ARM64/旧API/实体设备、长局功耗以及新版本独立棋力训练/保留集尚未验证。不把原一次性槽重跑，不改生产四档参数，不发布混合开发APK；本修复尚未提交或建立tag/Release。
