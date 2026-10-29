# 兰州麻将正式宿主安全切片（2026-10-26）

节点源码冻结：是。基于已发布 `49ec2db2283fdedef9e42fd8bd45b7b176b917e6` / `v0.1.0-lanzhou-mahjong-native-storage.1` 的严格存储和原生异常契约继续开发。本切片完成专属宿主与私有 Kotlin/JVM 验证；未启用菜单、教程资格或正式导航，不代表 App 牌桌或整棋种已验收。

## 最终实现

- `LanzhouMahjongGameHost` 在 worker 上以同一 Mutex 串行创建、读取、落子、bot、保存确认和关闭 Native。UI 只收到受控公开视图；旧 `TableController` 的同步关闭、旧 `SessionStore` 均不进入新的 ViewModel 路径。`TableState` 已独立，正式候选无须携带旧 Controller。
- `HostStorage` 每个页面写者只捕获一次 Owner/epoch，并把该 Owner 交给严格 Store 工厂。相同 gate 的各 Host 共用资源槽，新 Host 必须等待前 Host 的 `retirementCompletion` 成功；前 Host 关闭失败的回执持续阻止再开，另建 Storage/Host 也不能绕过。
- 资源槽等待不持有 gate/Room 锁。宿主操作边界内调用严格 Store，由已发布 Store 在 gate 内核对固定 Owner，再执行准入、Room 事务及精确读回。宿主不在 Store 外再次套 gate 锁，避免不可重入锁死。恢复成功或失败导致 epoch 改变后，旧 Owner 从不刷新或补写；恢复前已取得 gate 的写入可完成，再由恢复事务覆盖。
- `beginRetirement()` 同步封住新操作，由独立 cleanup scope 等待当前操作并关闭 Native。`closeAndJoin`/退休回执可等待真实释放。页面退休后，已接受动作仅在原 epoch 仍有效时继续按原 Owner 保存；严格 Store 是最终写入门禁。ViewModel 清除和取消 route 等待者不孤立后台释放。
- 保存请求固定完整 `LanzhouMahjongStoredSession`：native、规则版本、席位/难度、选择种子、辅助次数、决策点及时间一次捕获。失败保留同一请求对象，重试不再调用时钟或 bot；严格 Store 成功返回后才确认、展示提示和允许下一次操作。恢复后无变化不重写时间。
- 损坏或不兼容恢复保留错误及原行；失败界面仅允许安全返回，没有自动新局覆盖或错误后重开入口。显式重开先确认当前保存并释放旧句柄，再创建新局；新局写失败时保留完整待确认请求，旧持久行仍在，当前新局须精确重试。
- bot 调用前后验证 LZMJ 结构、相同规则/初始牌墙及完整既有动作前缀。按实际新增 action 数推进非负选择种子，包括 `Long.MAX_VALUE` 回绕。异常可留下合法已提交前缀；完整请求经过严格存储确认后才允许继续。无法测量或前缀/回报矛盾时封闭操作并保留此前持久行，不猜测种子或写入未确认状态。
- 页面错误固定简洁中文。原始 Native/SQL/取消原因保留在 Host/VM 的内部 `failures`，不放入 UI。打开失败又关闭失败时，用 `LocalDataWriterFailure` 聚合原始对象，保留既有 suppressed 链，不修改原异常。取消继续传播；失败的前宿主等待不占用/替换旧的失败资源槽。

强制退休会停止写者并保留最后已确认的持久行；若此前保存已失败，普通退出继续保留页面及精确重试请求。无法测量的内存状态不冒充已保存进度。

## 私有验证

最终执行命令：

```powershell
& .build/lanzhou-mahjong/check-host-safety-20261026.ps1 -CompileUi -OutputDirectory .build/lanzhou-mahjong/host-safety-20261026-r5
```

实际 compiler 为缓存 Kotlin/Compose 2.3.21，JDK 17.0.12，`-jvm-target 17`、`-XX:ActiveProcessorCount=1 -Xmx512m`；没有 Gradle、ADB、C++ 或环境新增依赖。真实输出为 `.build/lanzhou-mahjong/host-safety-20261026-r5/controller-tests.jar`。只读编译统筹新增 `data/LanzhouMahjongProductionHostStorage.kt`，通过 `-Xfriend-paths` 使用 `.build/lzsp4/source/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` 的真实 App classes，没有数据库 stub。当前及该发布快照的 `MocsDatabase.kt` SHA 均为 `fea5076262d413aaa50c7049b3eb1c8114222bf6080c2d6596b87400ad1c8caf`，schema 19。

