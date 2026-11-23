# 虎羊正式人机会话基础

依据 `docs/development/implementation-plan-2026-11-10.md`、2026-09-30 集中规则决定，以及已发布 `7123ffd` 本地存储节点推进。此次新增可注入的正式人机会话 owner，保留已发布本地双人实现与现有 MOCT。未开放首页入口，未改公共文件、Room、备份、JNI 或规则。

## 独立新增内容

源码位于 `app/src/main/kotlin/com/masterofchessstrategy/games/tiger_goat/ai/`：

| 文件 | 行为 |
| --- | --- |
| `TigerGoatFormalCheckpoint.kt` | v1 正式模式检查点，保存 sessionId、revision、人类执方、难度、动作数、时间和完整 MOCT 的私有副本。v1 只接受简单标准对局，不开放其他档位。 |
| `TigerGoatFormalSessionStore.kt` | 正式存储小接口；区分缺失、旧本地双人、损坏与正式续局；固定 Owner 包装复用现有 TigerGoatStorageEpochGate，重试不更新 Owner。 |
| `TigerGoatFormalSearch.kt` | 可注入搜索小接口，可独立编译 owner 与脚本用例。 |
| `CoordinatedTigerGoatFormalSearch.kt` | 真实协调器适配切片，消费必须在当前检查点上即时核验，依赖前一切片及尚未发布的 App 请求协调器。 |
| `TigerGoatFormalGameOwner.kt` | 串行管理载入、人类点击、合法 AI 应答、连跳、保存重试、悔棋、恢复、重开和页面退役。 |

`app/src/test/kotlin/com/masterofchessstrategy/games/tiger_goat/ai/TigerGoatFormalGameOwnerTest.kt` 提供 12 个 JVM 用例源码，使用脚本规则与搜索替身验证 owner；它们不检验第二套规则实现。

本节点同时冻结 owner、App 搜索协调器和真实 JNI 适配。owner 的脚本测试可以脱离搜索协调器验证。正式存储和页面接入继续按下文依赖推进。

## 行为边界

- `isQualified(config)` 必须由可信独立虎羊教程记录提供。未满足资格时不保存、不搜索。此次没有写入任何虚构课程完成记录，也未将其他棋种资格复用为虎羊资格。
- 新局先持久化初始状态，再允许搜索。每个接受的 AI 动作经 Native 活局合法动作表与 apply 双重确认，先保存再发下一请求。虎仍处于连跳时继续搜索；END_CHAIN 或 Native 换手后才开放人类操作。
- 搜索身份复用 sessionId、revision、完整 MOCT 和请求私有代次，updatedAt 不参与身份比较。悔棋、重开、恢复与退役使旧结果失效。一次悔棋回退 AI 整段应答，并回退至人类回合起点；人类已执行的整条连跳及 END_CHAIN 一并撤回。尚在人类连跳中时也回到该回合起点。没有历史可退时，以 Native 初态为准。
- 保存失败保留同一不可变检查点，锁住双方后续动作；重试保存不会改变版本。保存成功后才恢复搜索。AI 失败可重试或悔棋，非法建议不改变活局。
- 旧本地双人记录返回 `LocalGame`，由宿主导向原入口，不在载入时隐式转为人机或覆盖。损坏记录必须经过宿主明确重开确认。
- 同一控制线程（Android Main）调用操作与 `advanceAi()`。离页或存储 epoch 改变先同步 `beginRetirement()` 封口；宿主随后等待 `retire()` 完成再释放页面 writer。清理以 NonCancellable 排空串行保存与搜索 worker，join 成功后关闭活局 Native。失败保留错误，不能宣称安全离页。

现有 `TigerGoatGameScreen` 对 busy 会统一锁住部分按钮；正式宿主接线时须单独安排“AI 思考中悔棋”和重试操作，避免只接棋盘而隐藏 owner 能力。

## 交统筹串行集成

1. **存储与备份契约**：实现正式检查点的 Room adapter，原子保存模式元数据及原始 MOCT。不能把现有 `RoomTigerGoatSessionStore` 的 mode=0 包装后直接作为正式记录，也不能将新包络直接传给 Native。决定复用活动行的 mode/difficulty/variant 元数据，或单独正式存储后，同步备份 admission、白名单、回放校验、导入事务与跨模式提示。此次没有改公共 schema 或 adapter。
2. **可信资格**：接入虎羊独立课程完成证据；校验依据须能经重启与备份恢复。对外难度仍待校准，Native 默认的深度/节点/时间只是预算，不构成简单棋力验收。
3. **页面生命周期**：宿主持有一个固定 store owner 和一个会话 owner，在 Main 载入并消费 `uiState`，活动页轮询 `advanceAi()`；观察数据代次，及时封口。离页、VM 清理和备份替换都等待 retirement，不能仅取消轮询协程。
4. **导航**：在可信资格通过后提供简单标准新局/续局。已有本地记录应可回原本地入口。存盘失败离页、损坏重开及替换其他模式活动记录需宿主明确提示。
5. **精确验证**：先编译、运行专属 JVM 用例，再检查真实 JNI 开局移羊→摆羊→AI 回应、虎连跳/停着、保存失败恢复、杀进程续局、退役排空。随后执行受影响仪器测试和默认/大字号视觉检查。

## 依赖与验收状态

统筹冻结后，owner 12 项和搜索协调器 9 项 JVM 测试通过。Pixel 7 Pro 以真实 JNI 验证虎方移羊与 AI 摆羊，使用测试内存存储保留原始 MOCT。销毁会话并新建 owner 后恢复同一记录，悔棋回到开局且保持同一对局 ID。双 ABI、Debug 主包、仪器包和 Release 主清单构建通过。本节点尚无正式人机页面可做视觉验收。[证据清单](../../releases/manifests/v0.1.0-tiger-goat-formal-owner.1.json)

此次未引入第三方代码、权重、资源或依赖。高档资格、提示、自动演局、可信终局成长、棋谱消费、音效以及内容量按原目标继续，未纳入此次完成范围。
