# AI 完成状态与同局重试界面

范围仅为 `GoAiMatchScreen.kt` 和专属 Compose 测试；不修改 Route、Session、VM、导航、存储或规则。保留前轮未验的界面状态基础，补齐固定但尚未入谱的替换门禁。

- 仅在瞬时 `immediateHumanCompletion && !config.automatic` 策略下显示即时完成状态：`completionReserved` 表示结果已固定，`game.saving` 表示保存进行中，`completionPending` 显示待重试，`completionStored` 显示完成棋谱已保存。不能仅由 `FINISHED` 推定即时保存或正式成长；策略字段不写入存档。
- 即时真人结果固定但未保存时关闭悔棋、重开和指定开局；已打开的重开／开局确认对话也重新检查当前状态，不能用迟到确认绕过。`completionPending` 对所有模式仍阻止替换。旧训练reserved状态保留原有重开／指定开局保存重试行为；自动续局继续使用其专属重试按钮，不显示无法执行的真人重试。
- 尾部新增可选 `onRetryCompletion` 回调，不改变既有调用参数；重试按钮只调用完成回调，不发重开、导入或悔棋操作。页面不承诺自动／训练／未知来源已计入成长。
- 控制列继续使用纵向滚动，完成信息与重试按钮位于列内。新增10个展示测试覆盖固定未入谱、未固定待重试、保存中、已保存、旧训练兼容、已开对话状态更新、已关闭、无完成存储、旧训练reserved及自动series重试；其中待重试测试以局部150%字号执行，不改系统字号。

本轮仅静态核对，未调用 Gradle、NDK、ADB 或 Git。测试尚未编译／运行，尚无本候选设备截图；不计作设备或视觉验收。Root 下一接 `GoAiMatchRoute` 的真实 `retryCompletion` 与 writer 退休流程，再统一进行受影响构建、模拟器及普通／150%视觉检查。真实同ID事务与成长行为仍以独立 Room／Native 测试为证据，本展示夹具不冒充完整可玩或棋力证明。

集成判断：现有 `archiveAutoSeriesEnd()` 成功后只发布reserved，默认训练晚失败也可能仅有reserved；不能从这些旧状态推断stored/pending。Root 采用显式瞬时UI策略字段后，本切片按策略隔离真人完成门禁，保留旧流程，不扩大重试方法的适用范围。这不是修复自动完成事实发布；10项展示测试也不证明旧流程或真实自动保存已经设备验收。Session字段与接线由Root单独实现，本切片不修改持久格式。
