# 围棋 AI 原始终局到唯一共享棋谱的纯投影

本切片仅提供投影及不可变比较，不接 Room 事务、导航、备份注册或成长。依赖已发布 AI 原始来源与生产存储节点。提示词不是本切片执行指令。

## API 与同源验证

`projectGoAiRecord(archive, engineFactory)` 由完整首次 `GoAiCheckpointArchive` 生成一份 `GameRecord`。默认使用真实 `NativeGoEngine`，必须在 worker 执行。函数不接受调用方另外传入的准入元数据，以避免把某局的阶段/结果与另一局字节拼接。

处理顺序：严格规范 GAI1 v6 decode/encode校验 → 独立规则引擎恢复/完整历史与指定开局准入 → 重新编码逐字节同源校验 → AI域SHA身份匹配、真实终局及非自父校验 → 完整共享棋谱投影。父谱是否存在、图是否有环、当前活动固定状态与归档是否匹配，仍由后续 Room/备份事务核验；纯投影不能凭空证明这些数据库事实。

输出沿用现有 `MasterofChessStrategy:go-ai-record:v1` 域隔离 SHA ID；保存完整 MOCG，不缩成盘面或 SGF；着数只统计 Place/Pass，不计死子协商/确认事件；时间取首次原始归档时间。无写库、奖励、自动收藏或来源升级副作用。GAI原始归档必须继续保存，不能用共享投影替代完整开局、辅助次数、参与和来源信息。

## 模式与执色

| 完成配置 | 既有模式 | playerIndex |
| --- | --- | --- |
| 人机，包括让子/指定开局/曾切自动再返回人机 | HUMAN_VS_AI | 人类黑0、白1 |
| 当前双方AI，无指定开局 | AI_AUTO_PLAY | 0，不伪造人类执色 |
| 当前双方AI，含指定开局 | OPENING_AUTO_PLAY | 0 |

不新增枚举，也不把混合参与的人机局伪报纯自动。人机让子/指定开局仍用允许白方执色的既有 HUMAN_VS_AI 模式；训练性质、让子与开局完整事实从权威 GAI/MOCG取得，不用不支持白方的 CUSTOM_POSITION 容器丢失执色。`automaticUsed` 是永久参与事实，不是当前模式，始终保留在权威 GAI。

难度显式映射 SIMPLE→EASY、MEDIUM→MEDIUM、HARD→HARD、MASTER→MASTER。投影难度是配置标签，不证明实际棋力已标定。

当前三种来源 LEGACY_UNVERIFIED、UNVERIFIED_SESSION、TERMINAL_CONTINUATION 均不提供标准胜场/星级/积分资格；人机模式标签、完整合法历史、CRC和新ID均不能代替可信参赛来源。不要直接送入要求人机必有outcome的象棋完成 DTO。未来来源资格和成长另行版本化，不能从本切片的记录倒推补发奖励。

## 重试与冲突

`requireSameGoAiRecord(existing, candidate)` 严格比较 ID、棋种、模式、难度、结果、完整字节、格式、着数、执色、残局标记及首次完成时间，只忽略 `isFavorite`。事务应保留已有收藏，不以重试默认false覆盖它。

candidate 始终来自首次原始归档，所以正确重试不会产生不同时间。相同 ID/历史但首次时间不同也拒绝，防止掩盖导入冲突或与严格备份配对矛盾。字节按内容比较，不使用 Kotlin data class 的 ByteArray 引用相等。比较不修改任一参数；事务自己仍需取得防御副本并核权威来源。

## 私有验证与剩余门禁

`.build/go-ai-record-projection/private-r3/` 使用已发布 gac6 编译类作 friend 与既有缓存依赖，JDK17、单CPU、512MiB、`-ea`、协程debug/recovery开启。两个新增源码输入前后散列一致，编译退出0、JUnit **13/13**。

用例覆盖9/13/19与0/2/5/9让子、每档与人类黑白、混合参与、纯自动/指定开局自动、人机白方指定开局、AI域ID及错误拼接/自父拒绝、双确认之前拒绝、坏GAI校验/版本、准入规范化字节替换拒绝、时间及不可变内容冲突、收藏保留、防御复制，以及全部现有来源不升级。超过64KiB的4096行棋动作/4098总事件夹具验证完整历史不截断。

JVM规则是明确的MOCG形状替身，不证明真实棋局合法；长局夹具重复落点也不冒充合法Native长局。真实 Native 终局、完整事件、Room事务、备份配对和回放仍需统筹下一阶段设备验证。r1诊断驱动因PowerShell未引用带点JVM属性导致主类启动失败，没有运行测试；r2为13项之前的12项版本，日志保留，不累计为额外用例。

本切片没有 Gradle、NDK、ADB、Git 操作，没有新依赖/媒体/权重，也没有第二个 `game_records` 所有者或完成谱文件库；不能宣称首次完成事务、成长或围棋AI入口已经实现。
