# 兰州麻将 v2：正式生产接入计划

2026-10-25只读核查。依据正式PRD、页面交互、任务分级、`coordination-decisions-2026-09-30.md` 及 [炸胡补充规格](../../requirements/lanzhou-mahjong-false-win-v2.md)。本文件只规划接入，不表示当前App已经具备完整兰州麻将入口、存储或备份；不修改既有冻结WIP，不新增用户提问。

## 已确认规则与现有证据

新LZMJ原生规则v2炸胡向其余三家各付底分×24、总付×72，不再叠加自摸/点炮，既有杠分保留；旧规则v1维持各8倍。`lanzhou_mahjong_game.hpp` 默认 `project_false_win_24`；game炸胡分支按实际 `rule_` 选择旧/新结算。`lanzhou_project_false_win_payment` 先校验底分×72范围，再生成零和付款；`lanzhou_add_payment` 先检查全部累计值溢出再累加，不清零旧杠分。restore在动作重放前固定原记录规则，未知版本拒绝，重存和悔棋保留原版本。此为用户确认项目桌规，不宣称地方统一计罚。

| 层/证据 | 本次实际核对 | 不足以证明 |
| --- | --- | --- |
| 原生直接输入 | `false-win-24-20261022/sources-before.json` 的12份当前源码摘要全部相同 | 正式JNI、App装配及完整依赖闭包 |
| NDK产物 | settlement/game/bot三个既有程序均有编译收据 | 单凭编译不能算设备运行 |
| 独立模拟器 | `device-887d071765ad413da5275009b0394e2c/receipt.json` 三个相同产物摘要、退出0；未清数据 | 正式App/JNI、Room、备份与页面视觉 |
| JVM | 最终r2日志含 `OK (52 tests)`，日志SHA `7d516b749b967ed2bed1d36381d8971ec93bd2fe427d3be64d359a5d67d5325b`；测试jar SHA `f7643a070ff2fe999678cce84839fe9d5dd9cafd0121c13fec6fde7ae25e1281` 与交接一致 | DAO替身不证明真实SQL/事务，私有编译不等于Gradle/KSP/正式APK |
| 仪器源 | 既有13方法仅有隔离编译记录 | 不能计入设备通过数 |

证据根仍为 `.build/lanzhou-mahjong/`；本次未重跑测试。旧51/27/8及10月早期App/JNI结果只作各自源码当时证据，不直接套在新正式候选上。

## 正式装配前的实际缺口

1. `RoomLanzhouMahjongSessionStore` 当前只注入ActiveGameDao，save为一次upsert；没有同数据库epoch门禁、Native历史准入、读回确认或显式Room事务。load只检查部分元数据，其余通用字段和负时间未统一拒绝。不能照搬到生产。
2. 该Store的活动ID为 `lzmj-` 加64位小写SHA十六进制，共**69字符**；摘要来自native前122字节、viewer与难度，动作不改变ID。不能套方棋64字符上限、改写旧ID，或用新UUID静默替换已有局。
3. `LanzhouMahjongSessionRecordCodec` 外层LZSR读v1/v2、新写v2，最大15186字节；内层LZMJ规则读v1/v2、130至3130字节、最多1000动作。外层版本、108张variant和炸胡规则身份不可互换。辅助次数、决策点及选择种子以LZSR为现有权威；ActiveGameEntity两辅助列当前写入默认0，备份/新Store必须明确这一兼容口径，不能用行默认0覆盖真实消耗或补满额度。
4. 当前生产备份工厂未注册兰州Adapter。库存守卫会拒绝未注册的game3活动行，不能通过跳过该行来使导出成功。外层通用ACTIVE解码仅属于象棋；兰州必须使用自己的注册kind、`ACTIVE_GAME/LANZHOU_MAHJONG`槽及Native prepare，不绕过其字段限制。
5. 十课 `RoomLanzhouMahjongLearningStore` 直接写公共TutorialProgress行；公共Repository和备份codec固定4步完成，因此兰州第5至10课/完成位会被判不兼容。现有十课实现不能在未改学习存储/备份所有权前接正式入口。不得把十课截成4课、冒用象棋键或凭完成标志发奖励。
6. `LanzhouMahjongGameViewModel` 未接可等待writer退休/epoch；换局及onCleared直接同步close，可能在Main等待Native锁。route系统返回只检查busy/save，牌桌onBack直接回调；恢复失败页“开启新局”调用restart可覆盖坏行。需先补显式重试/返回、后台排空关闭、保存成功后替换和错误保留。
7. 当前正式App没有兰州麻将可玩导航/教程资格装配。PRD规定对应教程完成后解锁简单，不能为尽快展示就先开放四档；既有bot档名不代表梯度已验收。

