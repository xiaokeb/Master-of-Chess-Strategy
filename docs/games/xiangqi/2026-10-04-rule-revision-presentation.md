# 象棋规则修订提示与业务保存核验

日期：2026-10-04。设计见 `rule-revision-presentation.md`。这是局部验收记录，不代表象棋或全项目完成。

## 续接验收（审批恢复后）

本节为最新状态，下文「未完成与续接位置」保留第一次因额度中断时的历史记录。审批已恢复；原 AVD 进程已退出，启动既有 Pixel_7_Pro 时没有使用 wipe-data。

- 新增 `XiangqiRuleRevisionPersistenceTest.kt`，真实 App 仪器单类 **2/2**，0 失败/错误/跳过。每项内部遍历 v3、v4.0、v4.1：进行中存档关闭实际 Room 后恢复、通过真实 ViewModel 再走四步至原裁定终局、再关闭重开及显式重开；已结算普通人机局的原棋谱与成绩账本保持不变，冲突结果事务拒绝且会话/棋谱/账本均不改。测试数据库为独立 UUID，仅删除本测试创建的数据；没有用内存 DAO 代替 Room。此为数据库/引擎/ViewModel 生命周期恢复，不是 SIGKILL 或整机冷启动测试。
- 新增 `XiangqiRuleRevisionScreenTest.kt`，正式 Compose 单类 **2/2**：未知元数据不显示假定修订，两种修订有各自说明；真实 v3 引擎经正式重开按钮切换至 v4.1，提示同步。用直接 runner 运行，日志包含两次成功状态及 `OK (2 tests)`，无跳过。
- 新局 QA 实走与视觉完成：`current-after-restart.png/xml` 显示从旧局重开后的新修订与完整说明；`current-terminal.png/xml` 显示相同八步循环的黑方胜。100% 和 150% 字体下新旧说明均完整换行，操作及底部规则说明可纵向滚动到达；`current-150-top/mid/bottom/footer`、`legacy-150-top/footer` 原始截图已查看。首屏或滚动途中的部分控件超出视口属滚动裁切，不宣称全内容在首屏同时可见。
- 直接重复 `am start` 曾把 intent 交给现有新局 Activity，没有重新建立旧局；该画面改记为 `current-150-mid/footer`，未计作旧局验收。冷启动正确旧局后重新覆盖 `legacy-150-top/footer` 并核对实际「旧局兼容裁定」标题。
- 本轮最终双 ABI Debug APK、App 仪器 APK、Lint 通过；当前 **0 错误/7 提醒**（增加虎羊专属空 super 调用；其余为围棋同类提醒、三项依赖版本、国标麻将 Modifier 顺序、方棋未用资源）。这些专属提醒不由统筹代改。没有重跑已通过的全量 JVM 或 NDK 象棋回归。

Room XML 归档 `room-persistence.xml`，时间戳 `2026-10-04T07:55:05`；Compose 实际回执 `compose-screen.log`，原目录 `.build/instrumented-class/3d5694bab8f743079ad713bee227b4eb/`，均在前述证据目录保存。当前源码和 APK SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| Room 专项 | `9298192ac2ff832d2b2482e3da6061d26a5c1ca6ec508d0d9af7b6964f8d8bda` |
| Compose 专项 | `e93aaa66afcc8c4eaab8f4e4772485e1bc4f20ad769c760b14fe467eec31b6d9` |
| 原生象棋生产规则（未改） | `115fef46cb87225dfe370b7d899d9445706fb6a9ff8f4f0d0095df63b47b5c0e` |
| 最终 App Debug APK | `146a0a4e4592a0ca9353a8d4297daf10e70e0bf831d8a00ead0f44838e9781e3` |
| 最终 App 仪器 APK | `423b5fefc0207486a6ec7c6b87c98180182845e2a07d8b9febd78e0ff1e85194` |

### 测试运行器卸载与后续边界

