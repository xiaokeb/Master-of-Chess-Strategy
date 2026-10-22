# 围棋 AI 资源退出：多错误保真修复

## 问题与证据

正式候选 `gal4` 的 `GoAiMatchViewModelTest` 共12项，其中规则引擎和模型同时退出失败用例失败。原实现把次要异常追加到第一次捕获的异常对象；该对象可能已经是协程堆栈恢复副本。失败不是“原始对象身份断言太严格”：次要异常确实可能在下一次挂起边界后完全不可达。

本机实际使用的 `kotlinx-coroutines-core-jvm 1.11.0` 缓存源码中，`StackTraceRecovery.kt` 的 `causeAndStacktrace` 会取回恢复副本的原始 cause，随后 `tryCopyException` 重新构造异常。`ExceptionsConstructor.kt` 的反射复制不搬运副本上后来追加的 suppressed。源码包 SHA-256：`3e0847c0b463965404d5b6fe5d6868000a220b050180dec68501801e9439976a`。本结论只针对已检查版本与路径，不扩大成所有平台的协程行为。

本地最小重现位于 `.build/go-retirement-failure-diagnosis/`：

- `repro-debug-on-final`：`-ea`、debug=on、stacktrace.recovery=true。await 前的规则异常副本带有原始 cause 与模型错误 suppressed；await 后新副本只保留原始 cause，模型错误从完整可达图消失。
- `repro-debug-off`：同一重现，仍开启 JVM assertions；debug=off 时不发生该副本替换，次要错误仍可达。此运行仅为定位变量，不是通过关闭调试来修复。

两个早期诊断驱动未正确处理 PowerShell 单元素数组和 Kotlin 参数文件反斜杠，未启动重现主类；对应目录保留，不算通过或生产故障。

## 修复契约

公共 `LocalDataWriterFailure` 显式持有独立、不可修改的 `failures` 列表，第一项同时作为 cause，其余项同时作为 suppressed 诊断视图。聚合状态不再追加到原始异常或协程恢复副本上；没有反射解包、依赖私有协程 API 或只保留消息的处理。原错误仍可通过 cause 链精确追溯，嵌套聚合保留完整对象图。

空列表无异常，单个错误原样返回；同一对象重复出现仅记一次，不按消息/异常类型误合并独立错误。共享恢复屏障、围棋 ViewModel 与 Native runtime owner 使用独立列表收集错误，然后一次性构造最终失败。失败仍阻止备份存储入口；其他 owner、规则与模型的退出仍必须排空，不以首个失败提前返回。

晚到模型已在 opening 的 finally 执行一次释放，失败时保留 owner 与失败回执，不再对未知释放状态的同一资源重复处置。普通规则退出失败仍不会跳过模型释放。

## 验证与边界

私有 Kotlin/JUnit 驱动只用现有缓存依赖；编译与 JVM 均限定 `ActiveProcessorCount=1`、`Xmx512m`，`-ea` 始终打开。受影响三类为 ViewModel、RuntimeOwner 和共享恢复屏障；分别覆盖稳定回执、晚到候选、持久化写入排空、搜索退出、请求/模型多错、prepare 失败、单错兼容、重复错误、不可变列表、嵌套聚合、多次挂起、导航失败及其他 owner 继续排空。

最终运行与输入 SHA 由 `.build/go-retirement-failure-diagnosis/final-v2-debug-on/`、`final-v2-debug-off/` 的 `inputs.json`、`receipt.json` 和 `run.log` 给出。两种模式是同一用例集合的重复验证，不能相加成不同测试数量；早期失败日志保留。

设备测试另新增第4个方法 `failuresAfterBothRealNativeReleasesRemainObservableAndBlockBackup`：使用既有许可模型、真实 Native 规则与模型；两者均在真实释放成功后分别注入不同异常，验证完整聚合、稳定回执、Registry 保留与备份 sentinel 不可进入。原3方法保留。私有驱动只编译这4个方法，不代表模拟器已经运行；正式 Gradle、APK、Lint 与新候选4个设备方法由统筹随后冻结验证。本修复不代表围棋 AI 已接入正式导航或生产 Room 数据闭环，不更改其他棋种规则。
