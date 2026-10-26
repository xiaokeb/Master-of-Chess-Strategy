# 兰州麻将：完整活动局备份与恢复门禁

本切片新增专属 `data/LanzhouMahjongBackupAdapter.kt`、`LanzhouMahjongCoordinatedBackupRepository.kt` 及同名JVM测试；不改已冻结Admission/Gate/严格Store、旧WIP、公共工厂/JNI/导航。它是 [生产接入计划](2026-10-25-v2-production-integration-plan.md) 的备份前置，尚未注册为正式App完整备份。

## 数据格式与准入

descriptor固定 `LANZHOU_MAHJONG_ACTIVE`，最低容器5，独占 `ACTIVE_GAME/LANZHOU_MAHJONG` 槽，最多1条、canonicalOrder40、单记录上限65536字节。wire字段恰为：

| 下标 | 字段 | 约束 |
| --- | --- | --- |
| 0 | game | 固定 `3` |
| 1 | 专属wire格式 | 固定 `1`；不是LZSR或LZMJ规则版本 |
| 2 | fixedTimestamp | 规范非负十进制Long，不接受前导零、正号、负号或溢出 |
| 3 | 完整LZSR | 小写偶数字符十六进制，解码前按15186字节上限检查，再严格LZSR解析 |

新输出固定LZSR外层v2，覆盖最多1000原生动作、1000决策点及独立辅助消耗。不是仅保存公开手牌、最终牌面或胜负摘要。LZMJ内层v1每家8倍与v2每家24倍按原字节保留；新wire的格式1不授权改判旧局。

`validate` 只校验容器、归属、字段、规范数字/hex和LZSR结构，不创建Native。`normalize` 允许合法旧LZSR外v1规范升级外v2，仍不调用Native，不改变内层规则/历史/辅助。`read` 实际查询game3，缺行返回空列表，坏行报错保留；对每条已有行调用一次 `Admission.admitRow`，再编码。

`prepareRestore` 先结构decode，再调用一次 `Admission.createRow` 完成真实Native完整历史restore/serialize准入，构造所有元数据。69字符旧ID由原record的初始牌墙/规则、viewer与难度按现有算法生成，wire不截断或改为UUID；辅助权威仍在LZSR，行的旧辅助列保持0。

Prepared对象在构造时独占ActiveGameEntity的engineState，每次write又向DAO交一份新副本。write只upsert，不加gate、不清表、不开嵌套事务、不重复Native准入；公共仓库必须在所有prepare通过后才于单一事务清写，失败回滚仍由该公共事务负责。

## 联合恢复所有权

Root已确定新增锁序：**兰州麻将 → 方棋 → 围棋 → Room**，保留既有后三者相对顺序。专属wrapper只包Lz最外层；生产工厂需将它包在现有方棋/围棋组合之外。

export在Lz `exclusive(replacing=false)` 中委托完整导出，不推进epoch。restore在等待任何gate前复制输入，然后以 `exclusive(replacing=true)` 调用内层完整恢复；已进入的旧Lz写入完成后才替换。错误原样抛出，不暗中重试、不刷新旧Owner。

取消尚未取得Lz锁时三个epoch都不变。若已取得Lz、等待方棋时取消，只有Lz已退休；已取得方棋、等待围棋时取消，Lz和方棋已退休而围棋仍未退。不能仅因最外层开始就宣称所有写者均已退休。到达内层后发生失败，已到达各gate仍保留新epoch，磁盘是否回滚须由真实Room事务证明。

## 定向验证与边界

2026-10-25在独立 `.build/lanzhou-mahjong/backup-20261025/r1/` 使用既有JDK17/Kotlin2.3.21、协程1.11.0、Room2.8.5及缓存依赖，单CPU/512MiB，首次编译**22份真实源码**成功，新增**19/19 JVM**首次通过（0.183秒、退出0），当前源前后摘要一致。

- Adapter 11方法：descriptor/真实Registry单槽单记录、结构校验/normalize不调用Native、两种内层规则/辅助/决策/旧ID往返、旧外层规范化、缺行与坏元数据保留、read/prepare各一次准入、Native拒绝不写、错误归属/字段/规范数字/hex/大小、15186字节最大完整LZSR、Prepared构造及每次write独占数组。
- Wrapper 8方法：实际Lz/Fangqi/Go gate和既有Fangqi wrapper组合，覆盖已入Lz写入等待、导出阻止三个写者且不退休、成功返回原summary并退休旧owner、失败原样传递一次、各等待层取消以及等待前独占输入。

DAO、Native canonicalizer和最内层备份delegate均是显式替身，事务/SQLite/JNI均未运行。编译使用 `fqlp2` 的实际MocsDatabase、备份接口/摘要及Registry已编译类作为friend依赖，直接类摘要有收据，没有定义假的公共类型；这不是重编KSP/整个App或完整发布依赖闭包。既有Admission/Store的27项不在本轮重复执行，也不与旧52项相加冒充正式验收。

本地目录保存22份冻结源码、源前后摘要、实际argv/日志、直接friend类身份、Java/缓存jar环境摘要和handoff清单。没有启动Gradle、NDK、ADB、搜索或Git，没有修改公共源码/注册或套用旧App设备结果。

下一由Root串行注册adapter、生产factory与固定锁序，冻结完整候选后补真实Room/JNI的读写、旧8倍/新24倍恢复、坏历史先验拒绝、清写中途失败回滚、旧容器缺本段清除和旧Owner拒写；共享设置/SAF受影响回归也需运行。十课学习存储/资格、VM可等待清退、正式牌桌导航、音效和四档强度仍另行实现与验收。
