# 方棋学习持久化：公共接入边界

前置本地对局节点 `f3f92364a2a01a7bd8f7b2c25066b11b14110578` 已发布；本计划不改写其验收。下一补足实际六课和三项自编练习的学习进度，再判断人机资格入口，不能把学习WIP默认空回调算作持久化。

## 专属实现

专属对话只新增 `games/lanzhou_fangqi/data/*Learning*.kt`、对应测试和专属说明，提供Entity/DAO、不可变完成集合、严格内容键/版本、Store及Room适配。构造注入RoomDatabase、DAO和同数据库Fq gate/owner；同一事务内校验、写入、读回和取消检查，重复完成保留首次时间。坏数据报错保留，不默认为全未学。

公共TutorialProgress固定四步，EndgameProgress绑定象棋关卡与奖励，均不能借用。练习提交须绑定实际关卡种子、红先、规范完整Native历史及红胜无待吃；JVM verifier替身不替代JNI。只记学习完成，不写成长/胜负奖励，也不据客户端完成记录宣称防作弊认证。

## Root串行接入清单

1. 审定专属表与DAO的最终名称后，在 `MocsDatabase.kt` 注册Entity/DAO，schema18→19，只新增独立表。显式18→19迁移保留全部既有棋局、围棋来源关系及学习记录；生成schema19并做真实旧库迁移/重开验证。其他棋种不得同时占用该版本号。
2. 在 `GameBackupAdapter.kt` 新增专属 `FANGQI_LEARNING` 槽，限定game4；在 `BackupStorageInventoryDao.kt` 与 `BackupStorageInventory.kt` 完整报告该表，在 `LocalDataBackupRepository.kt` 同一替换事务清除该表。缺适配器且实际有行时拒绝，不能默默丢学习数据。
3. 新专属 `FangqiLearningBackupAdapter` 规范化六课/三练习的内容键、版本和首次完成时间；建议一个完整集合记录，容器5、独立kind、最多9项、稳定排序。准备阶段持有不可变值，write只执行同一Room事务的写入，不嵌套gate/清表。旧备份无学习段时清空该集合，失败事务保留旧集合。不得借学习导入给成长账本发奖励。
4. 在 `ProductionGameBackupFactory.kt` 注册学习Adapter；沿用Fangqi→Go→Room的完整备份锁序，学习写者使用同一Fq epoch。恢复退休旧页面写者，成功后新owner重新加载；失败恢复的旧owner同样不能擅自刷新身份继续写。
5. 学习页面改接真实Store、显示载入/写入失败和显式重试，在确认落盘后更新界面；配置重建、重进及杀进程恢复保持完成集合。练习Native所有者须清退，不能在Compose销毁时阻塞Main。最后按PRD接资格条件，不额外开放未验AI梯度或自动发奖。

## 验证与发布

只测受影响范围：专属严格输入/重复/回滚/退休JVM，真实SQLite迁移和关闭重开、Native练习准入、完整备份导入导出/旧备份/无Adapter拒绝/失败回滚、正式学习导航与双字体视觉。只在最终候选完成相关构建/设备检查后发布学习节点；schema19占位及私有测试不算生产可用。

当前专属Store尚在实现，公共schema仍18，未注册新表/备份/导航；此文件只锁定Root公共变更和测试责任，不能作为学习节点已完成证据。