Room 专项使用 Gradle connected 任务，其结束后 `pm list packages com.masterofchessstrategy` 实际返回空，目标 APK 已被运行器卸载。不能把「没有显式 pm clear」写成应用数据已保留；通常卸载也会删除应用私有数据，本轮没有卸载前备份，不能保证旧模拟器数据可恢复。已重新安装 Debug 包，实体设备及仓库未受影响。此事项已向用户说明。

追加通用 `tools/android/test-instrumented-class.ps1`，不清空数据、不卸载，更新安装后直接单类运行并核定真实数量，拒绝失败或跳过；安装失败不自动卸载补救。它已用上述 Compose 两项实跑通过，之后 `pm list packages` 确认目标和测试 APK 均仍安装。测试类自身仍须审核其数据影响。没有扩大清空授权，也没有执行旧杀进程脚本。

字体已读回 1.0，AVD 顶层实际为系统 Launcher；构建与设备窗口现已释放。六棋种仍未统一冻结，无暂存/提交/推送。后续继续残局正式流程、新规则 AI 梯度与其余棋种串行集成；全职业裁判棋例、所有内容量和实体设备仍未完成。

## 已实现

- 新增象棋可选修订元数据能力，不修改泛型引擎、动作/胜负码或 JNI 注册。Native 门面校验存档布局、修订字节及 CRC 并缓存；成功恢复和重开才清缓存，失败恢复保留原提示。
- 正式对局控制栏显示「旧局兼容裁定」或「连续杀证明修订」。未知元数据不冒充新规则；说明明确旧局不追溯改判、未完成证明不作为新增判负依据。
- 会话、备份、棋谱仍保存不透明原生字节，无 Room schema 或旧成绩迁移。三组夹具由 NDK 30 原生引擎实走同一八步循环导出：v3/v4.0 判和，v4.1 判红方负。
- Debug 专属 `XiangqiRuleRevisionVisualCheckActivity` 使用真实引擎和正式控件，恢复/保存只在内存，不写 Room、奖励或设置；重开预置为新导入局面。入口受 `android.permission.DUMP` 保护，Release 主 Manifest 不含此入口。重开的设备行为仍待验证。

## 实际证据

| 范围 | 结果 |
| --- | --- |
| 修订解码 JVM | 3/3，支持 v2/v3/v4.0/v4.1，拒绝未知修订、错误布局和 CRC |
| Native Kotlin 门面 JVM | 12/12，包含缓存、失败恢复原子性、成功恢复/重开失效及关闭边界 |
| 象棋 ViewModel JVM | 15/15，包含旧修订走子/悔棋保留、重开切换及未知能力不标注 |
| Room 会话内存 DAO JVM | 12/12，包含三修订字节往返和防御性复制；不是实际 Room 设备测试 |
| 棋谱内存 DAO JVM | 5/5，包含三修订终局保存和相同 ID 冲突拒绝；不是实际数据库恢复 |
| 备份 Codec JVM | 15/15，包含会话及棋谱字节精确往返、规范编码和复制隔离 |
| 真实 JNI 单类专项 | 4/4，0 失败/错误/跳过；元数据与真实恢复/重开一致，原有红黑循环、未知修订和申请事件回归通过 |
| App AndroidTest 编译 | `:app:compileDebugAndroidTestKotlin` 通过；国标麻将专属对话已删除其两处无效导入，统筹未改其测试 |
| Debug APK / Release Manifest | 双 ABI Debug APK 构建、安装成功；Release 主 Manifest 处理成功且不含 QA 入口 |
| Lint | 0 错误、6 提醒：三项依赖版本、围棋空 super 调用、国标麻将 Modifier 顺序、方棋未用资源；不由统筹越界修改专属代码 |

JVM XML 已归档至 `.build/test-evidence/2026-10-04-xiangqi-rule-presentation/`：`decoder.xml`、`native-facade.xml`、`viewmodel.xml`、`session.xml`、`record.xml`、`backup.xml`。JNI 同目录 `native-jni.xml`：时间戳 `2026-10-04T04:38:02`，实际单类四项，无多类筛选数量推断。构建采用既有 JDK 17、离线 Gradle 和 NDK 30 LLVM；未新增依赖或调用 MinGW。

