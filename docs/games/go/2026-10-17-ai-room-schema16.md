# 围棋 AI Room schema16 数据切片

更新时间：2026-10-17。本切片只实现专属实体、DAO、事务 Store 与测试夹具；共享数据库迁移、库存和封口由 Root 串行集成。生产 `aiRepository()` 不在此切片切换，完整 AI 备份、棋谱列表、成长和正式 AI 导航均不因此完成。

## 专属存储

- `go_ai_sessions` 保存唯一 Go 活动槽的完整规范 GAI1 v6、来源、可空父谱引用及更新时间。
- `go_ai_archives` 保存稳定 recordId 对应的首次完整终局 GAI 和完成时间。DAO 使用 ABORT 插入，没有覆盖更新接口；结果固定与合法结束系列只改变活动槽，不重写首次归档。
- 两表 `replacedTerminalRecordId` 均延迟引用归档主键，更新/删除为 NO_ACTION，并建父列索引。清活动槽不会删父谱；完整替换须在同一事务先清活动，再清全部归档。外键不代替 Native 终局准入或全图环检测。
- 来源显式码为 1 遗留未核验、2 会话未核验、3 终局替换上下文；不使用 enum ordinal。三类均无排名资格。GAI 自动参与字段不证明纯自动或可信标准参赛身份。
- 行转换校验所有者、时间、版本、来源与完整规范 envelope；输入/输出字节均防御复制。此层只做结构边界，真实规则、完整历史、父终局及不可变归档检查仍由现有 `GoAiCheckpointRepository` 完成。

`RoomGoAiCheckpointStore` 的 `database.withTransaction` 同时覆盖旧源首次迁移、终局归档、活动写入和永久封口；失败或取消应整笔回滚，不调用共享 GameRecord 或奖励写入器。

## Root 共享接线约定

- `MocsDatabase` 增加 `GoAiSessionEntity`、`GoAiArchiveEntity`，并提供 `abstract fun goAiCheckpointDao(): GoAiCheckpointDao`。
- schema15→16 只建立空表及索引，不能在 SQL 迁移猜测 Native 或读取文件，也不提前生成 AI 封口。旧 schema 保留，schema16 由正式 KSP 生成。
- `GoLegacyImportScopes.AI_MATCH` 是新的稳定旧源 scope，加入 ALL；成功全量恢复（包括不含 Go 的旧容器）在原替换事务封全部四源。安装内较高 handledVersion 不降级。
- 新 Room 行须加入独立备份库存槽。AI 适配器尚未注册时应明确拒绝遗漏它们的导出/恢复；现有 `GoAiDiskBackupGuard` 不撤销。
- 生产工厂继续使用当前仓储，待完整备份与搜索/会话/Native 独立清理任务真正完成的屏障一起集成，再切换 Room。不能只看 viewModelScope 已取消就认为运行时清退。

## 已编写的验证，尚未执行

1. `GoAiCheckpointRowsTest`：7 项纯 JVM 行边界测试。完整策略字段、防御复制、稳定来源码、首次归档时间、坏元数据/校验/版本、自父与完整双历史容量。这里的合成字节不是 Native 合法性证据。
2. `RoomGoAiCheckpointStoreInstrumentedTest`：10 方法，使用真实 Room；规则相关测试用真实 Native。覆盖完整活动与首次归档、reservation/结束系列、终局替换父谱/clear、旧终局首次直接 save、各阶段写入后错误/取消回滚、真实 Job 取消、ABORT 不覆盖、延迟 FK/孤父/父子全量清理、坏行/坏封口及较高封口/完整 envelope 容量。最大容量用例仅证明 BLOB 存储边界，不伪装为真实 Native 长局。
3. `GoAiRoomMigrationInstrumentedTest`：1 方法，schema15→16 保留旧字节、收藏、时间、来源与较高封口；两表为空、不提前封 AI，核对延迟 NO_ACTION 与外键一致性。

本切片没有运行 Kotlin/KSP/Gradle、NDK、模拟器、截图或 Git。上述仅为待执行测试清单，不能称为已编译或已通过；Root 后续收据才是运行证据。缺适配器备份拒绝与全来源恢复封口测试由 Root 在共享边界测试中负责，避免重复夹具。

## 下一依赖

Root 接共享 schema/迁移/库存/封口后，先进行必要编译及定向 JVM，再执行真实迁移与 Room/Native 用例。完整备份准备阶段应只对每份来源 Native 准入一次，并复用结果检查全体父引用、孤源、重复身份和任意长度来源环。当前每次保存仅验证直接父，不声称已做全图校验。

后续共享棋谱投影须扩展 Go 现有唯一所有者，保留完整原始归档，明确处理 GameRecord 10000 步与 Native 更大事件上限差异；不能截断历史或把三种未核验来源升级为标准胜场。
