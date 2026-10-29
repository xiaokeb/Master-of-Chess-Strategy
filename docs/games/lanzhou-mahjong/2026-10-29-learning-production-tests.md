# 十课真实页面仪器测试交接

新增`app/src/androidTest/kotlin/com/masterofchessstrategy/games/lanzhou_mahjong/LanzhouMahjongLearningProductionInstrumentedTest.kt`，只使用已有UUID磁盘库夹具`LanzhouMahjongOwnedDatabaseFixture`。页面为真实`LanzhouMahjongLearningViewModel`与`LanzhouMahjongLearningRoute`，仓库直接使用`RoomLanzhouMahjongLearningRepository(fixture.db)`；VM在UI线程构造，数据库调用走IO。没有旧Store、DAO替身或内存Repository。

## 两个方法

`actualTenLessonAnswersPersistAndSqlFailureRetriesTheOriginalTimestamp`实际点击十课的答案。首课先答错并确认学习表仍空、下一课锁定；对UUID库安装`BEFORE INSERT` trigger制造真实SQLite写入错误，确认未掌握且课程/答案锁定。移除trigger后点击重试，确认时间函数只调用一次，Room保存首次捕获的1000，即使QA时钟已经变为2000。之后逐课点击正确答案，每课断言题目可见、未完成前下一课锁定、写后独立Room快照计数增加；重点截取缺门示例手牌与24/72倍炸胡说明。完成后复习不再读取时钟、不改变快照，原通用四步教程行不变，通用胜场表保持空，返回回调检查退役成功且仅调用一次。

`productionRestoreRetiresTheVisibleOwnerAndRouteReturnsAfterRetirement`预置一课后打开真实页面，通过生产factory导出完整备份，实际答完第二课，再通过生产factory恢复原备份。确认可见旧VM被epoch退役，答题节点消失；调用旧页入口亦不能写，新的真实Repository读回原来一课。点击Route返回后检查retirement完成和回调唯一性。

## 证据边界与执行

AtomicLong时间函数及SQL trigger是显式QA输入，用于验证时间只捕获一次与真实SQLite错误恢复，不冒充设备真实时间或自然发生的存储故障。它们没有包装Repository；同一个Advance对象的身份由既有JVM测试覆盖，此处通过真实存储结果证明时间没有重采样。备份为完整生产factory，不手写学习扩展恢复步骤。

不在测试内设置系统字体。统筹用普通字号与150%字号分别执行同一方法，每轮使用新`mocsVisualToken`，严格匹配`qa-[a-f0-9]{32}`。截图写入测试应用`getExternalFilesDir(null)/mocs-visual/<token>/`，目标已有同名文件时失败，避免覆盖旧证据。学习流程固定7张`learning-01-initial`、`learning-02-failed`、`learning-03-retried`、`learning-04-example-hand`、`learning-05-false-win`、`learning-06-complete`、`learning-07-returned`；恢复流程固定3张`restore-01-before`、`restore-02-retired`、`restore-03-returned`。每方法另写metadata，记录真实fontScale、退役确认以及QA时钟/故障标记。十课各题依赖可见断言，不循环截十张课程图。

清理顺序为UI线程同步`beginRetirement()`，测试线程IO等待其completion，然后UI线程清理ViewModelStore，最后关闭并删除该夹具独有UUID库。未读取或清理默认数据库，不清应用数据。

本交接只增加上述测试和本文，未修改冻结生产代码、旧测试、Root数据文件或共享配置，未调用Gradle、ADB、Git。源码编译和设备执行交统筹合并候选串行处理；在统筹记录出现前，本文件不宣称编译通过、两方法运行通过或视觉验收完成。
