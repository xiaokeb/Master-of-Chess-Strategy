# 游戏备份扩展契约 v1

2026-10-13，Root公共接口迁移。此版本是内部实现契约，不是新增线格式；保留现有MOCS-BACKUP v1–5。

10月15日状态补充：生产源码迁移后的正式98项JVM、29项真实磁盘/Native与普通/150%界面10项/18图已通过；新增兼容配置后3项冒烟通过，schema13不变。此前“尚未正式集成”只描述10月14日时点。完整混合APK与仅虎羊候选分开验收，后者及第三方源码/许可门禁仍在进行，详见[正式集成报告](../testing/2026-10-15-registered-backup-integration.md)。

`GameBackupRecord(kind, fields)` 只含kind后的规范线字段，防御复制、不可变；拒绝分隔符、CR/LF/NUL、无效UTF-8及超128MiB单条。其相等/hashCode按内容。棋种包络含义由专属codec掌握，公共代码不列举棋种载荷类，不使用Any。

`GameBackupDescriptor` 固定kind、最低容器版本4/5、持久化槽、数量/字节上限和规范顺序。当前虎羊优先于国标，不受注册顺序影响。Registry拒绝重复kind/槽、核心kind及象棋活动槽抢占、错误game code、外层版本不足、数量/单条/扩展累计字节越限。低于128MiB的附加字节预算可用于测试或更严格策略，不能提高全局限额。

`GameBackupAdapter.read` 只读取，必须由公共Repository包在完整快照事务内调用；Registry拥有固定注册副本和读取顺序，检查读前/后descriptor未变、输出kind归属及所有限额。`validate`仅检查字段包络。`prepareRestore`必须在任何清库前完成实际Native准入并关闭Native，返回只持有owned、不可变已准入数据的PreparedGameBackup。Prepared的槽必须与adapter相符，`write`只在公共单事务内写本槽，不自行清库或另开替换事务。

BackupStorageInventoryDao独立只读枚举两张实际表的全部gameTypeCode，不影响现有ActiveGameDao或棋种测试替身。未知code、国标表错误owner都拒绝；库存准入缺adapter时拒绝，不静默丢数据。schema始终13，国标表/迁移不因模块缺失删除或回退。

生产导出顺序必须是：同事务库存准入＋读取核心/扩展快照 → 事务外全部实际Native准入 → 规范编码。恢复顺序必须是：完整解码/准入及全部Prepared → 停写屏障完成 → 单Room事务内再次库存准入 → 清表＋全部写入，任一写失败整体回滚。Codec编码前还须计入核心行/头/CREATED/SHA尾行和换行，不能用Registry仅扩展字节检查代替128MiB最终容器限制。

10月13日基础18项JVM与6项SQLite/注册读取通过，详见 `../testing/2026-10-13-game-backup-registry.md`；这是迁移前证据。10月14日公共Codec/Snapshot/Repository及App显式注册已在源码中接入，迁移后轻量JVM通过；**尚未完成正式集成与真实Native/磁盘回滚设备验收**。详见 `../testing/2026-10-14-registered-backup-migration.md`，不重复未变的原生规则/OOM/JNI。

## 10月14日规范化补充（私有验证）

新增codec.normalize与registry.normalizeRecord：先检查注册身份/容器版本，再由专属codec按旧解析语义将数字拼法、HEX等规范化，返回同kind的owned记录，最后检查规范槽归属/字节/包络。不能先按规范行预算拒绝旧可解析数字文本，也不能切换成另一已注册kind或game。不会调用Native；实际准入仍只在prepare阶段完成。

当前Root registry20项与虎羊adapter13项在同一私有Kotlin源编译/测试中33/33通过；国标adapter另10/10，未混称一次43项合跑。两adapter默认准入复用既有实际Native/关闭函数，但这些私有测试注入lambda，不证明新路径的真实JNI/Room已运行。源码身份见 `../development/freezes/game-backup-normalization-20261014.tsv`；旧10月13日基础8清单保留历史，新增接口不能套用其正式18项。

专属报告见 `../games/tiger-goat/2026-10-14-backup-adapter.md`、`../games/competition-mahjong/2026-10-14-backup-adapter.md`。旧规范化清单及上述33项属后续helper/回调后身份检查前的历史；当前Registry21项、新Codec8与WireBuilder5合跑34/34。虎羊Codec仅注册虎羊，原跨棋种混合方法保留为独立公共集成测试；核心测试显式空注册。下一正式集成、真实Native/磁盘/回滚及候选源码树验证，不能套用迁移前冻结/设备证据。
