# 围棋正式新局与首次成长数据节点验收

对应 `v0.1.0-go-ranked-growth-data.1`，源码预发布，无 APK 附件。本文是本节点最终证据；同日实施／纯策略／测试计划中的“待验证”保留当时状态，以本文为准。不是整棋种或全目标完成声明。

## 范围与精确输入

- 基线 `7261c1a56ca4af804b970dd6624e5f73526f81c7`；最终独立候选 `.build/grg5`，27 份精确覆盖，1508 份完整源码。
- 候选输入清单 SHA256：`82ca219bab76555af2c4066731f9892cf8d8252ca3ce748e04cc0cdf3b11d250`；完整源码身份 TSV：`e291f40e59dc6aaf445f04af77db3ef33de95827048b998d915a3a34215c875a`。
- `MasterOfChessStrategyApp.kt` 只从已验基线增加数据库版统计工厂，`GoLocalStorage.kt` 只增加正式创建工厂；没有纳入当前工作区其余解锁、导航或 AI 页面 WIP。
- schema 18 SHA：`f46fcb134bbc88fd2a00a0c722ef24a9fdd120de6b3f60ec99940f8f172a0e3b`。17→18 只增加空的正式创建与结算表，不升级旧记录。
- 新数据策略、事务、两个备份 kind、公共 core-outcome snapshot guard、库存／清理与统计过滤已入本节点；来源、首次终局、棋谱与 outcome 原子配对，重复调用不重复成长。

## 构建与单测

grg3 离线正式构建 **110 项 JVM／10 类全部通过**。grg5 只改两份仪器测试（未创建数据库的清理、六适配器旧断言），其余每个完整源文件身份相同，故继承该 JVM 结果，不重复全量测试。前驱构建收据 SHA：`6780649303c815967e8ef874d457948f95daecf5089a2d32666e616f27c9a9db`。

grg5 正式构建 `assembleDebug`、`assembleDebugAndroidTest`、`lintDebug`、`processReleaseMainManifest` 成功：171 tasks，2m31s；双 ABI arm64-v8a／x86_64，Lint **0 错误／7 提醒**。源码前后 1508 份全部未变，18.json 已列入输入，Release 无 QA Activity 或 INTERNET 权限。

环境使用既有 JDK17／Gradle 与 Android NDK `30.0.16138531`；实际 CMake NDK 路径一致，clang++.exe SHA `46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be`，未调用 MinGW、未新增依赖。

## 最终设备与视觉

现有 Pixel_7_Pro，API37／x86_64／16KiB；未清应用数据。最终安装 APK 与本地候选散列一致：

- Debug：`5da17cf6e27563bde88502faa282b2ff3ff7dddb9ea4570ed36ac95831662a6c`。
- AndroidTest：`3bdbb86edba0497f6e44008d69e9ba0f63a575c88a58a03dea5c43a21be61b43`。

数据证据 `.build/grg5/device-16965298a9704b13a45be5804c741e9b/`：**55 个不同方法全部通过**，包括新成长15、备份／迁移8、生产工厂1、旧 raw 备份6、完成事务8、投影配对6、真实 Session3、完整 bundle8。45 盘独立真实 Native 双虚双方确认的白方贴子胜验证恰好 10／15／20 解锁；是规则／账本门槛测试，不是 NN 搜索或棋力评测。

验证了十课门禁、创建／完成失败与取消回滚、真正关库重开、幂等／收藏、自动参与与旧来源不发奖、时钟回拨保留棋谱、stale epoch、旧终局替换门禁、缺项／改写备份清库前拒绝、恢复晚失败全量回滚、旧备份替换与 deferred FK 内部拒绝后连接可重试。

视觉证据 `.build/grg5/visual-dbea70b65fdc4a81bf6ca6c9b19b29a5/`：设置真实 SAF2 方法、棋谱来源2 方法，100%／150% 各跑一次，**4 个不同方法／8 调用通过**；字体恢复1.0。16 张当前截图与 gca4 已审图像逐字节一致，继承全部已审结果，并打开两张当前150%代表图复看，无新增阻断问题。仅覆盖滚动视口，不声称完整 AI 页面验收。

合计 **59 个不同设备方法／63 次调用全部通过**。日志、图片与所有源文件身份在提交门禁复核。

## 保留的失败与修订

- grg1 受沙箱外 Gradle 缓存锁权限拒绝，按审批使用既有缓存后编译通过；未改全局环境。
- grg2、grg4 仅组装候选，未构建、不计通过。
- grg3 新成长15通过；备份8中两项在 finally 清理时失败：未使用的第二个懒加载 Room 数据库没有物理文件，不能要求删除返回 true。仅修测试的存在性判断；生产数据代码没有变化。最终 grg5 全部重跑设备组。
- 设备收据最初把八个测试类的 array.Count 写成方法数；八份原始 OK 日志及 SHA 均未变，按 runs.expected 求和修正为55，保留 `device-receipt-original-counter.json` 和修订脚本。没有改写测试结果或用修订代替重测。

## 后续边界

正式 AI 导航／页面尚未接显式创建，普通真人终局即时固定、完成失败重试、资格展示刷新与实际可玩全链路仍待下一切片。四档强度、完整内容、音效／性能、真实杀进程恢复和实体设备未因此验收。旧 raw／棋谱不冒充正式来源；本机创建事实不是认证或反作弊。第三方及源码发布边界见 `../../legal/go-ranked-growth-data-distribution-v1.md`。
