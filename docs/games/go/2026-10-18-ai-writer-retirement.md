# 围棋 AI 可等待清退（2026-10-18）

本切片连接统筹新增的 `LocalDataWriterRetirement` 契约，修复“ViewModel scope 已结束，但独立 Native／存储清理尚未完成”的所有权缺口。仅修改 AI Session、ViewModel 及其专属测试；公共 registry／恢复屏障由统筹另行验证。不切换生产 Room、导航或完成 sink，不属于当前独立 raw 数据候选。

## 行为与顺序

`GoAiMatchSession.beginRetirement()` 必须在原 owner dispatcher 调用，同步且幂等：封禁操作、撤销自动继续搜索许可、取消搜索与延迟推进、标记 closed、取消 session lifetime。它不等待 worker、不关闭正在使用的规则引擎，也不获取数据库／备份 gate。

`closeAndJoin()` 复用此封口，随后等待 lifetime 和搜索退出，再在 worker mutex 内释放规则引擎。已经进入 NonCancellable 耐久写的操作允许完成或按原逻辑回滚；页面不得因回返结果重新变成可操作状态。

特别处理两处原有事务链：旧终局 reservation 保存后、完成 sink 返回后，重新检查退役／lifetime，再决定是否启动后续 sink 或新局 checkpoint。退役不回退已经提交的 reservation、辅助次数或系列序号，但不能继续生成下一局。指定开局重开使用同一边界。存档写回结果无法确认时，也切回原 owner dispatcher 执行同一封口，而不是在 worker 操作 owner 控制状态。

`GoAiMatchViewModel` 暴露终身稳定的 `retirementCompletion: Deferred<Unit>`。首次 `beginRetirement()` 同步封禁所有公开操作、封 session 并取消 opening，然后只启动一个独立的 Main.immediate／SupervisorJob 清理任务：

1. 等待 opening 完成，包括取消期间迟到模型的 finally 释放。
2. 等待 session 的存储、搜索及规则引擎退出。
3. 在 worker 等待模型 owner 释放。
4. 全部成功才成功完成 Deferred；错误完成为 exceptional，保留首错并附后续 suppressed 错误。规则释放失败也不能跳过模型释放尝试。

`onCleared()` 仅复用同一清退，不再另起第二个 cleanup。独立任务不属于被取消的 viewModelScope，因而 scope 先结束时仍可等待。迟到模型的释放失败同样进入稳定完成结果，不能被 Job.join 吞掉。已退役 owner 不允许提示、悔棋、重开、切换模式、续局、速度更改或加载重试重新启动工作。

正常对局的配额、提示、终局悔棋身份、指定开局、自动演局和有限续局功能保留；封口只针对被退役的 owner，不通过删除功能解决竞态。

## 私有定向验证

证据目录 `.build/go-ai-retirement-20261018/`；仅使用已有依赖运行宿主 Kotlin 编译／JUnit，没有运行 Gradle、NDK、APK 安装、模拟器或 Git。三轮有效检查不是相加后的不同用例总数：

| 运行 | 实际结果 | 边界 |
| --- | --- | --- |
| `private-r1` | 编译失败，未执行测试 | 私有源清单漏了既有 `ChineseChess.kt`；保留日志后补齐，不修改公共接口 |
| `private-r2` | 编译退出 0，JUnit **121 项通过** | 11 个 Session／VM／续局／提示／模式／重开／Opening／终局悔棋／耐久写／Coordinator 测试类 |
| `private-r3` | 编译退出 0，VM **12 项通过** | 相对 r2 只改变 VM 的 opening.cancel 错误收集；没有重复其他 109 项 |
| `private-r4` | 编译退出 0，**28 项通过** | 相对 r3 只改变 Session 不确定写失败的 owner-dispatcher 封口；运行 Session 15、终局悔棋 6、耐久写 7 项 |

没有声称最终源码又重跑完整 121 项，也不把 121＋12＋28 当作 161 个不同用例。所有有效运行均核对编译／测试前后 30 个选定源码与测试文件未变；这些是定向私有检查，不是可发布 Android 构建收据。

新增 9 个专属用例覆盖：重复 begin／onCleared 返回同一个完成结果、scope 先结束但模型清理仍 pending、构造期间取消与迟到 candidate、在途耐久写排空、搜索占用结束前不释放模型或成功完成、所有操作退役后拒绝、规则／模型清理异常可观察且不漏后续释放、终局 reservation 或完成 sink 悬挂期间退役后不再创建新局。原功能回归也保留在上述 121 项中。

关键身份：

- r2 源清单 SHA-256：`269e1c7251d9bc079cdb6d643ff874091f60abfa6d8618b651d6a6b1fd90b9fb`；JUnit 日志：`47d2e02afbc795e64d7b854a9433652154741dab3b9b7bd692399fa64fd96111`。
- r3 源清单：`55a6287f0a4ee41f7817914d11a809d9be9917048b0a0cde9e9715c28a7e5df7`；JUnit 日志：`25833125e4b87b6b09a08deb41970f09e8fa6bb97d170557ca83332821a18d73`。
- r4 源清单：`e27c34d43e7b242601817229f7fe7c395eac1b509dceb71a31985383be83d03b`；JUnit 日志：`0e0c8da416e5a988f2a13eeeab5c2dfdfcabec7b9aff7c70c4b69be464a5db89`。
- 最终 Session SHA-256：`da826231386233916bf0c23f515bdd26a001b36b9cd073c239ba770382533a63`。
- 最终 ViewModel SHA-256：`4c22c322d7766aa2c5e1b48d68cc10eec082954ab12cb529ec0283e18088a4ad`。

## 集成与待验

统筹后续已补公共 `LocalDataWriterRegistry` / `LocalDataRestoreBarrier`：scope 结束仍保留未完成或失败清退；先启动所有清退再导航，取消所有旧子任务，并在 NonCancellable 中 join/await 全部、收集失败，最后重新检查调用方取消。失败不进入备份存储锁。公共两类私有检查 **12/12** 通过，证据 `.build/data-writer-retirement-20261018/private-c9ae86a355f540dc98a6d6239863665c/`；是独立宿主检查，不与专属121重复累计或代替真实VM/Native集成。

统筹的 registry 必须保留清退未结束／失败的 owner；统一屏障须在所有存储锁外启动并 await 清退，不能只 join viewModelScope。随后才能进入备份 gate／Room 事务，避免清理中的存储写入等待一个被屏障自己占有的 gate。

仍待统筹执行公共 registry／barrier 与真实 VM 的集成、正式 Android 编译、真实 Native 搜索／加载取消与存储备份专项及需要的页面验证。JVM owner、规则和搜索使用明确替身，不证明真实模型释放延迟或设备行为。raw 候选的 140 JVM／26 设备计划与其后结果不能用于宣称本清退切片已设备验收；独立冻结输入未被本切片改动。
