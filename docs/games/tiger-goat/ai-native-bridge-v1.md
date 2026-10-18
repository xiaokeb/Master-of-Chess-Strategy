# 虎羊 AI 不可变快照桥 v1

日期：2026-10-17。该切片只准备安全的原生调用，不开放正式 App AI 入口，不改变 MOCT、现有 81 字段快照或 Registry 棋种句柄。搜索算法与原生行为验证见 [原生 AI 切片](2026-10-17-native-ai.md)；本文不能替代新的 Kotlin/JNI 集成验证。

## 文件与职责

本专属窗口仅新增以下文件，既有规则、Session、App、公共状态及构建文件均不改：

- `engine-native/src/main/kotlin/com/masterofchessstrategy/engine/games/tiger_goat/TigerGoatSearchRequest.kt`：参数、停止码、结果、请求与可注入桥接口。
- 同目录 `TigerGoatSearchBindings.kt`：独立生产 JNI object。
- `engine-native/src/test/kotlin/com/masterofchessstrategy/engine/games/tiger_goat/TigerGoatSearchRequestTest.kt`：14 个宿主测试方法。
- `engine-native/src/androidTest/kotlin/com/masterofchessstrategy/engine/games/tiger_goat/NativeTigerGoatSearchInstrumentedTest.kt`：8 个真实 JNI 仪器测试方法。
- 本文。

Root 串行负责 `engine-native/src/main/cpp/src/jni_tiger_goat_search_bridge.cpp`、主/测试 CMake 与正式编译、设备运行；本窗口未改这些共享文件。新接口属于既有 `engine-native` 虎羊专属包，不新增 `engine-api` 全局动作或注册表搜索句柄。

## 调用与所有权

```kotlin
val request = TigerGoatSearchRequest.prepare(moctBytes, limits)
// 在调用方持有、可等待完成的 worker Job 中调用，不在主线程执行。
val result = request.executeBlocking()
// 从另一线程或生命周期事件发出取消，不会释放仍在运行的 JNI。
request.cancel()
```

`prepare` 先限制长度 53–196658，再防御复制，检查 `MOCT`、存档版本 1/2、拓扑版本 1，以及 `53 + actionCount×3` 的形状。它不校验棋子、checksum、阶段或历史合法性；真正的 Native 完整重放才是准入。请求不借用 `NativeTigerGoatSession` 句柄，也不持有该 Session 的同步监视器。准备及运行前取消不加载 JNI 库。

`executeBlocking` 用 CAS 只能消费一次，包括运行前取消或桥抛异常；失败重试须由上层明确创建新请求。源数据、传桥数组、结果中的源数组相互防御复制。生产桥只读取取消位，不得将其重置。

`cancel` 可在运行前、运行中和返回后调用，幂等且不等待 JNI。`TigerGoatSearchResult` 是普通 class，不定义值相等；其 `stop/action/score` 会观察请求取消位，取消后呈 `CANCELLED/null/null`，已完成深度、节点与耗时仍保留诊断。

已经被外部取走的动作无法撤销。真正落子前，后续 owner 仍须检查当前请求、取消位、sessionId、generation、数据 epoch、模式/难度/执方和当前 MOCT 字节，随后复核合法动作；本切片不自动提供这些应用层防过期门禁，也不授权根据结果直接落子。

## JNI 签名

```text
TigerGoatSearchBindings.search(
  snapshot: ByteArray, turnDepth: Int, nodeLimit: Long,
  timeLimitMillis: Long, cancelled: java.util.concurrent.atomic.AtomicBoolean
): LongArray

descriptor: ([BIJJLjava/util/concurrent/atomic/AtomicBoolean;)[J
symbol: Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatSearchBindings_search
```

默认限制是 3 次换手、10000 节点、1500 ms；深度允许 1–16，节点数允许 0–`Long.MAX_VALUE`，时间允许 0–60000 ms。这些是预算，不是已校准的四档或硬实时保证。

JNI 在自己的同步调用栈复制并重放 MOCT，再运行私有搜索；没有新原生线程或持久原生请求指针。它通过 `AtomicBoolean.get()Z` 公开方法轮询，`JNIEnv*` 和局部引用仅供当前线程/当前调用使用。不得读取私有字段、传递裸地址或跨线程保留该 `JNIEnv*`。每次取消回调都轮询，性能与取消延迟仍需实测，不能先宣称达标。

调用、分配或 Java 回调产生 pending 异常时应保留原异常；回调仅返回停止信号让 C++ 安全退回，JNI 出口不能把异常转成普通取消成功，也不能覆盖它。参数/长度非法及未取消时重放失败抛 `IllegalArgumentException`。正确长度且已经取消的 raw JNI 请求可直接报告取消、不执行重放；这不是该输入已通过准入。

