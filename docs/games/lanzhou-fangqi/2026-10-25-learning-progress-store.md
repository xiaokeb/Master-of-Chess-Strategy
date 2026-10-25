# 兰州方棋学习进度：专属存储切片

本切片接续已发布的本地对局节点。新增4份方棋专属生产源码、2份JVM测试；未修改已发布的57份源码、公共schema、备份注册或导航。Root后续负责schema 19、迁移、备份与正式页面装配；此处不能据私有测试宣称已经生产可用。

## 内容与数据契约

- `FangqiLearningProgressEntity` 对应 `fangqi_learning_progress`：`contentKey` 主键、`contentVersion`、首次完成时间。版本1固定现有自编6课与3练习；键分别为 `lesson:<原课ID>` 和 `practice:<原练习ID>`，总计至多9行。内容语义或练习种子变更须显式升级版本，不静默继承旧完成记录。
- DAO的 `readAll()` 按主键排序，最多读取10行；第10行用于识别超过9行的损坏表，不把任意多坏数据先加载进内存。`insert()` 冲突中止，`clearAll()` 仅供后续协调备份事务使用；Store不自动清数据。
- `FangqiLearningSnapshot` 复制并冻结集合，拒绝未知键、重复键、过多行、非版本1与负时间；错误保留原始记录。`tutorialComplete` 保留既有学习模型的六课显式确认定义，练习另记；本切片没有开放任何难度或发成长奖励。
- 不复用公共固定4步的教程位图，不向象棋残局奖励表写入方棋ID。首次确认保存时间，重复确认不改时间；无记录只返回真实空集合，打开页面本身不完成课程。

## 生产接口

`RoomFangqiLearningStore(database: RoomDatabase, dao: FangqiLearningProgressDao, gate, owner)` 由Root装配。构造核对gate属于该database；**DAO归属不能由该构造证明**，Root必须从同一MocsDatabase的正式accessor取得DAO，不得混入其他DB或替身。Entity/DAO目前为internal，公共accessor可见性需保持一致。

- `load()` 在对应Fq epoch gate内实际读表、严格校验，异常不会变成空进度。
- `acknowledgeLesson(id)` 仅接受课程catalog键；`completePractice(id, completeHistory)` 在复制前拒绝超过既有1MiB会话上限的输入，进入gate前拥有证据副本。
- 写入先验证证据，然后在IO dispatcher执行同一Room事务：读取原集合、合并一条或保持原条、读回完整集合逐字段比较。事务真正返回才确认完成；插入、读回、commit或取消失败均传播，不返回成功。
- Store只捕获一次Owner；恢复替换后旧Owner、不同gate的Owner均不可读写，也不能触发Native验证。与完整备份仍共用既有Fq gate，不在适配器内重入gate或另开事务。

私有测试构造允许显式注入DAO、事务函数、时钟和verifier，不是默认空保存。正式构造使用真正 `RoomDatabase.withTransaction` 和 `FangqiLearningPracticeAdmission`。

## 练习完成证据

`FangqiLearningPracticeAdmission` 从现有 `LanzhouFangqiLearningContent` 读取指定题目的自编初始棋盘。输入必须为版本1完整历史、初始红先、同一49点种子且动作数1～16384；不接受孤立UI结果。

随后创建该题目的真实Native会话，先核对Native生成的初态，再恢复整条历史、比较Native规范序列化原始字节，最后要求红胜且无待吃子。这样其他练习、标准开局、黑先或非法历史不能借用一张胜利Snapshot通关。函数退出均关闭会话；不调用AI选着。JVM测试中的会话替身只验证传输与拒绝边界，真实历史合法性/终局仍须Root的JNI专项。

## 验证与冻结

最终私有证据 `.build/fangqi-learning-store-20261025/r3/`：既有JDK17、缓存Kotlin编译器、单CPU/512MiB，对13份真实源码编译成功；**20/20 JVM通过，0.208秒**，源前后SHA一致。包含14项Store/投影测试和6项Native传输替身测试，覆盖首次时间/幂等合并、六课/练习分离、坏行保留、读回/提交失败与取消回滚、提交前不确认、Owner退休、并发合并、证据复制和超限前置拒绝，以及三练习种子/红先/规范历史/终局要求。

测试日志SHA256为 `0ec5397d179adf5eae6b4067872107ab901c8455424c1ee085eb1c5ad3750b79`。r1的私有classpath内部类型归属错误记录保留；r2纳入真实 `LanzhouFangqiAutosave.kt` 后20项通过。随后DAO增加10行损坏哨兵上限，再对最终源码执行r3，不能把r2直接套用到最终源。

| 新增源码/测试文件 | 最终原始SHA256 |
| --- | --- |
| `FangqiLearningProgressEntity.kt` | `8e41aa9e1b35ece34e9e993fbdc3cdfad0ab27f45e62820fcd98cbae782fad46` |
| `FangqiLearningSnapshot.kt` | `26269ca859acc1a85abbf8d09f4dac7e40ee8a42b38c8d760e6bc6d9ab78575f` |
| `FangqiLearningPracticeAdmission.kt` | `7412e6f058a627b527026e2e1039ed2b800d58901417a58b193b6abf5a12375e` |
| `RoomFangqiLearningStore.kt` | `6337791e4818291cc29b16090ae0b0da1782d8d8887c00172ea704380fe332c1` |
| `FangqiLearningStoreTest.kt` | `d8754903b203526337896866f7bc3600aec6497f2e7ab743a9022239c9e60ddf` |
| `FangqiLearningPracticeAdmissionTest.kt` | `871fafa54b0533c91c3fe6e32efa163af76bae10d5c3ccc29e384d22016e9902` |

没有运行Gradle、KSP、NDK、ADB或真实Room/SQLite/JNI，未提交推送。后续须验证18→19迁移保留原有数据、同DB DAO装配、真实三关通关/负例及幂等首次时间、完整备份/缺段恢复与旧Owner退休，再接正式学习页面和资格；实体设备、内容量、音效及四档棋力各自验收。
