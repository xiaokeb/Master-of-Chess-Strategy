# 国标麻将正式 Room Bridge 设备测试交接

日期：2026-10-31。范围：独占新增 AndroidTest；未修改生产文件、公共文件及旧测试。

测试文件：`app/src/androidTest/kotlin/com/masterofchessstrategy/games/competition_mahjong/match/storage/room/CompetitionMahjongFormalRoomBridgeInstrumentedTest.kt`。

## 实际依赖与测试初态

- 复用 `withOwnedLanzhouDatabase` 创建并回收专属 UUID 磁盘数据库，使用真实 `MocsDatabase`、DAO、Room Bridge、Backup Bundle 和 Native JNI；不使用默认应用数据库，不清空应用数据。
- 权限来自明确的 QA `CompetitionMahjongPlayPolicy.fromProgress`。测试设置教程完成及难度映射，仅用于构造权限边界，不代表真实教程通关、解锁链或棋力验收。
- 初始局面及一步局面均由 `NativeCompetitionMahjongSession` 实际生成并序列化。一步局面要求真实执行恰好一个 Bot action 并处于应答阶段；最多尝试 16 个种子，不以伪造字节、跳过测试或旧版本代替。
- MCMT v4 提示回执是绑定实际公开局面及合法动作的 QA 元数据，消耗一次提示额度；不宣称真实顾问推荐、付费 UI 或支付链路已经运行。

## 九项用例

| 用例 | 核验范围 |
| --- | --- |
| `unmigratedClaimCannotOpenOrExportAndKeepsTheOldMcmt` | 未迁移 claim 不可打开或导出，旧 MCMT 保持原字节。 |
| `emptyReplacementNeedsOuterTransactionAndCommittedBridgeHasAnEmptyRepository` | 事务外安装被拒，外层事务提交后可打开显式空仓库并导出规范 MCTX。 |
| `actualInitialAndOneBotActionV4SaveAndReopenWithExactCompleteBytes` | 两个真实 v4 快照入库、保存及磁盘库关闭重开后 MCMT/MCTX 同字节，重领 Owner 更新 epoch。 |
| `reacquireRejectsOldRepositoryAllThreeViewsAndExportWithoutChangingPayload` | 重领后旧 Owner 的对局读写、统计/历史读取和导出均失效，新 Owner 可读取原数据。 |
| `outerFailureRestoresLeaseAndPayloadAndTheAbortedBridgeCannotOpenAfterRollback` | 外层事务失败回滚 Owner 与载荷，旧 Bridge 仍有效，已回滚 Bridge 不可打开。 |
| `bundleReplacementAdmitsAllFourSeatsAndMissingSeatsBecomeExplicitEmptyFacts` | 新记录格式 `['2', viewer, hex]`；单座输入替换四座，缺失座位写入显式空事实并废止旧 Owner。 |
| `invalidActualViewerRejectsReadAndWholePlanBeforeAnyClaimOrPayloadWrite` | 实际表中出现 viewer 9 时，整包读取和安装计划在 claim/载荷写入前拒绝，原四座及异常行均保持。 |
| `structurallyValidV4WithIllegalPaidActionIsRejectedByRealNativeBeforeAnyClaim` | 结构可解码但实际动作非法的 v4 回执，被真实 Native admission 拒绝且未创建 claim。 |
| `explicitNativeMigrationRechecksSourceAndKeepsOriginalMcmtAfterIdempotentRetry` | 显式迁移校验来源 SHA，重复操作幂等；旧 MCMT 保留，来源变更后拒绝旧确认。 |

## 已完成的验证

最终测试文件 SHA-256：`ef007ff20574de62f0deabcd5d64b35342a2ddb792f55a1e7e64c1a5f3e0c3ab`。

私有 Kotlin 编译最终轮次：`.build/competition-formal-room-device-20261031/r2/`，编译器退出码 **0**，51 个输入源文件前后 SHA 一致。日志、结果及输入清单分别保存为 `compile.log`、`result.json`、`sources-before.json`、`sources-after.json`。唯一警告来自未修改的 `CompetitionMahjongMatchPlayViewModelTest.kt:573` 冗余 `!!`。

此项只证明 Kotlin 类型检查。使用已有 schema 22 依赖 JAR，同时把当前 DAO/Bridge/Bundle 源文件编入；没有执行 KSP，也不能证明新增 `listViewerIds()` 的 Room 实现已经生成。

## 交由统筹验收

- 正式候选闭包需要完成 Room/KSP、AndroidTest 编译、Native 打包及对应模拟器九项用例运行；本交接未执行 Gradle、ADB、JUnit、Native 运行或 Git 操作。
- 数据库重开是关闭并重开专属磁盘库，不是杀进程恢复、App 启动接入或视觉测试。
- 测试通过局部变量保留 Bridge，提交后才交给调用者；不主张 API 能阻止调用者在提交前主动发布 Bridge。全局 Host 必须遵守相同生命周期。
- viewer 9 用例验证 Bundle/Plan 自身边界；全局恢复适配器在 `clearAll` 前检查目标键集合，仍需统筹完成集成和验证。
- 不包含整场终局、非空统计/历史、全局恢复互斥、真实教程解锁、棋力、完整支付链及发布验收；不可据此宣告这些功能完成。
