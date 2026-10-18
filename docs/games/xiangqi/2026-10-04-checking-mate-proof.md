# 连续将军成杀证明模块

日期：2026-10-04。状态：只读证明模块已实现并验证；新增循环判负未启用，象棋整体节点未冻结。

本页记录接入前切片。后续已在 v4.1 规则隔离下启用有界连续杀并通过真实循环与旧局恢复专项，最新状态见 [接入报告](2026-10-04-checking-mate-cycle.md)，不要把本页历史“未启用”当作当前状态。

## 实现

`ChineseChessEngine::checking_mate_proof()` 仅检查棋盘，不修改行棋方、历史、计数或终局。攻击方只走合法将军，防守方的全部合法应将都必须被覆盖；无将军的困毙不作为将死证明。结果有三态：

- `proven`：找到有限、覆盖所有应将的连续将军成杀策略。
- `refuted`：所有检查分支已完整反驳，不是简单的搜索范围内未找到。
- `inconclusive`：节点预算、检查深度或参数边界使证明未完成。

默认最多 4 次攻击方将军、512 个节点；接口允许最多 8 次。迭代加深共用节点预算，调用链最多 17 个棋盘帧；本链同棋盘/行棋方的循环不能充当有限杀棋证明，也不缓存这种路径相关反驳。该接口不调用 Pikafish、NNUE 或网络，不作为正式 AI 替代。

新增实现/测试位于 `engine-native/src/main/cpp/games/xiangqi/`，公开声明仍在现有象棋头；主/测试 CMake 由统筹串行仅追加该实现和 `mocs_xiangqi_checking_mate_test`。没有新增 Kotlin/JNI 公共契约。

## 专项证据

所有夹具同时覆盖红先与旋转 180°、交换阵营的黑先版本。

| 棋盘/边界 | 实际结果 | 红/黑证明节点 |
| --- | --- | --- |
| 一次将军成杀 | 已证明 | 10 / 2 |
| 两次将军成杀，仅给一次检查深度 | 未完成，不误报反驳 | 7 / 7 |
| 同一棋盘给两次检查深度 | 已证明 | 32 / 27 |
| 节点预算 1 | 未完成，不超过预算 | 1 / 1 |
| 检查深度 0 | 未完成 | 1 / 1 |
| 非法深度 9、零节点预算 | 未完成、不展开节点 | 0 / 0 |
| 无合法将军 | 已反驳 | 1 / 1 |
| 将军后存在吃子防守 | 已反驳 | 7 / 6 |
| 炮仍挡住己马马腿 | 已反驳 | 3 / 3 |
| 移开挡马腿的炮后，只给一次深度 | 未完成，并非一步杀 | 11 / 11 |
| 同一静着后的棋盘，默认深度 | 两次将军成杀已证明 | 48 / 15 |

另有无将军困毙不误认成杀的断言。每次证明核对节点不越界、序列化字节不变；一着杀、两着杀、吃子防守及静着前后还通过独立浅层枚举核对：该枚举使用公开 `legal_actions()`、`apply()` 与终局查询，不调用证明模块的棋盘递归，并要求覆盖所有合法应将。

最终执行：

- NDK 30 x86_64 新目标在 Pixel_7_Pro AVD 运行，24 行红黑诊断及其余断言通过，退出 0。检查使用异常，不依赖 `NDEBUG`。
- 既有 `mocs_engine_chinese_chess_test` 在同一 AVD 退出 0，包含循环、保存终局派生、恢复、悔棋与自然限着回归。
- `NativeBridgeInstrumentedTest` 正式 JNI 回归 **6/6**；最终 XML 为 `tests=6 failures=0 errors=0 skipped=0`，时间戳 `2026-10-04T03:34:40`。
- NDK 30 ARM64 Android 24 对新实现和测试执行 `-Wall -Wextra -Wpedantic -Werror -fsyntax-only`，退出 0；ARM64 未设备运行。
- 双 ABI Debug APK 构建通过；Lint **0 错误、4 提醒**，含三条依赖版本提示和国标麻将专属页面的 Modifier 参数顺序提示。未代改该页面。

```powershell
& 'E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe' --build .build/native-x86_64 --target mocs_xiangqi_checking_mate_test mocs_engine_chinese_chess_test -j 2
# 上传测试至模拟器 /data/local/tmp 并 chmod 755 后执行。
.\gradlew.bat :engine-native:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.engine.NativeBridgeInstrumentedTest' :app:assembleDebug :app:lintDebug --offline --max-workers=2 --console=plain
```

最终 SHA-256：

- `checking_mate_proof.cpp`：`807afcdf2ebd472d708d8da4e4bc1a036b00d4c01a936069bc18bceb984c5e77`。
- `checking_mate_test.cpp`：`ede87d4609b1da0282030a4f001644239d05efc3f60409719b956996bdcb0c53`。
- 本地 `app-debug.apk`：`8e4a36819d5dca37429c6b6d8a63241c78b7b47f4fdb74420c979ddb53484925`。仅构建，不把此前实装截图当作这个新包的视觉证据。

## 尚未启用的接入

本轮曾将连续杀证明接入循环分类，已有原生/JNI 回归通过。但当前静着夹具的 4 半回合可逆候选检查没有产生完整循环正例，不能冒充“连续杀循环判负、恢复和悔棋”验收。

另外，`restore()` 会严格检查 v3 第 9 字节保存终局与当前规则重算值一致。新规则若把旧循环和棋改判胜负，旧存档可能被拒绝，且 Room 已结算成绩可能与新终局不一致。故本轮撤回新增判负调用，**`src/chinese_chess.cpp` 相对仓库基线无改动**，原裁定行为保持不变；随后重新构建并通过上述最终原生/JNI 证据。不是绕过校验，也不是宣称所有旧存档迁移已验证。

下一顺序：形成真实循环正例 → 明确规则版本与旧终局/已结算记录兼容方案 → 定向验证循环裁定、旧存档、恢复/悔棋、JNI 与业务存档 → 再启用生产分类。任意深度、职业棋例图谱及预算未知的产品裁判流程仍待开发。

国标麻将 App AndroidTest 两处无效导入仍在；用户指定原对话修复，统筹未跨文件改动，也没有过滤或豁免该测试。App 仪器、实际新包界面和实体设备不在本轮通过范围。没有清空 App 数据；AVD 已回系统首页，构建/设备窗口释放。六棋种统一节点未冻结，未提交或推送。

规则依据及取舍见 [实现设计](checking-mate-design.md)，引用的规则文本为 [第 24 条](https://cnchess.net/rules/ChapterSixSection24.html) 与 [第 26 条](https://cnchess.net/rules/ChapterSixSection26.html)。第三方许可与权重范围不变。
