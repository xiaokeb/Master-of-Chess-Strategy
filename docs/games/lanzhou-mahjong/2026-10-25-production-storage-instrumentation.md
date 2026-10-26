# 兰州麻将：生产存储与完整备份仪器交接

本切片只新增3份专属androidTest源及本说明，供Root冻结正式候选后编译、设备执行。没有修改已冻结Admission/Gate/严格Store/Adapter/Wrapper、旧牌桌WIP、公共数据库/工厂/JNI或构建。**当前没有编译或运行这10个新方法，不是设备通过记录。**

## 精确源与覆盖

路径均位于 `app/src/androidTest/kotlin/com/masterofchessstrategy/games/lanzhou_mahjong/data/`：

| 文件/类 | 方法数 | 实际路径与断言 |
| --- | --- | --- |
| `LanzhouMahjongOwnedDatabaseFixture.kt` | 0 | UUID磁盘Room与隔离空Go旧源目录；真实Native历史、旧规则/外层和故意非法历史夹具；调用原签名生产factory |
| `RoomLanzhouMahjongAdmittedSessionStoreInstrumentedTest.kt` | 6 | 生产构造拒错误gate；内LZMJ v1/v2×外LZSR v1/v2关库重开、69字符ID/辅助/决策不变；合法校验和非法DRAW拒绝及坏行保留；真实SQL读回异常和取消回滚；真实JNI新24/旧8计罚、undo/重放与终局落盘再开 |
| `LanzhouMahjongCompleteBackupInstrumentedTest.kt` | 4 | 同一真实生产factory完整导出/恢复与旧Owner退休；非法历史prepare先拒绝不清数据；真实SQLite INSERT触发器中断清写并回滚；旧容器无Lz段清槽且旧owner不能回填 |

编译需要当前实际MocsDatabase/ActiveGame、已冻结5份专属存储/备份源、旧LZSR/辅助/决策数据、真实NativeLanzhouMahjongSession与公共engine API、Room/test/coroutines、原签名 `createProductionGameBackupRepository`、GoLocalStorage、FangqiLocalStorage和既有endgame pack。Root在factory内部增加Lz最外层wrapper及Adapter，测试不另造仅测用注册表来代替生产组合；坏wire生成与旧容器生成才显式使用最小registry。

## 历史与版本夹具

正常历史采用既有真实Native辅助测试使用的seed321。首次创建直接要求实际JNI新局LZMJ字节4为2；弃牌从真实 `legalActions()` 取得并实际apply，再真实restore/serialize精确往返。决策点指向实际本机弃牌前的0动作位置。辅助计数是明确、合法额度内的存储夹具；本轮测试不证明牌桌提示/悔棋按钮曾实际消费这些次数。

旧规则v1只在真实、零动作初态切规则字节并重算FNV，再由真实Native restore/serialize确认规范与合法，之后的历史全部由已固定旧规则的Native执行。这沿用既有 `lanzhou_mahjong_game_test.cpp::test_rule_identity_preserves_legacy_replay` 的夹具方式，不把已有计分历史直接改版本后冒充合法旧局。外LZSR v1夹具要求无辅助/决策字段，保留内层原字节并正确重算CRC；load不改盘上旧外层，显式save才升级外层v2，内v1及ID/计罚不升级。

计罚方法采用既有C++炸胡测试seed5、底分2；先确认没有合法WIN，再通过真实JNI作WIN声明，预期旧规则三家各16/合计48、新规则各48/合计144。保存终局、关库重开并用另一Native会话真实恢复；undo后再声明须重现相同终局字节。该新设备方法未覆盖先杠后炸胡；已有独立Native程序的杠分保留结果继续单独记录，不伪称本方法覆盖。

非法夹具在真实初态追加当前DISCARD阶段不允许的DRAW，正确填写动作数与FNV；先确认真实Native拒绝且原会话不变。LZSR封装和完整备份由真实codec计算CRC/校验和，结构校验必须通过，只有规则完整重放应拒绝。它只用于负例，不列作合法历史。

## 数据与真实故障边界

每方法只建立 `lzmj-data-qa-<UUID>.db`，从不打开默认用户数据库；包含关库重开而非只用内存Room。清理只调用该精确UUID名称的deleteDatabase，空旧源目录只作非递归删除。没有清应用数据、杀App进程或修改外部文件；这些方法不证明真实杀进程恢复、schema迁移、页面/视觉或实体设备。

读回故障/取消方法使用真实Room事务和真实DAO的upsert，仅以DAO委托在后续find注入异常或挂起；不是内存事务模型。完整恢复故障在自建数据库内安装仅针对game3的SQLite BEFORE INSERT触发器，finally移除，随后关库重开再次核对原行。Root须实际运行后才能声称SQLite回滚有效。

静态源码交接与SHA位于 `.build/lanzhou-mahjong/instrumentation-20261025/r1/handoff-freeze.json`。Root随后记录正式候选、编译输出、APK/设备身份、每类方法数、日志及实际结果；不能沿用旧13方法或私有19/27 JVM证明这些方法通过。牌桌、十课资格、完整棋力、音效和本轮之外功能仍按正式计划独立推进。