## Root串行公共接口与专属边界

| 工作 | 专属文件可独立推进 | Root协调的共享变更 |
| --- | --- | --- |
| 活动行准入 | 新 `games/lanzhou_mahjong/data/LanzhouMahjongSessionAdmission.kt`、`LanzhouMahjongSessionMetadata.kt` 及测试；现有LZSR codec只读引用 | 不需先改engine-api；真实Native门面和JNI仍由Root核对当前候选 |
| epoch/Store | 新专属 `LanzhouMahjongStorageEpochGate`、`LanzhouMahjongLocalStorage` 与严格Room适配/测试 | MocsDatabase注入/所有者工厂；不得并发修改Fq/Go gate。原Store改动需显式解冻，不由其他对话代改 |
| 完整备份 | 新专属 `LanzhouMahjongBackupAdapter`、联合门禁包装与测试 | `ProductionGameBackupFactory.kt`、公共注册/库存测试及恢复clear事务；Root固定包含新兰州gate的全局锁序，保持既有Fq→Go相对顺序 |
| 学习 | 新专属、按十课版本/顺序校验的学习适配与测试 | Root选择独立存储所有权及schema迁移、库存/clear/备份kind；须兼容已有合法十课行，不能清除或截断。schema版本以实际集成顺序为准，不与方棋迁移争号 |
| VM/route | 显式解冻兰州VM/controller/route及专属测试 | 可等待writer注册、NavBackStackEntry保留VM、epoch key、导航/资源及资格装配 |
| 构建/发布 | 专属准确清单、范围和测试记录 | CMake/JNI注册、Gradle/KSP/Lint/APK、模拟器和Git仅Root串行；按真实基线冻结干净候选 |

准入应复制输入原字节、严格LZSR结构/长度/元数据、真实Native完整restore再serialize一致；已读取旧LZSR v1可按明确新写v2规范化，但不得改内层LZMJ原字节或重置辅助。决策点需继续接受既有合法v2语义，不能把结构校验/FNV/CRC当合法历史证明。所有已有棋局均按原native规则恢复；恢复失败保留原行和错误。

备份adapter `validate` 只做有界结构校验；`read/prepareRestore` 完成真实Native准入。Prepared对象拥有不可变已验数据，只在公共单事务写入，不再加gate、清表或开嵌套事务。kind、canonicalOrder及单记录wire上限在实际字段定案后注册并验证不冲突，不套方棋kind或未核尺寸常量。恢复完成/失败后旧owner退休语义与既有公共数据替换一致；只释放本模块确已排空的Native所有权。

## 按依赖推进与验收

1. 冻结精确当前专属源、公共API需求和已验收基线；先实现SessionAdmission/元数据与gate/严格Store。JVM覆盖合法/损坏/未来版本、69字符ID、LZSR双外层×LZMJ双内层、非默认通用字段、辅助消耗和失败重试，替身标明边界。
2. Root接完整生产备份工厂、固定锁序和库存。真实Room/JNI测试读写、关闭重开、完整备份往返、校验和合法但非法动作历史拒绝、事务清写中途失败回滚、旧容器缺本段清理以及旧owner拒写。共享Go/Fq设置/SAF只跑受影响回归，避免无关全量。
3. 独立解决十课学习持久化与备份、旧合法行迁移及教程资格；随后解冻VM/route，补恢复失败保留、配置重建同owner、退出保存/排空与新局原子替换，不在Main执行阻塞close。
4. 在精确候选中正式构建当前JNI/native/bot、JVM、Lint和Debug APK；运行现有13仪器方法及新增真实存储/生命周期方法，按候选身份继承不变部分。普通/150%字体逐图核查手牌/暗牌隔离、响应操作、辅助额度、错误重试与返回；专属数据库内验证真实杀进程恢复，不清默认用户数据。
5. 单棋种可验证节点满足后，显式暂存、全树干净导出核对、提交推送、附注tag和源码prerelease。发布列出未验实体、四档强度、题库/残局、自动演局完整入口、成长/统计、音效与性能；不能把24倍原生程序通过写成完整棋种完成。

普通桌规已明确，没有需要再次询问用户的真实产品冲突。上述结构与所有者问题可按既有项目边界修复；若后续发现旧数据无法无损迁移，应先保存并交Root核对具体字节/影响，再决定是否确需用户取舍。
