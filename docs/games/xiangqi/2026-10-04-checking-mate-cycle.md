# 连续杀循环接入与旧局规则隔离

日期：2026-10-04。状态：有界连续杀已接入新局原生裁定，并完成原生/JNI 专项；业务存档、版本提示与实际新包视觉仍待验收，象棋整体节点未冻结。

## 实际循环正例

项目本机候选搜索找到合法的一杀一将循环，随后固定成专属测试，不导入题库或第三方图谱：

```text
2b6/4k4/3a5/2PC2C2/3NPN3/9/9/5K3/9/9 w - - 0 1
红炮 (3,3)→(4,3)
黑将 (4,1)→(3,1)
红炮 (4,3)→(3,3)
黑将 (3,1)→(4,1)
```

第一着不将军，走前连续杀已完整反驳；走后一次检查深度为未知，默认深度下有连续杀证明（红方 86 节点）。黑将应对后威胁又被完整反驳，炮退回为将军，将退回恢复原棋盘及行棋方。不是把一步杀当成连续杀，也没有填造棋局历史。

循环走两遍，初始局面第三次出现：

| 规则版本 | 红方进攻 | 旋转 180°、交换阵营的黑方进攻 |
| --- | --- | --- |
| 旧 v3 / 修订 0 | 和棋 | 和棋 |
| 新 v4 / 修订 1 | 黑胜，红方一杀一将须变着 | 红胜，黑方一杀一将须变着 |

NDK 及正式 JNI 分别覆盖以上四组；第一圈后重新打开引擎，第二圈裁定后再次恢复、悔棋至进行中、重走末着，保存字节与裁定均保持原修订。终局继续走子被 `apply()` 拒绝。

