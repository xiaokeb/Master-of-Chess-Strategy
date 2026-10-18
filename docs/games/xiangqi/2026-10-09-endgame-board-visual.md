# 新八关棋盘触控与字体视觉核验

日期：2026-10-09。统筹象棋专属窗口，Pixel_7_Pro x86_64、NDK30、既有 JDK17/离线 Gradle。没有清空或卸载应用，测试只清理自行创建的 UUID 数据库及权重副本。

## 实现与问题修正

`XiangqiEndgameGameFlowInstrumentedTest` 新增真实 MainActivity 横屏宿主及生产对局 Screen，使用真实 ViewModel、JNI、已核验 NNUE 的 Pikafish 和 Room。根据生产棋盘几何逐次触控起点、落点，等待实际 AI 应答及终局事务，不用直接调用落子冒充触控。该宿主不是完整导航链；目录解锁回调沿用此前独立证据。

首轮截图发现切换到另一合法残局时误报旧存档损坏。生产 `restoreSavedSession` 对模式/难度/变体不匹配的合法检查点，仍执行既有清理和初态保存，但改用 `GAME_RESTARTED` 提示；真正格式不兼容及原生恢复拒绝仍用 `RESTORE_REJECTED`。没有修改保存格式、规则裁定或奖励事务。新增两个 JVM 用例验证合法切关/切模式，并保留原四项恢复与写回用例。

最初新增 JVM 夹具只实现规则接口，不能用于要求 AI 引擎的残局，实际失败为 `ENGINE_UNAVAILABLE`，不能当作问题的有效红测。修正夹具为 AI 接口且禁止不需要的 AI 调用后，六项正式测试全部通过；原失败日志保留。问题依据是实际截图及生产分支，不隐去无效夹具。

共享单类仪器脚本增加可选 `TestMethod`，只允许预期1项，以减少修后与大字体重复测试；保留包安装、不清空数据及完整成功收据门禁。

## 实际结果

- 新八关：`xq-easy-017`、`018`，`xq-medium-008` 至 `013`。分别验证初态、选子/落点、实际应答、原生红胜、目标步数和 Room 最优步数；每关初态/终局截图。
- 首轮普通字体整类3/3：新增触控，以及既有解锁回调、失败重试/重建幂等两项。首轮大字体仅新增方法1/1。它们早于提示修正，不混入最终包结果。
- 修后普通与150%分别只复验新增方法，各1/1；每次实际完成八关。最终32张截图全部查看：标题、棋盘及棋子、红胜文字、步数目标清晰，150%目标可换行显示。右侧是可滚动操作列，滚动到目标时上方说明离开视口，不称所有控件同时可见。
- 正式 JVM `ChineseChessGamePersistenceTest` 6/6，无失败/跳过；XML时间 `2026-10-09T12:03:15.807Z`，副本 `.build/test-evidence/2026-10-09-xiangqi-endgame-visual/`。
- 最终 AndroidTest APK、Lint 构建成功57秒，Lint0错误/12提醒。Main APK在前一命令已构建；不以失败的合并 JVM 命令宣称全部成功。

| 修后仪器收据（`.build/instrumented-class/`） | SHA-256 |
| --- | --- |
| `3cd6053ccccb4fb99df69af5566c77de/instrumentation.log`，普通1/1 | `b5bab7c096634a0de56583a7d690bc6bf6af66bccdfd1af6996aac1bf450e073` |
| `813c67e8686f47558093174e130e96bf/instrumentation.log`，150%1/1 | `a18e637c0de58982c7f050c1aaf446b59984e279a88ef861e4e32d7674e2fdaf` |

最终截图：`.build/xiangqi-endgame-visual-20261009/normal-final/` 与 `large-final/`，每目录16张。旧 `normal/large` 保留为修前证据。构建日志同根 `switch-feedback-{red,fixed-build,final-build}.log`，其中前两次失败不能删除或计通过。

## 固定身份与边界

- Main APK：`574a71ac0ff38bc7a4aeb13f5030a52f1c40de1be0e6a0b89f2144db5aeccc67`；Test APK：`bde0a967246ee9d36bbbacc368300d94b88642c586605e747d0a45d588e1023a`。
- ViewModel源码：`77fd5b1def1157544af28c92902a0cb0d075a915ece4806c0a2d2e975c5d4e2b`；存档JVM测试：`3bc64b0c3149b94f608572d14df33b828c73ee7484eedf181c43a506715e2e5d`；仪器源码：`6e03891d20c5593bc196d67b03d5491b3311caf95792f2c63621262af9a3e7e7`。
- 系统字体恢复1.0、旋转保留原值（自动1、用户0），两 APK 保留安装。没有实体设备验收。本次模拟器以无音频启动，不能称实际试听。
- 新八关棋盘/目标/胜负视觉已补齐，资源35关与保守完整验收27关继续分开：新题实际难度、音效试听及完整目录奖励提示仍待验；不宣告9000+内容量或AI梯度完成。
- 没有修改第三方许可、生产AI参数或历史基准。六棋种未统一冻结，无暂存、提交或推送。