## 11 个 Long 的结果帧

| 下标 | 内容 | 约束 |
| --- | --- | --- |
| 0 | 协议版本 | 固定 1，长度固定 11 |
| 1 | stop | 下表显式码，不使用 Kotlin/C++ ordinal |
| 2 | hasAction | 仅 0/1 |
| 3–5 | kind/from/to | 无动作全部 -1；有动作按既有动作表和点号校验，禁止 RESIGN |
| 6 | hasScore | 仅 0/1 |
| 7 | score | 无分值固定 0；有分值为 -100000–100000，原始执方视角，不是胜率 |
| 8 | completedTurnDepth | 0–本次目标深度；只记录整轮根搜索 |
| 9 | visitedNodes | 0–本次 nodeLimit |
| 10 | elapsedMillis | 非负；Native 入口到结果组装的单调时钟耗时，包含复制/恢复及搜索 |

| stop 码 | Kotlin 名称 | 动作与分值 |
| --- | --- | --- |
| 0 | DEPTH_COMPLETED | 必须有动作和分值，完成深度必须等于目标 |
| 1 | NO_LEGAL_ACTION | 不提供动作/分值 |
| 2 | NODE_LIMIT | 可无动作；可有未评分合法回退（深度0），或上一完整迭代结果 |
| 3 | TIME_LIMIT | 同节点预算；不把未完成根搜索前缀当完整结果 |
| 4 | CANCELLED | 不提供动作/分值；取消优先于完成竞态 |
| 5 | INVALID_LIMITS | 不提供动作/分值；正常 public 参数已在调用前校验 |
| 6 | RULE_REJECTED | 不提供动作/分值，不假造停着或胜负 |

失败状态允许保留已有深度/节点诊断，但不能泄露可用候选；有分值必须同时有动作且深度大于0。Kotlin 先查 Long 边界再转 Int，拒绝未知码、尾随字段、非法存在位、溢出点号、缺失 payload 或语义冲突。取消后动态清除候选不能替代这些 wire 校验。

总耗时包含恢复，但不包括请求排队、Java 侧桥对象/库初次加载和上层显示耗时。Native 在入口计时、恢复后扣剩余预算；现有恢复过程没有中途取消 hook，单次恢复/分配可能超预算，仍属合作式边界。移动端完整单步 ≤2 秒及真实取消延迟须另行测量；`elapsedMillis >= 0` 的测试不能证明性能达标。

## 生命周期与生产前置

调用方必须持有并等待实际执行 JNI 的 worker Job；取消协程本身不等于取消同步 Native。退出、暂停、重开、恢复备份应先停止新生产者并推进 generation，再设置取消位，等待搜索及存档写者完成，最后关闭相关会话/进入替换事务。`cancel()` 本身不提供 `join` 或备份排空承诺。

优先使用结构化的 `viewModelScope` 子任务与现有 writer barrier；若另开 cleanup scope/executor，就必须另登记其关闭等待，不能让 `LocalDataWriterRegistry` 把仍运行的 JNI 当已清退。不得在主线程阻塞等待搜索。

现有 `RoomTigerGoatSessionStore`、`TigerGoatActiveArchive` 及备份 adapter 只承认本地双人 `mode=0/difficulty=null`。正式 AI 仍缺模式配置、配额/来源持久化、代次门闩、成长与备份准入，不能靠本桥直接开放入口或覆盖本地双人来源。连跳应逐动作保存，以实际换手继续调度，不把每次跳吃当成一个玩家回合。

## 测试范围与当前状态

14 个宿主方法覆盖参数/存档形状、所有允许动作、停止码、Long 溢出和帧语义、预算回退、四处字节所有权、运行前/中/后取消、单次执行并发竞争、桥异常传播及结果防御。假桥不能证明 Native 准入。

8 个仪器方法使用真实生产 JNI，覆盖开局/摆羊/连跳、羊强制停着及双胜终局、零预算、非法参数、坏 checksum 与正确 checksum 的非法动作重放、raw 预取消及关闭原会话后的私有快照搜索。并发测试的 latch 只证明桥已分派，不宣称精确观察到某次 Native 内部轮询。

本专属窗口截至源码交接**未运行编译、JVM、JNI、设备、Gradle 或 Git**。上面是测试源码范围，不是 14/14 或 8/8 通过结论。由 Root 在串行窗口实际编译并运行后记录包/源码身份与结果；旧原生 10 组通过不能替代本次新桥验收。四档参数、相邻约7:3、自动演局、App 页面、视觉/音效、存储和实体设备均不据此宣告完成。
