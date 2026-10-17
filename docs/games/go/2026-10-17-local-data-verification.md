# 围棋本地数据节点：独立候选验收

状态：本节点构建、必要模拟器与视觉门禁通过；仓库发布收据以对应 tag/Release 为准，不代表全棋种完成。

## 范围与候选身份

包括本地双人会话、稳定身份、Room 完成谱与来源、详细教程/残局进度、只读旧源迁移、注册式备份与恢复屏障、正式棋谱和真实 SAF SGF 入口。保留现有象棋、虎羊以及国标备份依赖，不宣称围棋全部功能完成。

- 基线：`de5bd3f972c1e015533a02dfbdbb9db13bc65cfe`。
- `rgl1` 首次正式构建在 App 测试编译处失败，漏收 `CompetitionMahjongMatchFixtures.kt`，不是玩法失败；候选与失败日志原样保留，没有删除测试。
- `rgl2` 仅在原 1304 份原样源码上加入该必要夹具，共 1305 文件；原 30 份替换保持不变，新增 161 份，实际 overlays 191。普通目录、无 worktree 或 Git 元数据。
- manifest SHA：`6769a860d654fe7815bc10a45cf39599f408b9112156125039868569c923b4ed`。
- source identity SHA：`b78514ce8c3122c5f95b60eb14dba4667d2cd5a5ce550122125110e8ea348999`。

私有 `.build/rgl2/` 保存清单与组装收据，父候选及后续 AI/解锁/对应残局源码不混入本次代码身份。

最终 `rgl3` 只修正一份 SAF 设备夹具，其余 1304 文件与 rgl2 逐字节相同；仍共 1305 文件/191 overlays。source identity SHA `56acb2caec72e65947832af55cac3ea51493c410587f141ab060af12a1dee1ea`，manifest SHA `1c501acb0f4b7ffab6364bf7cee7c4162c623ed0bcce48e4d2e82e0186f36156`。41 秒/98 任务实际重建 App 测试 APK（含其 Gradle 依赖），没有重跑不受影响的 JVM；实际主包与 Native 测试包沿用 rgl2 原字节，因此下方 399、57、22 的源及运行时绑定仍成立，不把它们改称一次新的全量运行。

## 正式构建证据

`rgl2-formal-20261017-r1` Gradle 实际退出 0，2 分 25 秒、213 任务。NDK30 LLVM 双 ABI、KSP、Debug 主/仪器 APK、定向 JVM 与 Lint 均执行；不是混合工作区旧包。

| 模块 | 测试套件 | 实际测试 | 失败/错误/跳过 |
| --- | --- | --- | --- |
| App | 49 | 365 | 0/0/0 |
| engine-api | 1 | 12 | 0/0/0 |
| engine-native | 4 | 22 | 0/0/0 |
| 合计 | 54 | 399 | 0/0/0 |

实际 Lint XML：0 错误、6 提醒。第三方 nlohmann 字面量声明的 Clang 提醒保留，不擅自改 vendor。

构建后的自制检查器先后误报了 Gradle 生成的根 `build/reports/configuration-cache` HTML 和 JNI 库名（实际为 `libmocs_engine_native.so`）。修正仅限检查器，保留失败收据；没有为此重新运行 Gradle。`rgl2-postflight-20261017-r2` 重新逐源复核、绑定同一成功日志/退出收据、核对原 HEAD/index、实际 APK 双 ABI 和 schema13–15 打包字节，通过。不能把检查器失败写成先前 APK 曾缺 ABI。

| 产物 | SHA-256 |
| --- | --- |
| 主 Debug APK | `28fa70112eebc902e3db129fed5f4121b2d6f7782b932a2690b59949d280f549` |
| App 仪器 APK | `442e6532ab8aec5bd93e3755e4787b7b2429c1bdfe415587e0e9d5ffc8ae92bb` |
| Native 仪器 APK | `a6333dc7e0bfedde9cebcf4053786c313a3301d0e19d13646b286bce78473c52` |

最终 rgl3 App 仪器 APK SHA：`dfb0627b0bcdfe2781bc6935232d9b334e00b77c1c78fc10c1a212b2072701ed`；schema13–15 再次从包内逐字节核对。主/Native 安装路径及设备内实际 APK SHA 均核验，未清应用数据。

最后仅补 rgl3 正式 Lint，34秒/103任务（54执行49复用），退出0/0错6提醒，XML SHA `2061a1f953d72705e64010f26a313e80ea35ae0891521c2d3c939fdbed0919bb`。没有重复 JVM 或设备测试；实际主APK权限中没有 INTERNET。

## 模拟器与视觉

Pixel_7_Pro，API37.1/x86_64/16 KiB；冷启动不清数据、不读写快照，恢复字号 1.0、回 HOME。ARM64 仅构建，不冒充实体设备验收。

- 数据 57 项：详细进度、13→14/14→15迁移、永久恢复封口、Room终局归档、注册备份/准备失败回滚、真实Native完成谱、AtomicFile与身份；另含象棋完成事务、虎羊及国标真实混合备份回归。
- Native/JNI 22 项：Go规则/SGF、国标公开v2与跨类型handle、虎羊，以及真实 KataGo 对9/13/19路与让子0/5的搜索。不以此宣告四档棋力。
- 真实 DocumentsUI 的4个不同方法：往返/取消/坏UTF-8、1000001字节拒绝、归档首帧导出完整主线、恢复后的迟到URI拒绝旧谱写入。
- 生产Root视觉普通/150%各2项；真实Settings导出/恢复/取消/坏容器/恢复后重新落子，普通/150%各2项。
- 新原始截图共32张，完整SHA去重25张，全部不同图已实际查看；没有编辑或旋转图像。棋盘/来源/错误与备份状态清楚；下方控制使用滚动容器，截图的视口边缘不能当作“所有控件同时完整显示”的保证。系统DocumentsUI截图遵循系统自身方向，不据其方向判应用横屏失败。

SAF 首批在两项已通过后，第三项进入`@After cleanupOnlyOwnDocuments`的嵌套runBlocking等待。JDI短暂线程快照后均恢复/detach，确认主Looper空闲、清理阻塞；未据此武断认定具体Main或gate根因。只停止该挂起仪器App，保留自有DB与日志，不清数据。rgl3使用独立清退任务＋15秒真实时间上限并驱动Compose调度，等待实际回放controller关闭；未确认清退则不关闭/删除DB。仅补两项原未通过方法，均通过；保留先前两项的独立status0和未变方法体，不重复57/22或已过SAF方法。整体失败记录没有改写成通过。

完整收据位于 `.build/go-production-storage-20261016/` 的 rgl2-data/native、rgl3-saf-pending-r2、rgl3-visual/settings-r1 和 rgl3-visual-review 对应文件。最终发布检查将重新核全部源、日志与截图散列，再核索引及干净导出；源码节点不附 APK。

## 发布门禁

前两项构建/设备视觉已按上述身份收口。下一按节点清单串行显式暂存、提交推送、附注 tag 与 prerelease，并核远端目标及链接；未通过索引与导出核验前不发布。

desugar 固定 2.1.5，MIT/vendor 与内置版权文本已静态核对；新增二进制的精确源码/provenance 与分发义务仍须在 APK 分发前闭环。本次拟为源码节点，不附混合或未满足分发门禁的 APK，不扩大 Pikafish/NNUE 授权，也不作法律完备结论。

真实杀进程、实体/API24/ARM64 设备、AI 完整持久化与排名、难度标定、完整内容量及美术音效仍分别跟踪，不用本节点替代最终验收。
