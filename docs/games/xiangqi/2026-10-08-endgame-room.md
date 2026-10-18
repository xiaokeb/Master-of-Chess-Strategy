# 象棋35关：真实结算与游戏链路

日期：2026-10-08。统筹/象棋串行使用 NDK、Gradle 和 Pixel_7_Pro（x86_64）。正式题库资源及 APK 内副本 SHA-256 均为 `1551ce2a61002326edf15b17f5c3ab1c3e1740b03aedd15148749f0630db6f95`。

## 实现

`games/xiangqi/XiangqiEndgameCompletionValidator.kt` 在独立 Native 句柄恢复通关历史，校验棋种、难度、真实红胜和精确历史步数；逐步撤销后必须回到指定关卡的棋盘及行棋方。`data/CompletedGameSessionRepository.kt` 仅在象棋残局红胜发奖前、原有 Room 事务内调用该验证器。校验失败整体回滚，不改变数据库结构或其他棋种契约。

国标麻将 `CompetitionMahjongPresenter.kt` 的 pendingSeat/pendingTile 局部缓存已在复编前存在；用户授权后统筹只读确认并采用，未重复修改其文件或麻将逻辑。

## 实际验证

| 范围 | 本轮结果 | 观察内容 |
| --- | --- | --- |
| 正式 JVM | 18/18 | 题库9、目录ViewModel4、奖励仓库5；XML 无失败、错误或跳过 |
| 真实 Native/Room | 5/5 | 新八关首次奖励、重开幂等、旧版本记录兼容、逐关解锁、事务失败回滚、非法步数/已知关卡错配/非原生胜局拒绝 |
| APK 题库 JNI | 3/3 | 全35关主变化、13道两步题全应手与唯一首着、17道唯一一步题 |
| 正式游戏 ViewModel/Pikafish/Room | 2/2 | 新八关实际选子走子、真实 AI 应答、终局回调刷新目录；失败不发奖，公开重试后只发一次，重建 ViewModel 不重复奖励 |
| 构建 | 通过 | 双 ABI Debug APK、AndroidTest APK、许可/内容/NNUE/音效文件门禁 |
| Lint | 0错误、11提醒 | 他棋种专属代码/资源及依赖升级提醒；未代改或升级依赖 |

设备测试使用 UUID 专属数据库；实时游戏另用 UUID 缓存目录复制并核验现有 NNUE，全部句柄关闭后只删除本夹具数据库、核验文件及空目录。不打开生产数据库、清空应用数据或卸载 APK。旧23关前置记录来自真实 Native 主变化及正式事务，明确是前置夹具；新八关经实际游戏走子及 AI 完成后共31条进度，旧困难/大师四关不冒充本轮重玩。

首轮实时链路为1通过、1失败：测试误要求第13关实际 AI 应手后的杀着唯一，实际有4着。正式来源清单只规定唯一首着、所有应手仍可胜，不规定后续每步唯一；核对既有全应手测试及来源清单后改为必须存在合法胜着，选一着由正式游戏执行。保留失败日志，未修改资源、AI或生产规则，修后两项均通过。

## 收据与快照

本地证据根 `.build/xiangqi-endgame-room-20261008/`：`gradle-final.log`、`game-flow-build-final.log`、源码前后清单；五项原快照哈希一致。XML/Lint 副本在 `.build/test-evidence/2026-10-08-xiangqi-endgame-room/`，JVM 时间为 `2026-10-08T12:10:59Z`。初次围棋新测试未识别和本测试 SQL 数组类型编译错误日志均保留；SQL显式类型修后最终构建通过，围棋测试只参与编译、不计运行通过。

| 仪器日志（`.build/instrumented-class/`） | 数量 | 日志 SHA-256 |
| --- | --- | --- |
| `6e3be11927eb452694784e6be89e255e/instrumentation.log` | 5 | `f714fd2a64fdf66ab14c0e0d3b67475ddd3127c45366dd7c5920395da3d556c9` |
| `3c0e29796b0347cf853aec8c4d74c44c/instrumentation.log` | 3 | `4395ca183d3e02dd49b00752915a94d6a3c441cdb1825fbb552b4a11e480d512` |
| `c988aa2a857d4246968ff27074346b6d/instrumentation.log` | 2 | `f4ca8249b083ee43ddd873a5c9a60017ff66971cbcb2260efd769fec7736b23e` |

失败实时链路日志：`a15cf32467404233a37eb835ef169a17/instrumentation.log`。三类分别用 `tools/android/test-instrumented-class.ps1` 更新安装并直接单类运行，无跳过。不是一次10项合并运行。

- Main APK：`a2685103055c987f21323d99e0a47bbce6db7813a83a34fa75d4dee05813ab76`。
- 前两类 Test APK：`58431a5c3f1715caee9e8273961d02de2e4248b6168e8b6eb1e0001f7eb50658`。
- 修后实时链路 Test APK：`3b3aed8647aec0b52f98b159eb54a92620a1e6f7b76e88f86c2d5e046cff9362`；只新增/修正专属测试，主 APK 不变。
- 验证器：`46cf69bced1d16eab639a4b0cc55d71ca483b9aad8a423d841c19a48ab40c48a`。
- 事务仓库：`7485f84bf29c112310b653cd88287798efbd942cf673da85eb352c2ecbe4230b`。
- 实时链路测试：`21843baa5afe1a3c2802417344ced006c8f1eac6f475b2cf6c1329205735eacf`。

## 正常入口与视觉边界

同一 Main APK 的首页→中国象棋→残局目录在普通/系统150%字体下实际查看：35关总量、筛选与摘要可读；中等列表滚动到第13关，标题、奖励和锁定说明完整。点击其禁用卡片仍在目录（UI节点 `enabled=false`），未注入生产进度或绕过锁定。四张 PNG 在证据根：`normal-catalog.png`、`large-catalog.png`、`normal-medium.png`、`large-medium.png`，均已打开查看，不是仅截屏未检查。

本轮视觉只覆盖正常入口及目录，不把它冒充新增八关棋盘/通关反馈的大字视觉。实时游戏链路是隔离存储的 ViewModel 输入，不是完整导航自动点击验收。数据库重开/模型重建不是 SIGKILL；音效事件或文件门禁不是实际试听；AI实际应答不是四档棋力验收。完整保守交付量仍27，35关规则/结算节点通过，剩余棋盘/反馈视觉、试听及实际难度继续跟踪。

字体恢复 `1.0`；旋转保持原值 `user_rotation=0`、`accelerometer_rotation=1`；实际窗口焦点为 NexusLauncher，两 APK 仍安装。无其他游戏代码、许可或权重变更。六棋种未统一冻结，本轮不暂存、提交或推送。
