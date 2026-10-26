# 兰州麻将：严格活动会话Room适配

本切片在 [Admission与门禁](2026-10-25-session-admission-epoch.md) 上新增 `games/lanzhou_mahjong/data/RoomLanzhouMahjongAdmittedSessionStore.kt`、同名JVM测试。原 `RoomLanzhouMahjongSessionStore`、VM、旧52项切片、数据库schema、公共备份工厂、JNI和导航均未修改；尚未接正式生产。

## 接口和事务

`LanzhouMahjongStoredSession(record, updatedAtEpochMillis)` 持有已有独占字节/决策集合的不可变LZSR record与非负固定时间。`LanzhouMahjongAdmittedSessionStore.load()` 返回该对象或仅在game3行不存在时返回null；`save(snapshot)` 成功返回才表示该次事务确认。时间由调用者创建快照时固定，重试复用同一对象，不读取系统时钟、不重生ID或选择种子。

生产构造接收真实 `RoomDatabase`、该数据库取得的 `ActiveGameDao`、同库gate、固定捕获Owner和Admission，默认IO dispatcher及真实 `database.withTransaction`。构造核对gate确属于该database；通用RoomDatabase无法反查DAO归属，因此Root工厂必须从同一实例取得DAO，不能据构造参数宣称已证明DAO属于该库。internal构造只提供明确的事务/dispatcher故障注入缝隙。

- load：在固定Owner的 `gate.access` 和IO内先核取消，实际find game3行，交严格Admission，再核取消后返回。Admission在任何copy前按15186字节上限拒绝；结构、元数据或真实Native失败都抛错，原行不删除、不改成新局。
- save：在同一gate/IO内进入单一Room事务，核取消 → 实际Native准入并生成候选行 → 核取消 → upsert独占副本 → 核取消 → find同一game3行并与候选逐字段/字节精确相等 → 再核取消。任一步失败/取消抛出，使真实Room事务负责回滚；不在异常后刷新Owner或返回成功。
- LZSR外层v1可load而不改盘上字节；显式save才规范新写v2，原LZMJ规则v1/v2及69字符ID不改。辅助次数/决策种子由LZSR保真，行辅助列沿用0；新Store不发奖励、不补额度或开放难度。

Gate只等待并保护本地所有者，公共恢复须由Root使用同gate与既有固定锁序包围。它不自动清表、不持久化epoch，也不能替代VM的可等待Native退出与页面退休。

## 当前定向验证

保留 `.build/lanzhou-mahjong/admission-epoch-20261025/r1/`：旧五件精确副本与15项结果未改写。本轮在独立 `r2/` 使用既有JDK17/Kotlin2.3.21、Room2.8.5实际runtime类、协程1.11.0和缓存依赖，单CPU/512MiB，**16份当前真实源码首次编译通过，27/27 JVM通过**（0.200秒、退出0），16份源前后SHA相同。

27项为原新增Admission9/门禁6与新Store12，不与既有52相加冒充正式总数。新Store检查：缺行与其他棋种隔离、完整辅助/决策往返、坏行/超限保留、数据库读失败仍是错误、Native准入确在事务内、写后读回不符、读回异常、写后异常、upsert挂起取消、同步准入返回前取消且无upsert、同快照重试保留ID/时间/字节、旧Owner不刷新，以及旧外层v1仅显式升外层v2。部分相关断言在同一方法，未扩大用例数。

事务模型明确是内存故障注入；Native canonicalizer明确是替身。Room生产构造与真实Native默认路径仅编译，没有执行SQLite、JNI、Gradle/KSP、ADB、页面或Git。实际源码、依赖jar摘要、Java身份、完整编译/测试argv、退出码、日志和jar保存在r2；它们不冒充真实Room取消语义、Native合法重放或跨进程证据。

## 后续

Root审查/冻结后再接实际工厂及VM固定快照重试，补真实Room/JNI测试：v1/v2内外组合、辅助不补额、存档损坏保留、触发器等真实SQL中途失败回滚、取消、完整备份恢复和旧Owner拒写；最后正式候选构建、设备及双字体视觉。十课持久化/资格、牌桌返回清退、备份注册、四档棋力、音效及完整棋种仍需后续节点，本切片不授予这些完成状态。
