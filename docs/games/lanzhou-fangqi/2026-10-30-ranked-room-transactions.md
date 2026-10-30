# 方棋正式来源事务交接

新增`RoomFangqiRankedRepository`，生产构造仅接受实际`MocsDatabase`，从同一实例派生方棋gate及固定Owner。所有方法按gate→IO→真实Room事务执行，操作前后检查取消；读取创建、结算、活动、成果与棋谱完整快照后调用Root的`FangqiRankedSnapshotValidator`。没有通用统计补数、自动修复坏行或恢复后刷新旧Owner。未接正式页面或公开新局入口。

## API与写入

- `load()`返回验证后的完整快照与可信胜场；失败保持失败。
- `admitNew(creation, expectedPrevious = null)`在事务内重读实际学习与验证胜场并核资格，校验真实标准根。null要求空活动槽；非null只允许精确命名当前已结算终局，原子替换活动局并保留旧创建/收据/成果/棋谱。旧局、未结算终局和任意活动局不能借此静默放弃；显式abandon另待。创建确认丢失仅在同创建及同初始活动快照时幂等返回，不能重建已丢失或已变化的活动行。
- `checkpoint(before, after)`核实际活动CAS、固定身份/难度/初始根、时间及辅助计数单调。向前历史须扩展全部原动作；回退须为原动作前缀且undo恰增1；hint只能在历史不变时恰增1，各档额度不得超限。结构检查之后仍执行真实Native Ledger。精确after重试可确认；非重试先重放原活动局，原结果必须仍ONGOING，已经出现的首次终局不能再undo或继续修改。
- `firstTerminal(terminal, settledAt)`要求终局先成为已确认活动检查点，随后真实Ledger裁定并原子写Outcome、完整棋谱和首次收据。终局检查点写成但尚未结算时不授奖、不允许修改，只能继续确认首次结算。同一终局重试沿用冻结Ledger的首次收据/时间；有显式删除标记时返回原收据，不补建棋谱。尚未实现把未确认终局检查点与首次结算合并为一步的新CAS API，调用者须遵守上述顺序。
- `deleteRecord(id, deletedAt)`要求已验证完整首次收据，删除时间不早于结算；同事务执行NULL→首次时间CAS及按ID删除棋谱，然后验证收据/成果仍在。已删除请求仅允许原删除时间重试，不从缺棋谱推断删除，不重写历史/结果/首次时间。

正式棋谱字段采用格式1、普通人机、红方人类0、非残局，完整bytes和动作数等于终局，完成时间等于首次settledAt。收藏位可独立变化，不作为不可变来源冲突。DAO只扩展`markRecordDeleted(id,time)`和`deleteCompletedRecord(id)`，未添加upsert或来源覆盖。

## 验证边界

私有Kotlin2.3.21/JDK17编译使用实际Room运行时/注解、现有fqrs1真实App/Engine编译类及五份真实源码，没有生成stub。`r3`编译0警告，14项纯检查点/显式替换策略测试通过（0.024秒）；夹具的动作文本刻意只是合成结构，不代表合法Native对局。它们验证前缀、计数、身份、额度、时钟以及只替换明确已结算旧局的策略，不是SQLite事务、并发、取消回滚、真实终局、数据删除或Native验收。

随后`r4`增加原活动局真实终局保护，最终五源私有编译通过0警告。纯策略helper和测试未变，未重复运行14项；此新增Native守卫仍待Root真实Room/Native专项，不计入14项覆盖。证据根`.build/fangqi-ranked-transactions-20261030/`保留r1参数路径反斜杠失败、r2原12项、r3补替换策略14项、r4最终编译及源SHA。

没有运行Gradle、ADB、SQLite、Native或Git，没有修改共享DB/备份/导航/实体。Root后续应验证实际生成DAO、事务提交/读回、取消及SQL故障回滚、精确重试、已删棋谱不复活、跨epoch旧Owner拒绝、实际终局禁止undo、已结算旧局显式新建和完整备份关联，再决定发布。
