# 围棋真实 Native owner 生命周期

日期：2026-10-18。状态：专属实现及定向私有 JVM 检查完成；正式 Android 集成与以下 3 项设备测试待执行。本文不作为 Go raw 数据节点的验收证据。

## 范围与依赖

沿用已有 `GoAiRuntimeOwnerAdapter`、`GoAiAssetModelLoader`、`NativeGoSearchRuntime`，不增加第二个 owner。只调整专属适配器及测试；不改 engine-api、JNI、公共清退屏障、导航、Room 生产工厂或其他棋种。

模型仍使用已锁定的本地 `kata1-b15c192-s1672170752-d466197061.txt.gz`，压缩文件为 48,038,914 字节，SHA-256 为 `09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967`。没有引入新模型、依赖或第三方资源；既有许可边界不变，不新增商用授权结论。

## 请求与释放顺序

- 完整位置快照按现有公开 API 传递，不截断 Native 历史。`sessionId/revision` 继续由 Coordinator 检查，过期结果不交付。
- 原样传递访问次数和时间预算，并拒绝超出访问预算的结果；时间预算不是推理中途的硬实时保证。
- owner 仅返回建议。走子仍由 Session 交规则引擎应用，再执行耐久提交；网络估分不代替规则裁定。
- lease 从 `prepare` 前开始，直到取消回调脱离及请求 `close` 完整结束才移除。关闭先封禁新准入、取消已准入 token，等待全部 lease，再在 worker 释放 runtime。
- 并发及后续 `closeAndJoin` 共享稳定完成结果。请求或模型释放失败会保留并再次抛出，不能被一次幂等调用吞掉；正常搜索错误与资源释放错误分开记录。

本轮复核移除了 `prepare` 后额外分配的 `OwnedRequest` 包装：直接取得请求并用 `try/finally` 释放，避免该额外分配失败时留下已取得的资源。主搜索异常保持为主异常，释放异常作为 suppressed 保留。这是对该具体缺口的修订，不表示已验证所有内存耗尽路径。

与已实现的 Session／ViewModel 清退衔接后，顺序为：封禁操作和自动续局 → 等待加载及搜索清退 → 规则释放 → 模型 owner 释放 → 稳定 `retirementCompletion` 成功。任何释放失败都不得让备份屏障成功通过。

## 本轮实际证据

使用缓存 Kotlin 2.3.21、JDK 17 的定向私有检查；未运行 Gradle、NDK、AVD、安装或 Git 操作。

| 收据目录（仓库内） | 实际结果 | 身份及限制 |
| --- | --- | --- |
| `.build/go-ai-owner-20261018/private-r1/` | 43 输入编译成功，45 JVM 通过：owner 12、loader 7、VM 12、Coordinator 14 | 包装修订前的旧快照；不是最终源码的 45 项复跑 |
| `.build/go-ai-owner-20261018/private-r2/` | 43 输入编译成功，仅 owner 12 JVM 通过，检查前后输入未变 | 包装修订后的最终定向复核；3 项设备方法仍只编译，未执行 |

两轮是分段证据，不合称最终快照 45 项通过，也不相加为 57 个不同用例。私有编译不能代替正式 Android 构建及设备验证。两轮均只有公共屏障已有的 unnecessary safe-call 警告，未修改该公共文件。

最终 r2 源清单 SHA-256：`e5f6028e48cd98c9e0f22c8351cd5747dd5debf923bf9ab9fe118c9e9b7b1f9f`；JUnit 日志 SHA-256：`c65b4c140b5877528a7fdc7e12aeb92be87fd9bc515a2d52c079ddf9f399667e`。

旧 r1 源清单 SHA-256：`8cdeac1e9caff44aa5bd2fa87a7ff3933f5c2da2ce87bef522554053bd8acbe5`；JUnit 日志 SHA-256：`d9e403055d0cbf2b85548107a8640ba5b38da06092239ae132834b271a41d012`。

owner 12 项覆盖类型化结果、预取消、prepare 期间取消、运行异常后脱离回调、晚到取消、主异常与释放异常、预算／尺寸／关闭后拒绝、并发关闭、prepare 期间关闭、运行至请求 finally 期间关闭、稳定失败结果及完整最大快照／位置身份。JVM 使用显式假 runtime/request，不宣称真实 Native 搜索。

## 真实 Native 设备专项：已编译，尚未运行

专属类：`GoAiNativeOwnerRetirementInstrumentedTest`。以下 3 个方法不属于已发布 raw 节点的设备证据，待统筹在独立正式 AI 生命周期集成候选中执行：

1. `retirementDuringRealModelDeliveryWaitsForLateRuntimeReleaseBeforeCompletion`：构造真实模型 runtime 后延迟交付，期间退役，验证晚到资源先释放、再完成，且不建立或保存对局。
2. `observedNativeSearchRetirementJoinsRequestThenRulesThenModelBeforeStableCompletion`：19 路 AI 对弈，观察真实 JNI `GoSearchBindings.run` 栈帧后退役；实际请求关闭后延迟测试包装的最终释放，验证完成仍 pending，随后验证 request → rules → runtime → completion 顺序与退役后无新增保存。
3. `errorAfterRealRulesReleaseStillClosesNativeModelAndBlocksTheBackupBoundary`：真实规则释放成功后注入 IOException，仍释放真实模型；验证稳定完成失败、registry 保留失败 owner、备份边界 sentinel 不可进入。

三项均使用实际锁定资产、`NativeGoSearchRuntime` 和 `NativeGoEngine`。模型写入本次测试独占的 cache 子目录，不使用生产模型缓存；只清理已验证的该目录文件。checkpoint repository 明确为内存替身，不打开用户数据库。注入仅限交付／请求释放时序及真实规则关闭后的异常；不是伪 Native 搜索、Room 恢复或 SAF 备份测试。

真实 JNI 栈帧观察不到或搜索过早结束时，用例必须失败，不跳过或改用假搜索。观察栈帧仅证明进入真实 JNI run，不证明已处理特定网络行数或达到棋力指标。

## 待统筹完成

执行正式 Android 构建及这 3 项设备方法，留存候选 APK、源码和日志身份；再结合公共屏障与生产接线评估发布。本文不宣告生产 Room 切换、完整 AI 功能验收或已发布生命周期节点，不借用 raw 的 27 项历史设备测试证明本轮功能。

本轮专属源码 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `GoAiRuntimeOwnerAdapter.kt` | `927898f43139b8ec8b2fbf396d9e5c27f2b35c42e1866fef4ae6701ea2deb4e5` |
| `GoAiRuntimeOwnerAdapterTest.kt` | `2c643253fffcc537cafe78f90db1bdd88f3dfa72852fd4392e76605ca0a5676b` |
| `GoAiNativeOwnerRetirementInstrumentedTest.kt` | `f2cfde7dde5a15f3d08109770cd2211bf26a3a9667423940393bfe142182d6bf` |
