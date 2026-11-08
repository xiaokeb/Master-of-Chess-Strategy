# 正式赛事生产备份适配器

`CompetitionMahjongFormalBackupAdapter`注册既有Formal槽、v5、最多四席与版本化Wire预算。整容器准备由公共batch接口调用一次，单条准备明确拒绝；格式规范化不调用Native。策略映射由调用方明确提供并防御复制，只从同一候选MCLP读取教程条件，可信胜场仍为零。

导出加入宿主既有Room事务，枚举全部实际座位、分块读取并验证完整MCTX，再读取同快照旧源；各席Wire绑定同一canonical旧记录。未知键、异常归属、坏或空载荷拒绝，不能过滤遗漏。目标校验只做结构检查，实际候选Native准入在清表事务外执行。

LegacyOnly安装仅在外层事务删除正式归属行，旧源仍由原适配器同事务安装，允许后续显式迁移；Formal安装完整四座计划，保留已存在租约的epoch/revision演进，不向调用方提前发布Bridge。全局恢复在任何表清理前调用`requireStoredTarget`，Prepared写入阶段再次校验，不能用后者代替前置保护。

Root已在生产工厂注册适配器，明确策略身份EASY→0、MEDIUM→1、HARD→2、MASTER→3；这不证明四档强度。全局Competition存储gate和后续正式宿主负责失败恢复时的页面退休；SQL回滚会保留原持久租约，不能宣称直接数据库事务已经退休所有旧句柄。

## 当前验证

适配器14项JVM已纳入`cmfb1`正式构建。JVM使用DAO与事务替身，SQLite和Native行为由设备测试覆盖。

专属3项和生产7项在Pixel 7 Pro模拟器上通过，覆盖结构目标校验、实际Native候选准入、清表前保护、四席及旧源往返、LegacyOnly后明确迁移、空候选补四席、候选资格隔离和SQLite故障原子回滚。正式赛事入口另行验收。详见[验收报告](2026-11-01-formal-backup-verification.md)。
