# 正式会话持久事务节点验收

适用 `v0.1.0-go-formal-session.1`，源码预发布，无APK附件。本文是同日计划及冻结测试说明的较新最终证据：其中“待编译／未运行”是当时状态，不代表本次最终候选。不是完整 AI 页面或全目标完成声明。

## 输入与实现

基线 `c140df9b5d71e0959e886fa4675020b9b4aaed34`，最终 `.build/gfs3`：17份精确覆盖，1524份完整源码。候选清单 SHA `d45e9e2cac591d818e5a2f274e1194b7bf158efebeb3345fd651eabf7f02094d`；完整源码 TSV SHA `564d00e928e3c645ba1cdb8dfc4ed60578aeff435c62029388e68d811274f739`。

`GoAiFormalMatchRepository`、同epoch Room门面与显式新局调用已接 Session／生产存储工厂。普通load/save不升级旧局；丢失创建确认时，活动GAI与正式来源必须同时匹配。保留旧三参数保存入口、engineFactory尾随lambda调用及默认训练行为。

即时完成是显式可选策略，必须有完成sink：双方确认终局后先固定GAI，再提交Room棋谱／关联／成长事务；`completionReserved`、`completionStored`、`completionPending`各有独立含义。失败可同局重试，不撤销已固定结果、不重复奖励。恢复未reserved旧终局若固定失败，仍显示待保存且不能悔棋；不会补来源或成长。已进入保存的退休必须等待，未进入的工作不会在旧页继续生产。VM已转发策略和重试，正式Route／导航／重试按钮尚未接。

`GoLocalStorage.kt`只从已验基线摘出新增正式门面工厂，未顺带纳入当前解锁WIP。schema18、数据备份、公共导航、UI资源及其他棋种均未改变。

## 构建与单测

gfs3正式 **103项JVM／10类全部通过**，含新增初次读取期间退休的确定性回归。gfs1曾过102；gfs2仅增加三份仪器源并按全部其余源相同继承102，但最终发现退出竞态后未沿用这一结果，而是修复后正式重编并重跑103。

gfs3离线 `assembleDebug`／`assembleDebugAndroidTest`／`lintDebug`／`processReleaseMainManifest` 成功，177 tasks／1m52s；双ABI arm64-v8a、x86_64，Lint0错7提醒。使用既有JDK17及Android NDK30.0.16138531 LLVM/Clang，无MinGW或新依赖；源码1524份前后未变，schema18 JSON身份不变，Release不含QA入口或INTERNET权限。

## 最终设备证据

现有Pixel_7_Pro，API37／x86_64／16KiB，未清应用数据。安装散列与候选一致：

- Debug `09856b4b9f462d6416f68cd8e6b9d49fa6d726e994049ce6524ef7eedbe94893`。
- AndroidTest `8de8d017e1ff982bc34f064ab24498bd13fb81c66fe4f68fe13652f2d1742043`。

`.build/gfs3/device-2cdb1829e50644ed82b29b32cceaf0e4/` **30方法全部通过**：正式创建5、即时完成6、边界失败4、旧Room Session3、实际Native资源所有权4、旧Native会话3、自动续局2、重开3。

覆盖真实创建先于ready、十课门禁无隐式降级、旧未知局不补来源、正式重开新UUID／新来源、四种操作一致epoch；完成即一份棋谱／成长、晚失败／真实关库重开／同局重试、提交后丢失确认与收藏、init固定写入失败、正在sink中退休必须等待、有无真实来源的创建确认隔离。旧训练终局撤销产生新UUID与父归档来源，而不是篡改原终局。

新增事务走步为明确Pass脚本，只证明实际规则与业务。旧资源所有权4项实际加载／关闭既有Native模型，但不据此宣称完整NN对局或棋力。

`.build/gfs3/visual-53607eae9ce34fe0ab4dd5c283e80f55/` 来源回放2方法在100%／150%各跑一次，**2方法／4调用通过**。4张当前图按字号／文件名／SHA逐项匹配grg5已审视口，继承该视觉审阅；字体恢复1.0。未改Settings/SAF，因此不重复其完整套件、不把旧12图计作当前图。总计 **32个不同设备方法／34次调用全过**。

## 保留的初轮失败

- 私有r1发现新boolean放在末尾会破坏engineFactory尾随lambda；生产参数调整到factory之前，未修改旧测试来迁就。
- 私有r2有8项旧三参数保存调用因新源码／前驱二进制混合类路径而NoSuchMethodError；保留三参数委托入口后r3共102通过；正式gfs1仍独立重编全模块并重跑102，未把私有结果冒充正式。
- gfs1设备新增15及旧Room／Native owner7通过；下一旧Native测试因源码未纳候选而ClassNotFound，后两组未跑。gfs2显式补三源并通过30；这不是最后发布候选。
- 发布前补的r4初次读取退休专项稳定失败：退出期间load返回后仍创建一次。修复init读取前／后和新写入前的退休检查、提交路径的退休门禁；r5私有103通过。已进入的保存仍按原约定等待，未开始的新局／Native owner不在退出后创建。gfs3正式重新构建、运行103及同最终APK完整设备组，不发布前两候选。

其他棋种或象棋预算v2专属在制改动未纳本提交。全项目目标保持进行中；后续接正式AI设置／导航、失败重试按钮、资格刷新和可玩／视觉闭环，四档强度、内容、音效性能与实体设备分别验收。