首次构建暴露 Debug 入口嵌套 `apply` 的 CRC 参数绑定错误，改用明确的 `legacy` 字节数组后，同一串行构建通过。首次启动误用了不存在的 `.debug` applicationId，按既有 `com.masterofchessstrategy` 重启成功；没有为绕过问题修改项目 applicationId。

## 已查看的模拟器画面

Pixel_7_Pro / `emulator-5554`，x86_64、横屏、3120×1440、字体 1.0。只使用 RAM QA 对局，没有清空应用数据。

- `legacy-initial.png/xml`：旧局兼容标题、完整说明及恢复反馈可见，棋盘和操作区无横向裁切。
- `legacy-terminal.png/xml`：经真实棋盘点击实走八步循环，顶部显示「和棋」，仍标旧局兼容；终局操作提示及重开按钮可见，冲突动作禁用。

原始截图和控件树位于 `.build/visual/2026-10-04-rule-revision/`，两张截图均已实际查看。旧裁定终局与原生/JNI 证据一致；这不能替代新修订或大字体视觉验收。

## 未完成与续接位置

继续点击旧局终局「重开」的命令被审批服务用量限制拒绝执行，错误明确是审核未完成，不是操作被判定不安全。未绕过审批。模拟器最后确认停在 RAM QA 旧局和棋界面，未确认返回首页；勿假定设备窗口已释放。

1. 审批恢复后，点击正式重开，核验新修订提示；实走同一循环核验黑胜，再检查 150% 字体并恢复原字体和返回首页。
2. App AndroidTest 编译阻点现已解除，补真实 Room 进行中/已结算旧局关闭重开、原子提交及旧成绩不改写专项；不能以本轮内存 DAO JVM 测试代替。
3. 继续残局正式流程与新规则下 AI 梯度验收，未解决的内容量、全职业裁判棋例及实体设备验收另行跟踪。

六棋种节点未统一冻结，本轮无暂存、提交或推送。源码和文档保留工作树，其他棋种专属文件未代改。

## 审批恢复后的续接（以此节覆盖旧待续状态）

新局重开后显示当前修订，实际相同八步循环判黑胜；新旧说明在150%字体下均已查看，字体恢复1.0，设备返回Launcher。截图保存在同一 `.build/visual/2026-10-04-rule-revision/`：`current-after-restart.png`、`current-terminal.png`、`current-150-{top,mid,footer,bottom}.png` 及 `legacy-150-{top,footer}.png`。这些是既有续验图，本次整理文档未重新运行设备。

真实Room进行中/已结算旧局恢复、原成绩不改写两项通过：`.build/test-evidence/2026-10-04-xiangqi-rule-presentation/room-persistence.xml` 为 `XiangqiRuleRevisionPersistenceTest`，2测试、0失败/错误/跳过，原始时间戳 `2026-10-04T07:55:05`。正式Compose未知/新旧元数据与真实旧局重开两项通过：单类日志 `.build/instrumented-class/3d5694bab8f743079ad713bee227b4eb/instrumentation.log`，实际收据 `OK (2 tests)`，stderr为空。最终Lint为0错误/7提醒，见同目录 `lint.txt`；不将其他棋种提醒算作象棋新验收。

首次Room connected任务结束时测试运行器自动卸载目标应用；后来已重装，但旧模拟器数据不能保证恢复。后续使用 `tools/android/test-instrumented-class.ps1` 更新安装和直接单类runner，不清空/不卸载，正式Compose续验后两APK仍保留。测试本身的数据影响仍须单独检查，旧杀进程/清空授权不扩大到其它专项。

上述验证是数据库关闭重开与界面新建局，不冒充真实进程死亡。规则展示切片的旧阻点已解除，下一实施为 `endgame-acceptance-next-node.md` 和新规则基准专项；完整职业棋例、AI梯度、全部内容/实体设备仍待验。共享窗口已释放给后续围棋任务；六棋种未冻结，无提交推送。