规则文本：[2020 第 24.2/24.13 条](https://cnchess.net/rules/ChapterSixSection24.html)、[第 26.1.2 条](https://cnchess.net/rules/ChapterSixSection26.html)。新求解仅在循环重型分类执行，普通走子不运行检查链；已有立即杀、帅将应将例外及 Pikafish 明确胜负优先不改。

## 旧存档不会追溯改判

采用 [MOCX v4 格式](../../../config/schemas/chinese-chess-position-v4.md)，在申请事件表之后、CRC 前加规则修订号。

- 新标准局和无历史 v2 摆局：v4 修订 1，启用已证明的连续杀扩展。
- 旧 v3 的进行中和已终局存档：原 v3 格式、旧循环裁定固定，不升级已走历史；已结束和棋仍可恢复为和棋，不改已结算成绩。
- v4 修订 0：保留旧裁定并继续写 v4.0；悔棋保留规则修订，`reset()` 新开局才切换 v4.1。
- v2 带历史：保留既有自然限着旧值重算与 v3 旧循环裁定流程。
- 未知修订 2/255、缺失/多余标记、错误 CRC、伪造终局：拒绝恢复且当前棋局字节不变。把已有终局换成另一修订、只重算 CRC 而保留旧判定也会被一致性校验拒绝。

没有修改 Room schema 或用户数据库，也不放宽原历史合法性和终局一致性检查。自然限着的一次误申请、再犯判负和事件表恢复已通过新 v4 JNI 专项；不把标记误读为申请事件。

精彩局自动收藏的子力解析已支持 v4.0/v4.1，仍核完整长度/CRC、拒绝未知修订；原 v2/v3 兼容测试保留。该解析器不充当棋局历史合法性验证器。

## 最终验证

- NDK 30 x86_64 `mocs_xiangqi_checking_mate_cycle_test`：四组新旧/红黑循环、真实历史恢复/悔棋/重走通过，退出 0。
- `mocs_xiangqi_snapshot_revision_test`：规则版本往返、旧终局固定、未知/破损恢复原子性通过，退出 0。
- 纯证明 `mocs_xiangqi_checking_mate_test` 和既有 `mocs_engine_chinese_chess_test`：按最终重建源在 AVD 退出 0，未改生产规则迁就测试。
- 新 JNI 类 `NativeXiangqiRuleRevisionInstrumentedTest` **3/3**，XML `tests=3 failures=0 errors=0 skipped=0`，时间戳 `2026-10-04T04:06:21`。其中循环项内部覆盖四组。
- 既有 JNI `NativeBridgeInstrumentedTest` **6/6**，单独 XML 时间戳 `2026-10-04T04:04:43`。不是一份合并 9 项报告。
- `ChineseChessHighlightPolicyTest` JVM **5/5**，旧 v2/v3 及 v4 两修订、未知/破损规则标记均覆盖；无跳过、失败或错误。
- 新专属原生测试及生产源：NDK 30 ARM64 Android 24 严格语法检查退出 0。生产库/Debug APK 双 ABI 构建通过；ARM64 未设备运行。
- Lint **0 错误、4 提醒**，没有改国标麻将专属提醒或其无效 AndroidTest 导入。

执行时，逗号分隔两类的 Gradle runner 过滤在本机两次均只执行旧 6 项（关闭配置缓存也一样），故不归因于缓存、不把成功输出当成 9 项执行；最终新专项改为单类过滤并核对 XML。新测试首跑 2/3，唯一失败是误假定“裁定终局时几何合法着列表为空”。现有接口仍返回几何合法着，实际 `apply()` 已拒绝终局走子；修正断言为 `INVALID_STATE`，未更改这个无关契约。修正后单类真实通过 3/3。

```powershell
& 'E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe' --build .build/native-x86_64 --target mocs_xiangqi_checking_mate_cycle_test mocs_xiangqi_snapshot_revision_test mocs_xiangqi_checking_mate_test mocs_engine_chinese_chess_test -j 2
# 上传各原生目标至 /data/local/tmp，chmod 755 后在 emulator-5554 逐个执行。
.\gradlew.bat :engine-native:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.engine.games.xiangqi.NativeXiangqiRuleRevisionInstrumentedTest' --offline --max-workers=2 --console=plain --no-configuration-cache
.\gradlew.bat :app:testDebugUnitTest --tests 'com.masterofchessstrategy.records.ChineseChessHighlightPolicyTest' --offline --max-workers=2 --console=plain
```

最终源码 SHA-256：

- `checking_mate_cycle_test.cpp`：`fb088c14b6b50ecea45a2d8912e15e5ef995880c6fc5655971aa9388d209aa43`。
- `snapshot_revision_test.cpp`：`b72f25efc422f97c56b4944c05d68fd74819b9c2b66ca7faf1d8bea65a2cd56f`。
- `src/chinese_chess.cpp`：`115fef46cb87225dfe370b7d899d9445706fb6a9ff8f4f0d0095df63b47b5c0e`。
- 新 JNI 类：`addf8c91741db47145480a0862c70e35c3471181e150b5e413f213c870177546`。
- 本地 Debug APK：`6680fab25c12fa692a3d835d64a52a841cfb5f967c46f0c7810f327d3542ecdb`；仅构建，尚未拿此包做新界面视觉验收。

本机证据归档在 `.build/test-evidence/2026-10-04-xiangqi-rule-revision/`，新 JNI XML SHA-256 `79378e2772343fb3fbd24921e6a9758d332ad26a754ea8aa20189a1b48cf06b8`。历史报告保留为当时状态，本报告才是接入后的最新状态。

## 后续与交接

接下来补旧局规则版本提示、业务会话/备份/棋谱不透明字节往返、真实 Room 旧已结算记录及新包操作/视觉。原生/JNI 不等于这些业务链路已验收。四次将军/512 节点是本修订的证明能力边界，未知不得制造新增判负；任意深度、全职业棋例、预算未知的裁判流程、AI 梯度新规则基准及实体设备仍待完成。旧 AI suite 结果是当时规则基线，不重贴成新修订的梯度验收。

仅修改象棋源码/测试/专属文档及统筹串行持有的 CMake；JNI 注册、公共 Kotlin 引擎契约、导航、Room、其他棋种与第三方库未修改。App AndroidTest 导入按用户决定仍由国标麻将对话修复，未过滤豁免。没有清空 App 数据；循环搜索已退出、AVD 回系统首页，构建/设备窗口释放。六棋种统一节点尚未冻结，未暂存、提交或推送。
