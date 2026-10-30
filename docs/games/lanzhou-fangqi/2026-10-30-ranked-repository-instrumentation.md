# 方棋正式Room事务仪器验证计划

新增`games/lanzhou_fangqi/data/FangqiRankedRepositoryInstrumentedTest.kt`，9个仪器方法，均通过既有`withOwnedLanzhouDatabase`使用UUID专属磁盘库。只触及该库；不清默认应用数据。读取实际Repository、同DB DAO及真实Native完整历史准入，不使用DAO替身或伪结果。此交接仅完成源码/静态接口核对，未运行Gradle、ADB、SQLite或Native，执行与结果由Root正式候选记录。

## 用例范围

1. 实际课程不足拒绝创建，完整正式版本1学习行不凭空授中等10胜资格；创建精确重试保持ID/开始时间，修改时间或静默创建另一局拒绝。
2. `BEFORE INSERT ON active_games`注入SQLite故障，检查创建/活动行整体回滚，同原请求可重试。
3. 标准根真实黑胜53动作，经检查点→首次结算，重复结算保留首次时间；不增加人类有效胜场，改变终局辅助元数据拒绝。
4. 标准根真实红胜50动作，只增加一次简单有效胜场；真实棋谱完整bytes/计数和独立收藏标记核验。
5. 对Outcome、棋谱、收据三处INSERT分别使用新的UUID库安装SQL故障trigger，三族不留部分写入、创建与活动终局保留，删除trigger后可按原时间重试。一个JUnit方法内包含三次独立故障场景，不重复计为三个方法。
6. 删除棋谱时SQL失败回滚删除标记与记录；随后精确删除幂等，拒绝改写首次删除时间；原收据/Outcome/有效胜场保留，首次终局重试不复活棋谱，重开数据库后仍保持删除事实。
7. 已观察到但尚未结算的真实终局，禁止undo及新建替换；原终局精确检查点重试可确认，随后首次结算。
8. 真实合法历史前1/2动作构造CAS场景，旧before不覆盖新检查点；确认丢失重试和数据库重开保持原完整bytes及计数。
9. 已结算原局不能通过无expected的新建覆盖；明确精确expectedPrevious后可创建下一局，旧创建/首次成果/棋谱和有效胜场均保留。

## 共享夹具与边界

同文件提供`internal object FangqiRankedRoomFixtures`，供Root备份测试复用。`start(db,id="ranked-qa")`向新测试库写入catalog全部9个正式key、内容版本1、完成时间1，再以固定开始时间10正式创建；预期库尚未存在相同学习键，不做IGNORE/upsert。`terminal(initial,redWin=true)`从仪器包assets的`games/lanzhou_fangqi/ranked/standard-{red,black}-win.mocs-lfq`读取完整合法历史，只去文件末CR/LF文本换行；终局时间20、动作数从完整history结构检查取得。Root负责将已生成并核验的两个终局资产纳入正式候选，不由本任务复制资产或运行生成器。

这两局是明确QA输入，用于真实规则重放和事务验证，不是棋力/难度梯度评测。SQL trigger同为明确QA输入，不冒充自然发生故障。跨epoch、完整生产备份和取消等其余门禁由Root的独立专项覆盖；本9项不据静态代码宣称其完成。
