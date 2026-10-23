# 正式人机 Session 即时完成测试计划

## 状态与 API 假设

新增 `GoFormalSessionCompletionInstrumentedTest.kt`，共 **6 个设备测试方法**。当前仅编写与人工核对，**未编译、未运行**，不是验收通过证据。基于已发布 `8b5f6b0`／schema 18 数据接口，以及统筹拟接入的以下 Session API：

- 构造器末尾 `completeHumanImmediately: Boolean = false`，不改变默认训练行为。
- `GoAiMatchUiState.completionStored: Boolean = false`。
- `retryCompletion(): Boolean`；固定终局未成功存储且未正在保存/未关闭时可重试，成功后返回 false。
- 正式开关开启且 sink 非空时，真人双方确认后先持久 reservation，再即时提交 sink；恢复终局也幂等提交，不等待 restart。

正式初始 GAI 由真实 `RoomGoRankedMatchCreator` 预先原子创建，Session 仅恢复同一 UUID，不假装已验证未来的 Session 正式创建端口。

## 六项方法

1. 白方真人对脚本黑方双虚、双方确认后立即产生一份棋谱/投影/outcome/settlement，UUID 不变，无需 restart。
2. 完成 sink 事务末端 I/O 失败时，持久终局保持固定不可悔棋，派生行全部回滚；一次同局 retry 成功，重复重试不再派发。
3. 失败后等待 Session 关闭，真正关闭并重开磁盘 Room；恢复同局自动提交再失败，显式 retry 成功且没有新局。
4. 已完成局恢复后再次幂等调用 sink，仍一份成长与棋谱，收藏不丢，固定结果不可悔棋。
5. 省略新构造器开关，默认训练终局不即时调用 sink、未 reserved、可请求并成功执行悔棋；终局分叉采用新 UUID，保留父归档并记录 TERMINAL_CONTINUATION。
6. 已有正式初始来源但后续永久 automaticUsed 为 true，完成仍写普通棋谱和投影，不产生成长账本或 marker。

## 真实依赖与隔离

每方法拥有独立 UUID 磁盘 Room 数据库。每 Session 有独立 SupervisorJob 父 scope；所有控制在 `Dispatchers.Main.immediate`，规则/存储沿 Session worker。关闭时等待 `closeAndJoin()` 后再取消父 scope、关闭 Room；只删除本方法自己的测试数据库，不清应用数据。

规则使用真实 Native；搜索只按脚本返回 Pass，不加载 NN、不宣称棋力或性能。白方依真实贴子在空盘双方确认后获胜。没有 Compose/视觉、真实搜索、Session 新建来源端口、进程杀死或备份退休覆盖。

## 终局悔棋边界核对

初次审查只看 checkpoint 禁止已归档身份回退进行中，遗漏 Session.commit 已为终局悔棋生成新 UUID，曾误报同身份冲突。经统筹指出并重读当前代码，已撤回该误报。第 5 项验证真实撤销成功、新 UUID、父归档保留与延续来源，不要求旧归档身份回退，也不需更改旧 Room 逻辑。静态核对仍不等于设备通过。

## 交接

本任务只新增此文档和测试文件，不运行 Gradle、NDK、ADB 或 Git，不改旧策略/成长/备份测试。统筹接好最终 API 后应冻结候选、编译并运行 6 项，保存逐项结果与 APK/源身份，再更新本计划的实际验收状态。
