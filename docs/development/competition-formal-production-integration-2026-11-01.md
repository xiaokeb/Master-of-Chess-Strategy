# 国标正式赛事生产集成

前置课程节点已发布：`909f8eddccfb42200b9f031058d968b876d39271`，tag `v0.1.0-competition-mahjong-learning.1`，[源码Release](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-competition-mahjong-learning.1)，id430395104；索引提交`d5ac29dcbcb5e3827e517da5b4e2f7e31b4f9e64`已推送。本计划保留全部国标目标，不以课程完成替代正式赛事、可信胜场或全棋种验收。

## 当前事实

- 现有FormalRoom支持固定座位、Owner/epoch/revision、显式迁移与统一赛事/统计/历史仓库；Native只重放当前盘。此前十五盘仅有公开ledger，不能据此授予可信胜场。
- `CompetitionMahjongFormalBackupBundle`以完整容器一次准备四席，缺席生成明确空MCTX；准入在SQLite事务外，安装必须加入唯一外层事务。返回Bridge只有提交后才可发布。
- 生产工厂已接入正式kind。完整候选校验、四席安装及旧源保留均已在实际Room/Native上验收，正式宿主可在此基础上接入。
- 旧`COMPETITION_MATCH`仍支持历史备份。迁移后的旧源由Wire明确标识为保留归档，与正式记录在同一事务安装。

## 实施顺序与所有权

1. 专属完整候选准备器：只从同一候选容器的独立MCLP读取教程完成。缺失/部分均未完成，坏/重复拒绝；不读取恢复前权限或旧四步。策略映射由调用方明确给出，可信胜场来源建立前为零。输出完整四座已准入计划，不在该层操作事务或发布宿主。
2. Root协调公共容器契约：注册、整容器准备、实际键保护、唯一安装事务及提交后发布；准备器必须在即使没有正式记录时也正确处理缺席。先规定并验证legacy-only往返、旧新冲突及迁移后保留旧源的无损备份策略，再修改生产工厂。保持既有棋种锁序及退休屏障，无关源码不进入节点。
3. 固定正式宿主：一次acquire，共享match/statistics/history三个Store；课程资格来自独立持久化证明。补可等待的同步停收、在途任务排空、Native close和固定Owner检查，禁止子Route自行claim或文件Store fallback。
4. 三路由及实际可玩验收：十六盘换盘、席位与配置、全场辅助额度、保存失败、返回/后台、恢复续赛、末盘撤回/重结算、收藏/删除及普通/150%视觉。真实Room/Native与同一生产宿主必须贯通。
5. 可信胜场与成长：新增逐盘私有Native证据及明确的名次→胜场/星级/积分映射；单独设计版本和迁移，不倒推旧统计或冒充现有MCTX已支持。高难梯度及全部内容、音画和性能仍按正式需求验收。

## 备份节点验收

候选权限用例必须覆盖MCLP缺失、部分、完整、坏/重复、旧计数不转换及旧kind冲突；真实恢复覆盖四席缺席、异常实际键清表前拒绝、任一席Native失败不写库、安装失败回滚及旧Owner退休、候选权限不能借用旧内存、提交失败不发布Bridge。仅重跑受影响测试；源码、ABI、APK、设备及视觉证据按最终文件闭包冻结，再显式提交、tag、Release。

`cmfb1`的125项JVM、52项设备测试、双ABI构建、Lint及共享备份视觉回归均已完成。详见[正式备份验收](../games/competition-mahjong/2026-11-01-formal-backup-verification.md)。

## 并行边界与构建来源

专属准备器由`competition_learning_provenance`负责新增源码、测试和说明；`competition_play_retirement`只负责`match/play`及对应测试，先回传具体方案。Root独占公共容器契约、生产工厂、导航、集成构建/设备及发布。准备器不会直接接管旧容器，退休模块不会自行取得FormalRoom归属；真实Owner由正式宿主明确提供，不能默认授权或重试续租。

私有编译须明确引用`.build/cmlp5/source`的冻结App/API/Native产物，未重跑的JVM夹具引用同源`cmlp2/source`并核对输入。根目录`app/build`属于混合工作区，不得标成课程标签的产物；失败的路径定位轮次保留，不能以重新指定classpath改写旧收据。正式节点仍需干净文件闭包的实际Gradle、必要设备/视觉与发布验收。

准备器私验`r2`编译退出0、14项JVM通过，源码及二进制依赖前后散列一致；证据位于`.build/competition-formal-restore-preparer-20261101/r2/`。当前测试成功路径不创建活动Native会话，锁定活动夹具在JNI前拒绝；因此只证明候选资格与完整四席结构准备，不证明实际Native恢复、Room安装、生产备份或正式赛事可玩。`r1`路径错误编译失败保留。后续发布仍须纳入上述真实验收。

## 完整候选与旧源保留契约

公共`GameBackupContainerPreparation`对注册Owner调用一次，接收已经全部完成格式验证的不可变候选和本次准备上下文；即使该kind缺席也调用，以便生成明确空事实。返回单个拥有本槽的批量安装事实，内部可含四席。非批量adapter继续原逐条准入；所有结果仍受描述符稳定、槽归属、共享棋谱ID及跨Owner关系检查。接口私验6新＋23原测试通过，未注册生产Formal；干净`cmbp1`闭包预验覆盖本接口与准备器，结果单独记录。

`cmbp1`预验现已完成：基线`d5ac29d`加7个明确覆盖文件，1960份源码封存；实际KSP/生产Kotlin/测试Kotlin编译及7组75项JVM通过，前后输入散列一致。含批量6、注册表23、容器编解码15、混合备份1、正式库存7、学习备份9、严格准备器14。日志和XML散列见`.build/cmbp1/preflight-receipt.json`；该快照不含仍在开发的Wire或赛事页面，未执行本节点APK/Lint/设备，也未满足发布门禁。

选择沿用单座kind/slot，生产wire新增显式版本`[2,1,viewer,mctxHex,retainedLegacySourceSha256或-]`。旧三字段只作为无旧源伴随的历史正式输入；规范化必须保留新版本及归档角色直至整容器核验。SHA256覆盖同容器旧adapter规范化后的完整`kind|fields…\n` UTF-8行，各Formal席必须关联同一旧源；缺源、有源却标空、不一致、重复或未经角色声明的旧新混合均拒绝。该关联是归档完整性与角色标识，不是签名或可信胜场证明；容量预算须计入新增字段。

核验关联后，仅向严格准备器传剥离版本/角色的Formal及候选MCLP等伴随事实，旧记录仍交原adapter在同一事务保留。纯旧容器走单独的LegacyOnly分支，保留后续显式迁移资格。无Formal也无旧源时补四座明确空事实，防止旧磁盘源静默复活。

Wire已按该合同冻结，私验16项通过，详见[说明](../games/competition-mahjong/2026-11-01-formal-backup-wire.md)。赛事退休切片7文件亦已冻结，交接`.build/competition-play-retirement-20261101/handoff.json`逐项SHA已核；私有JVM原45＋新增6共51项、定向Compose编译通过，详见[页面退休](../games/competition-mahjong/2026-11-01-play-retirement.md)。这些不是本节点真实设备验收，也未进入已发布课程tag。

下一步接正式宿主。首次进入先判读目标座位和旧源，待玩家明确确认后才建立正式迁移归属；新座位无旧源时直接建立明确空MCTX。宿主传固定Owner与同库写者注册表，共享赛事、统计、历史三个Store；旧页面先完成退休，正式宿主在事务提交后发布。两份旧共享QA调用在宿主候选内适配。
