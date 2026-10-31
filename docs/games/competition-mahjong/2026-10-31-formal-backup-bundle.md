# 正式赛事完整备份批量闭包

本节点复用既有 MCTX、RoomBridge 与 RoomBackup，维护国标专属备份批量编解码、恢复计划和测试。schema22前置已由统筹发布（`4a8a050` / `v0.1.0-competition-mahjong-formal-schema.1`），表/DAO及缺适配器库存保护已存在；本Bundle尚未注册生产备份 kind、导航或正式宿主，不代表完整正式备份已经可用。10月26日交接中的旧schema21编号不再适用。

## 已完成

新增 `data/CompetitionMahjongFormalBackupBundle.kt`，提供：

- `readInTransaction(database, dao)`：先确认调用方已有Room事务，再调用同步 `listViewerIds()` 枚举实际主键。全部键必须互异且处于0–3，任何未知行均在读取首个payload前拒绝，不能只遍历合法范围而漏导坏归属。有效键按座位排序读取完整MCTX；枚举后缺行、待迁移归属及损坏内容均拒绝，不冒充空库。事务内不调用Native、不加外层Mutex、不切线程。
- `encode(viewer, bytes)` / `normalize(record)`：正式kind为 `COMPETITION_FORMAL`，字段固定为 `["2", viewer, MCTX十六进制]`，首字段与公共registry的棋种所有权契约一致。先复制并完整结构校验，再输出。上限4条，每条预算仍为 `32 + 2 × MCTX.MAX_BYTES` 字节，编解码显式检查整个记录长度，含棋种/座位字段；该预算足够容纳新增的棋种字段。旧未发布二字段形状与错误棋种均拒绝。保留原MCTX字节，不携带owner、epoch或revision。
- `prepareReplacement(wholeContainerRecords, trustedPolicy)`：接收完整容器列表，忽略其他棋种，拒绝任何旧 `COMPETITION_MATCH`，拒绝重复 viewer、超过四条、跨 viewer、坏编码、损坏或超限 MCTX。全部结构检查成功后才逐座使用可信权限及原有真实 Native 准入。返回前已处理四座，缺座生成明确空 MCTX，不会回落为待迁移状态。
- `CompetitionMahjongFormalRestorePlan.installInTransaction`：只接受外层已开启的Room事务，安装前先枚举并严格检查目标表全部实际座位，再逐座调用旧桥安装并轮换lease。发现未知座位时不开始任何claim/write。计划不持有数据库事务、Native引擎或活跃宿主。

活动赛事继续由现有 `CompetitionMahjongFormalRoomBackup.admit` 校验权限、真实 Native 重放及 v4 已付提示。没有注入可默认为成功的验证器。历史、统计、收藏和删除标记仍按既有 MCTX 同源校验，没有改写旧结算规则。

## Root 精确接入与生命周期

1. schema22表/DAO/SQL migration已发布，Root新增无过滤同步 `listViewerIds()` 查询属于下一接入节点，仍需正式KSP/实际SQL验证。本次只改Bundle、对应测试和本交接文档，不修改公共DAO或旧Storage测试。
2. 公共备份枚举、slot、inventory已有 `COMPETITION_FORMAL`；registry/factory和容器摘要计数仍待接入。保留旧 `COMPETITION_MATCH` 的原单赛事语义与显式迁移身份，不能把MCTX写入旧kind。生产导出从 `readInTransaction` 取记录，待读事务结束后完成公共准入。
3. 公共恢复需要整容器级准备位置：**清表之前**调用一次 `prepareReplacement`，传入该次恢复的完整原始记录列表与可信权限。旧 kind 单独存在或与新 kind 混合，均拒绝此正式替换路径；另走显式旧源迁移，不能丢弃旧记录后制造四座空库。普通逐记录 adapter 不能独自补齐缺座，也不能用逐条 prepare 冒充本步骤。
4. 只有全部棋种、全部候选都准入成功，且所有现有/旧源写者、计时与延迟回调已经退役后，才进入公共唯一恢复事务。**Root还须在任何清表前、同一写事务内预检现有正式表的全部实际viewer；该公共检查尚未接入。** plan的安装前检查不能代替更早的清表前检查。在该事务中调用一次 `installInTransaction`，并按旧交接同时封存旧MCMT/MCST/MCHR来源。任意后续写失败由公共事务整体回滚。
5. 保存返回的四个 bridge 为本次事务的待发布结果；**外层提交成功后**才能把对应 viewer 的唯一 repository 交给正式赛事、统计、历史三 Route。事务失败即丢弃结果。计划仅限该次恢复调用，不缓存为全局对象、不跨另一轮权限快照重用。
6. 导航/生产工厂仍需提供真正教程与成长记录生成的 PlayPolicy；不得从备份文本直接授予难度权限。公共恢复锁、旧 writer retirement 和封源不由本类代替。

## 验证证据与限制

首轮历史证据：私有脚本 `.build/competition-mahjong-formal-backup-20261031/check.ps1` 使用缓存Kotlin 2.3.21、JDK17、真实Room API与Native Kotlin门面编译；当时编译exit0，旧二字段Bundle10项、RoomStorage15项及MCTX Codec6项共31/31。该轮未经过公共registry，不能作为修后三字段与实际viewer枚举的验收；原证据保留。

修后测试继续覆盖四座补空与排序、完整历史/统计/收藏/删除标记字节保留、旧源混入/重复/超量/末座损坏拒绝、规范化、防御性复制及未解锁难度在Native创建前拒绝；新增四座均使用公共棋种前缀、旧二字段/错误棋种拒绝、整记录预算、实际座位扫描/非法或重复键零读取、枚举行缺失拒绝、无事务拒绝，以及坏目标座位零安装写入。新增读/安装测试使用真实Room运行时类型的明确事务/DAO替身，没有SQLite、真实Native或共享registry集成。

修后私有证据：`.build/competition-mahjong-formal-backup-20261031-r2/check.ps1` 使用既有JDK17、缓存Kotlin 2.3.21及真实Room运行时JAR。编译退出0；Bundle20项＋Root更新DAO假对象后的既有RoomStorage15项＋MCTX Codec6项，合计 **41/41**。编译有一条既有PlayViewModel测试的非空断言警告，没有修改该测试。`compiler.log`、`junit.log`、`result.json`和前后相同的 `source-hashes*.json` 留存于同目录；`dependency-hashes.json`记录实际classpath。该私有回归不改写旧31项历史，不等于正式Gradle/KSP或设备验收。

未运行共享 Gradle/KSP、真实 SQLite、真实 Native、整容器注册/全局恢复事务、宿主退役、AndroidTest、模拟器视觉或实体设备；未暂存、提交或推送。Root 接入后仍需真实验证四座替换与失败回滚、缺座封源、旧宿主失效、活动 MCMT v2–v4 的 Native 准入、跨棋种容器摘要及三 Route 同源。
