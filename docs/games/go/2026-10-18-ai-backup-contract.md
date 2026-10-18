# 围棋 AI 原始来源备份契约（2026-10-18）

本节点是 Room AI 活动槽、首次终局原始归档的备份前置，不是正式 AI 页面、共享棋谱或成长系统验收。实现未自动注册到生产备份，也未替换生产 FileRepository。下列测试描述是用例范围；执行结果须由统筹附实际收据，不能把 JVM 替身当作 Native 或设备证据。

## 所有权和格式

容器继续使用版本 5；各新 kind 的格式字段为 `1`，GAI 来源必须是规范 GAI1 v6。`createGoAiBackupAdapters(admit)` 同时创建两个适配器，共享一个仅本次准备过程使用的准入 key。

| kind | 字段（按顺序） | 存储槽／最多条数 |
| --- | --- | --- |
| `GO_AI_SESSION` | GO 编码、1、更新时间、来源码、父谱或 `-`、完整 GAI 十六进制 | `GO_AI_SESSION`／1 |
| `GO_AI_ARCHIVE` | GO 编码、1、recordId、首次完成时间、来源码、父谱或 `-`、完整 GAI 十六进制 | `GO_AI_ARCHIVE`／200000 |

来源码固定为 `1=LEGACY_UNVERIFIED`、`2=UNVERIFIED_SESSION`、`3=TERMINAL_CONTINUATION`，不用枚举序号。只有第三种必须附父谱；其他来源禁止附父谱。它们均不是排名资格或“纯自动”证明。父谱和记录 ID 采用 64 位小写十六进制；记录 ID 必须等于现有 AI matchId 域隔离 SHA-256。时间只接受规范非负十进制 Long；十六进制只接受小写偶数形式，不接受数字别名。

两个 prepared 只写 `go_ai_sessions`、`go_ai_archives`，不声明 `completedRecordIds`，不写 `game_records`。现有 `GO_LOCAL_RECORD` 仍是所有围棋共享棋谱的唯一备份所有者；原始 AI 归档存在而尚无共享投影在本前置阶段是允许的。后续正式完成事务和投影接入时再扩展该唯一所有者及配对校验。

## 容量与完整性

完整 GAI 最大为 **2000172 字节**：两个各最多 1000000 字节的 Native／指定开局图像，加完整头和 ID。单条 wire 上限为 `MAX_GAI * 2 + 512`，即 **4000856 字节**。不按单份 Native 图像上限截 GAI，不裁剪最大 16386 个事件的完整历史。

全容器仍受 128 MiB、扩展合计 128 MiB、容器总行数 200000 等现行限制；单适配器的条数上限不承诺所有条目一定同时装得进一个容器。超限必须整体失败，不能只备份前 N 条、丢弃父谱或截断历史。最坏尺寸的 33 份 GAI 已接近总字节预算。

当前 `GameRecord.MAX_MOVE_COUNT=10000` 仍小于 Native 完整历史范围。此节点不创建受该限制的棋谱投影；正式投影前须由统筹协调按棋种的步数预算，不能以截断历史或限制合法 Native 对局来迁就旧公共上限。

## 准入与快照关系

结构校验和导出行编码不开 Native。准备阶段先校验完整 GAI decode／encode 字节完全一致，再使用真实 Native 准入并要求返回的规范 GAI 仍逐字节相同。每次 `GameBackupRegistry.prepareRestore` 拥有独立 context；两个适配器的 typed cache 只在该 context 存活。缓存用 SHA-256 分桶并再次比较完整字节；失败不缓存，哈希碰撞不复用。完全相同 envelope 仅准入一次；reservation 或续局控制不同则是不同 envelope，各准入一次。prepared 不持 context，guard／写入不重新开 Native。

统一 guard 检查：

- 活动槽至多一个，归档 ID 不重复；所有归档均已双方确认终局。
- 所有父谱在同批归档中存在，禁止自父及任意长度的环；使用迭代遍历，不递归至历史长度。
- 未终局活动 ID 不得已归档；终局活动必须有对应首次归档，来源、完整历史、开局、辅助次数、自动参与等内容一致。
- 首次归档 reservation 不能从 true 回退；活动可从 false 固定为 true。系列控制只可原样，或由原启用状态关闭为 1/1；不得重新开启、改上限或改序号。
- 首次归档原始 envelope 和完成时间保留，活动更新时间独立保留。

终局内容规范化与 `GoAiCheckpointRepository` 的现有私有纯定义同义；后续公共策略窗口应合并，避免长期维护两份判断。父谱只代表已记录的替换上下文，不宣称证明了父子历史前缀。

## 测试与生产切换前置

专属 JVM 用例覆盖严格字段、显式来源、完整容量、重复、父谱缺失／长链／环、活动与首次归档控制、不可变字节、缓存内容与碰撞桶、同一适配器的并发独立 context。注入的阶段／胜负事实明确是替身；本文件不宣称它们已执行或证明真实棋局合法。

生产切换前必须继续完成：

1. 真实 Native 准入、Room 全量导出／恢复／失败回滚和外键延迟父图的设备验证。
2. AI owner 登记到统一写入者边界，提供同一个幂等可等待的清退 job：禁止新加载／重试／续局／落子，取消并 join opening、session、搜索，再释放规则引擎和模型。
3. 修复 `GoAiMatchViewModel.onCleared` 另建异步 cleanup scope 的屏障缺口。现有 `awaitLocalDataWriters` 仅抓取 viewModelScope 子任务，不能证明该 cleanup 已结束。仅 pause 或 epoch 不等于 Native 清退。先在 gate 外清退，再获得备份 gate，避免 cleanup 写入等待 gate 导致死锁。
4. 四个旧源导出前同事务迁移；恢复不导入将被替换的旧源，成功替换同事务永久封口，包括不含围棋的旧容器。
5. 覆盖正式 Room AI 和旧文件三种映像后再移除拒绝保护、注册生产适配器和切换 aiRepository／页面 fallback。覆盖不足时继续 fail closed，不静默遗漏 AI 数据。
6. 正式 AI 棋谱投影、完成账本与成长资格另行按统一事务实现；本前置不授予奖励。
