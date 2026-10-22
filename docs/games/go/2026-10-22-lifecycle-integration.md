# 围棋生命周期正式集成进展

## 最终节点验收：gal5

最终候选已通过本节点验收，拟用于 `v0.1.0-go-ai-lifecycle.1` 源码预发布。基线9f0f598，28个精确输入（24新增、4修改），1467份源码；输入manifest SHA `c47e55e18cd26ce2261a00bb47e078c95f90f93be444d5ec9341c02a37b75f39`，完整源码身份TSV SHA `1dca2f2107c2804fd532b724565dd4fa981f440639f8210a1b1487c9a8a101ce`。只有本节点输入进入候选，其他棋种、生产AI导航和未来数据切换排除。

正式Gradle15类156项JVM全部通过，无失败/跳过；双ABI主/仪器APK、Lint与Release合并Manifest完成，2分19秒/177任务。Lint零错误、7警告（EmptySuperCall六处、UseKtx一处）。最终生产修复在私有debug on/off各33项通过，但不累计为66不同方法，也不替代正式156。下方gal1..4失败均原样保留，不能再称全是断言问题。

Pixel_7_Pro/API37/x86_64/16KiB实际新APK **4/4**方法通过，21.787秒；证据 `.build/gal5/device-1fc05efd9b014f43816a2578c54e83c8/`。第四项确认真实规则与模型均已释放后分别注入两错，稳定聚合完整可访问、Registry保留失败owner、备份sentinel不可进入。前三项覆盖迟到模型、观察JNI run后的请求→规则→模型顺序及单一规则关闭失败。Native模型和规则真实，checkpoint是明确内存替身，时序和释放后故障为测试注入；不冒充Room/SAF备份、磁盘恢复或棋力。

主APK SHA `14854f3f6a51d9ee187f010ba0c15d4044571cbca7a83182f4d33c4b8f5bc12d`；App仪器APK `aa6f2188d83a2a5f553bd436ea53715bc64c4b1d05648e3206c1c385a6ce6bac`。设备后逐份复核1467源及两个实际安装APK一致，双ABI、Release无QA活动、未带AI页面/资源变化。没有清数据、卸载、重启AVD或操作旧象棋评测。无本次新UI，不重复无关截图或宣称AI页面视觉通过；实体设备未验收。

正式范围：Session/VM封口与可等待清退、请求lease排空、模型加载及迟到释放、稳定错误回执、Registry/恢复屏障等待独立资源、多错显式保真及不重复未知状态释放。错误机制及完整重现见 `2026-10-22-retirement-failure-aggregation.md`。Session公开支持的辅助、模式、开局和续局接口保留；没有完成sink时终局重开/自动续局拒绝，不注册no-op生产sink。

本节点不等于可玩AI入口。生产仍使用Gated FileRepo，AI双adapter未注册、AI旧磁盘保护保留；Room原子切换、唯一完整棋谱/完成账本、成长资格、AI页面导航/视觉、棋力与内容规模仍待。仅源码预发布，无APK附件；不新增第三方资源/依赖或扩大已有模型许可。以下为按时间保留的诊断过程，已由本节更新最终状态。

本轮为独立生命周期前置，不接生产AI入口、Room工厂、备份注册、完成棋谱或成长。基线 `9f0f598f163f3dbf3fbafd6da122da3fa8b0bf78`；生产行为不得用默认null completedSink冒充完成链。

## 候选与首次失败

`gal1`由精确27输入组装，共1466份源文件，完整身份TSV SHA `7e93453ef78475b52a37ab9719a5b85b24a0289a543e49992313f930b8256976`。正式Gradle152项JVM中149通过、3失败；4分30秒/105任务，构建被测试失败终止，不能宣称Lint及仪器APK完成。

三项均涉及跨协程异常的引用同一性：VM两项、Barrier一项。Gradle调试模式下协程恢复可复制异常，以原异常作cause；见 [官方实现说明](https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/debugging.md)。不关闭调试、不修改生产逻辑；测试沿同类型同message的恢复链严格找到原异常引用，同时沿恢复链检查附加错误仍可访问。不是只比较错误文字或删去失败断言。

`gal2`为初次测试修订快照，未运行；静态复审发现suppressed可能附在恢复副本，随后冻结`gal3`。gal3仅跑受影响两类17项，16通过、VM附加错误查找1项失败；证据完整保留。最终`gal4`再仅修该VM测试，同样检查恢复链上的suppressed；正在定向复核，尚不能作为通过或发布证据。生产源与首次gal1相同；无任何新生产修复。

后续gal4的VM12项仍11通过/1失败：首错原始引用已可找到，但secondary错误未出现在恢复链的suppressed中。故不能把全部失败定性为纯测试兼容；已转专门诊断实际异常图及潜在的恢复副本附加错误丢失，禁止放宽或移除完整错误可观察断言。所有正式单测进程已结束，无活跃比赛。Root启动独立gal1 `finish-r2`仅补APK/Lint任务，不重跑152项，不以构建成功冒充测试通过。

## 当前设备与剩余

`finish-r2`已完成独立APK/Lint补构建：2分14秒，171任务（81执行、90最新）；Lint零错误、7警告（EmptySuperCall六处、UseKtx一处）。没有重跑152项，也不改变其失败事实。主APK SHA `fbd24971e29d1c5008f6559fb9c54d644865a1268c796ea90080b6255701bbf6`；仪器APK `2fe28888b5cf3d3bbed132b5426d34cf562be4d24b249e81f452b926eab6ddeb`。

gal1的三项真实Native设备方法实际全部通过，17.301秒，API37/x86_64/16KiB；证据 `.build/gal1/device-31172831f2b44c3cbf61d7eea39baac2/`。分别覆盖模型迟到交付释放、观察JNI run后请求→规则→模型排空，以及真实规则关闭后单一故障阻止备份sentinel。内存checkpoint替身/注入边界不变，不证明Room/SAF或多错误聚合完整。这个结果属于修复前gal1，不能直接套到后续生产修复。

专属诊断已确认生产风险：异常恢复可能丢掉追加在中间恢复副本上的suppressed。Root已分配唯一共享窗口，允许实现不可变完整错误集合的显式失败容器及Barrier/Go必要修复，私有debug-on/off复现后再冻结正式候选；不以删除断言或关调试解决。

旧AVD/原生评测进程均已不存在后，正常启动既有Pixel_7_Pro，launcher84888/qemu75632，不清数据、不加载/保存快照。原象棋中断槽没有重跑。内存checkpoint及backup sentinel明确不等于Room/SAF备份。

下一：完成实际多错误聚合修复，保留旧失败并验证影响范围、逐份核对新候选/产物身份；生产修复后的设备结果另行取得。全部闭环后才准备精确提交、tag和源码预发布。本记录不是已通过声明。