| JVM 类 | 实际通过数 |
| --- | ---: |
| LanzhouMahjongGameHostTest | 30 |
| LanzhouMahjongGameViewModelTest | 5 |
| LanzhouMahjongStorageEpochGateTest | 6 |
| LanzhouMahjongSessionRecordTest | 11 |
| LanzhouMahjongAssistanceTest | 8 |
| LanzhouMahjongDecisionHistoryTest | 6 |
| LanzhouMahjongTableControllerTest（历史） | 4 |
| LanzhouMahjongAssistedControllerTest（历史） | 8 |
| RoomLanzhouMahjongSessionStoreTest（旧 DAO 替身） | 4 |
| LanzhouMahjongLearningControllerTest（历史） | 4 |
| RoomLanzhouMahjongLearningStoreTest（历史） | 2 |
| 合计 | 88 |

Host/VM 的 35 项覆盖精确确认与失败重试、受控提示/决策点、bot 异常合法前缀和继续、不可测量封闭、旧 v1 保留/新局 v2、STEP_LIMIT、种子回绕、损坏恢复不建局、失败恢复退休旧 Owner、保存中退出与取消等待、同 gate 多 Host 等待及关闭失败封闭、Native 调用真实专属线程、原始异常和 suppressed 链、取消加载/保存/bot/前宿主失败、载入期间 epoch 改变仍保留退休标记。

测试中的 Native/Store 替身只证明宿主编排和固定 Owner 门禁行为，不证明真实牌局合法性、JNI 或 Room SQL。本轮没有实际运行真实 Room/Native，也没有 Gradle、APK、Lint、设备或视觉结果。严格 Store 的已发布验证仍以 10 月 25 日报告为准，不能充当本宿主的新运行证据。

3 份既有 AndroidTest 仅私有 Compose/Kotlin 编译：`TableScreenTest` 9 项、`LearningScreenTest` 2 项、`AssistanceNativeTest` 2 项，均未运行。全目录搜索没有旧 VM `store`/`resume` 构造调用。`AssistanceNativeTest` 仍在两处使用旧 Controller，是历史 Native 辅助测试；统筹若要纳入无旧 Controller 的正式 Host 候选，应另行适配为 Host 或暂留历史验证，不能改名宣称验证了新宿主。其余两份没有旧 VM 构造问题，学习页仍属未交付专属内容。

首轮至最终的日志均保留：r1 75、r2 79、r3 81、r4 87、r5 88 项。每轮新增测试对应实际安全边界修订；最终验收只认 r5 输入。

## 输入身份与交接

[冻结清单 TSV](freezes/host-safety-20261026.tsv)记录 44 份直接 Kotlin 编译输入的路径及 SHA；[冻结元数据 JSON](freezes/host-safety-20261026.json)单列本代理修改的 8 份源码、统筹只读工厂和 3 份仅编译 AndroidTest。全部直接输入（包含依赖及真实友元 classes）共 1759 文件，私有 `inputs-before.json` 与 `inputs-after.json` 内容及 SHA 相同：`a03f35b47fc480d63c38fdd34628211e76896f1daf1eb2ed786cf9d8cedcd9b1`。输出 jar SHA 为 `1eb6d6c9c650a3a264ba9c5a4971e9c04b9e80f6080cde1adaff79ee9c0e5536`；`compile.log`、`jvm.log`、`verification-receipt.json` 与精确命令脚本均保存在上述私有目录/其父目录。

本代理源码范围：

- `app/src/main/kotlin/com/masterofchessstrategy/games/lanzhou_mahjong/` 下 `LanzhouMahjongGameHost.kt`、`LanzhouMahjongGameViewModel.kt`、`LanzhouMahjongGameRoute.kt`、`LanzhouMahjongTableState.kt`、`LanzhouMahjongTableScreen.kt`。
- 同目录 `LanzhouMahjongTableController.kt` 仅抽离状态类并注明历史用途，不是正式 Host 依赖。未授权把旧宽松 Store/同步生命周期重新接入生产。
- `app/src/test/kotlin/com/masterofchessstrategy/games/lanzhou_mahjong/` 下 `LanzhouMahjongGameHostTest.kt`、`LanzhouMahjongGameViewModelTest.kt`。
- 本文及 `freezes/host-safety-20261026.{tsv,json}`。

共享接入由统筹继续：在精确同 DB 的工厂构造固定 Owner/严格 Store，并把 ViewModel 注册到该 DB 的 writer registry；恢复屏障先 seal/await 写者，再按已发布 Lz→方棋→围棋→Room 次序进入备份恢复。统筹新增生产工厂已只读编译，尚未启用。随后进行实际 Room/JNI 故障与跨页资源测试、正式 Gradle/专属仪器、退出/错误状态页面视觉，再评估教程资格和导航。不得据本轮替身测试宣布上述集成完成。

本代理曾尝试在专属文件内新增 Room/DAO 便捷工厂，自动审批以“新增对共享 RoomDatabase/ActiveGameDao 的正式接入，超出任务禁止共享 Room/API 边界”为由拒绝；该动作未执行且未重试。后续仅编译统筹在其生产集成窗口自行新增的工厂，未编辑该文件。未修改其他棋种、共享 schema/API/JNI/导航/构建、chat-status，未执行 Git 提交或推送。
