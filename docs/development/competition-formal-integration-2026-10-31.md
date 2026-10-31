# 国标正式赛事公共集成窗口

2026-10-31阶段1已完成：真实schema22、DAO及缺adapter库存保护通过57项JVM、23设备方法/25调用与双字号12PNG/8不同画面直接检查。见[验收报告](../games/competition-mahjong/2026-10-31-formal-schema-verification.md)。下一阶段2–4继续，不能把数据库前置称为完整MCTX/正式宿主/备份已交付。

当前已发布HEAD：`69583ad`。方棋正式入口与可信成长两个节点已完成推送；公共schema仍21。国标预留schema22，替代10月26日桥交接中的旧21编号。以下是下一实施窗口，不能当作已完成的集成或发布。

## 输入与所有权

复用已冻结的 `match/storage/room/CompetitionMahjongFormalRoomRows.kt`、`FormalRoomStorage.kt`、`FormalRoomBridge.kt`，以及10月31日新增的 `data/CompetitionMahjongFormalBackupBundle.kt`。旧单赛事 `competition_mahjong_matches` / `COMPETITION_MATCH` 继续保留原义；不把它改名为完整正式赛事库。新增批量闭包私有31项、Room桥私有测试不代替KSP/SQLite/Native验收。

Root独占MocsDatabase、备份枚举/库存/registry、完整恢复事务/工厂和公共导航；棋种专属新增代码保持独立，旧冻结文件只在明确集成需要时做最小修改，不搬迁其他工作区文件。构建、ADB、Git串行。

## 依赖顺序

1. **Room/schema前置。** 注册现有单表Entity/同步DAO，21→22只建新表，保留旧MCMT和所有棋种数据；不在迁移中复制旧源。把新表纳入库存检查。完整adapter接好前，新表一旦有归属行，公共导出/替换必须明确拒绝，不能静默漏导或清空。先验KSP、实际旧库迁移、64KiB分块、CAS与缺adapter保护，再判断该前置是否可独立发布。
2. **正式宿主与旧源屏障。** 审核现有MCMT/MCST/MCHR写者及时间/延迟回调的可等待退休；由一个宿主acquire一次，三个Route共用同一repository。可信PlayPolicy来自真实学习/成长，目标待迁移时走明确预览/确认，v1不补造正式难度。当前旧writer不读新lease，不能只靠新表CAS声称已经隔离。
3. **整容器备份。** 清表前一次性调用批量prepareReplacement，全部结构和实际Native准入成功才进入唯一写事务。缺座写明确空MCTX、旧源同时封存；拒绝把旧COMPETITION_MATCH混入此正式替换。四座安装结果只在外层事务提交后发布，失败整体回滚。读取加入外层已有事务，不在SQLite事务里claim、加仓库Mutex或进行权限/Native挂起调用。
4. **生产与设备闭包。** 按实际文件冻结完整依赖；定向验证多座替换、末座损坏零写入、失败回滚/旧lease、跨棋种库存与摘要、真实Native活动v2–v4、三Route同源，再检查普通/150%字体、进程恢复与视觉。每个可验证节点独立提交/tag/源码Release，未完成部分明确列出。

不清默认应用数据、不重启模拟器、不启动象棋搜索、不加入未经核准第三方资源。需要真实数据破坏、许可变化或产品规则冲突时另提具体决定；常规实现按既有授权继续。

## 阶段3已确认的接线缺口

只读审查发现尚未发布的Bundle使用`[viewer,mctxHex]`，与公共registry要求首字段为持久棋种代码不符。正式接线前改为`["2",viewer,mctxHex]`，同步编解码、长度预算和测试；不复用旧COMPETITION_MATCH格式。当前schema节点没有注册或调用该Bundle，不受此问题影响。

Bundle目前只遍历0–3座，而新表库存只报告棋种槽。未来槽注册后，这不足以发现viewer9等越界行，可能漏导并在恢复时残留。正式适配器接线前必须枚举实际主键并拒绝越界归属；增加“适配器已注册但存在非法viewer”的导出/恢复保留测试。当前无适配器时任意行均被阻断，不能把本轮坏viewer测试外推成注册后的完整保护。
