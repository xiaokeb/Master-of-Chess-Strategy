# 正式整容器恢复准备器

本节点新增 `data/CompetitionMahjongFormalRestorePreparer.kt`，衔接已发布课程 `909f8ed` 与既有四座 `CompetitionMahjongFormalBackupBundle`。它是严格正式容器的独立准备器，尚未注册生产备份，不改变旧备份可用性或已发布的迁移行为。

## 输入和权限来源

`prepare(records, strategyBindings)` 接收同一次恢复候选的完整记录列表，以及调用方明确提供的四难度策略映射，无默认映射。准备器复制列表，筛选独立 `COMPETITION_LEARNING`，只接受0或1条；重复即拒绝，不挑第一条或已完成的一条。

学习记录复用正式LearningBackupAdapter及MCLP解码。缺失、零章或部分章均未完成；只有四章有效收据才满足教程条件。坏字段、棋种、十六进制、长度和MCLP版本/内容均拒绝。调用方仍负责在进入准备器前验证公共容器版本、所有非本棋种记录和总容量；本API接收记录而非备份头，不能代替公共容器校验。

权限只来自本次候选MCLP，不读取恢复前数据库、内存权限、旧共享四步或旧赛事成绩。由于可信胜场来源尚未建立，简单/中等/困难胜场均固定为0；完整教材最多解锁简单。MCTX统计和历史可以作为历史事实保留，不能用其数量或名次提升难度。策略映射按既有PlayPolicy校验并复制，不宣称Native数字对应的棋力已验收。

## 四座准备与安装边界

构造候选权限后，准备器只调用一次既有 `FormalBackupBundle.prepareReplacement`，传入完整候选记录。Bundle统一处理四座：已有座位完整准入，缺席座位生成明确空MCTX，重复/坏座位或任一准入失败都不返回部分计划。旧 `COMPETITION_MATCH` 冲突仍由既有严格Bundle拒绝，准备器不会过滤旧源使冲突消失。

返回的是完整 `CompetitionMahjongFormalRestorePlan`，不是数据库变更或活跃宿主。实际活动盘会执行既有Native恢复准入，因此宿主须在IO上下文、Room事务之外、清表之前调用本准备器。所有棋种候选都通过后，才可在唯一替换事务中检查目标实际键、清表并安装计划；桥接对象只能在外层提交成功后发布。写者退休、门禁锁序、失败后旧Owner失效和提交失败回滚由统筹保持，本节点没有增加另一条备份链。

## 旧源兼容尚未接入

严格准备器拒绝legacy-only及旧新活跃kind混合。这不等于生产入口应拒绝所有旧备份：当前生产旧adapter仍按原路径处理legacy-only，直至统筹完成正式兼容接线。本节点不删除、修改、隐藏旧行或旧文件。

建议公共整容器准备入口先明确区分三种候选，再注册正式生产adapter：legacy-only保留原导入语义；纯Formal走本准备器；Formal与保留旧源同时存在时必须具有显式、可校验的只归档关联，未经关联的旧新混合继续拒绝。关联只负责无损保存来源，不赋予该来源活动赛事、学习资格或可信胜场。缺席Formal事实、旧源存续、全部实际库存及后续显式迁移还需一并验证，不能靠过滤旧record满足严格接口。

复合信封若重复嵌入MCLP，会增加双份事实与跨记录一致性问题。优先沿用单座既有记录，公共层提供一次整容器准备能力；只归档角色的版本和精确关联由统筹另行决定。本切片不实现新信封、kind别名、源角色、schema或公共registry改动。

## 验证与冻结

2026-11-01，私有Kotlin2.3.21/JDK17/JUnit4.13.2编译无警告，`-ea` 下14/14通过。输入覆盖缺失/0～3章/4章证明、旧共享教程与其他棋种记录、坏和重复证明、legacy-only与旧新混合、重复/末座坏Formal、部分座位、10条历史赛事不提升权限、未解锁活动赛事及显式策略映射。

成功准备的夹具只有空正式库或无活动盘的历史库；活动盘夹具用于验证在Native创建前拒绝越权。本轮没有执行Native恢复、引擎搜索、真实SQLite安装、生产备份或设备测试，不能将14项作为这些行为已通过的证据。

独立入口 `.build/competition-formal-restore-preparer-20261101/check.ps1 -Run r2`；证据在同节点 `r2/`：编译/测试日志、结果、源码与全部二进制依赖前后SHA256均一致。编译依赖明确使用 `.build/cmlp5/source/` 下App/API/Native快照classes；测试夹具使用 `.build/cmlp2/source/` 的JVM classes，`CompetitionMahjongFormalFixtures.kt`、`CompetitionMahjongMatchFixtures.kt`与cmlp5快照源码逐一同哈希。首轮r1误用根build而缺少MCLP/Bundle，编译失败，原日志保留；只修私有classpath，正式准备器和测试未因此改动。

未执行共享Gradle、ADB或Git，未修改既有Bundle、Room、学习文件、公共备份、导航或全局状态。新增准备器、测试与本说明到此冻结，等待统筹后续接入。
