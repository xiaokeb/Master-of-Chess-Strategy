# 国标课程接入独立确认与退役等待

本切片将现有四章29卡Controller、ViewModel和页面切换到独立MCLP章确认。Library、Proof/Codec/Rows/事务Store及国标Gate保持冻结；公共数据库、备份、App导航与正式赛事资格由统筹接入。本切片不宣称真实导航、数据库、视觉或设备验收已完成。

## 课程行为

Controller直接接收 `CompetitionMahjongLearningProofStore`，只从 `Ready.proof` 投影已保存章数。`Incompatible` 或读取失败继续显示读取失败，不补空成功、不生成完成事实。没有旧共享四步计数读取或转换路径。

逐卡阅读确认、正确物理牌选择、知识题检查与顺序推进保持原行为。只有全章逐卡通过、末卡已解决且玩家明确点击继续时，才创建一次请求UUID、章ID、完整有序卡ID及确认时间。创建请求前不生成章收据；保存失败保留末卡答案和原请求，重试不更新时间或换UUID。保存后确认丢失时，同请求再次确认，不重复记章。复习已保存章不创建新请求，不减少既有完成。

控制器还核对返回证明包含本次预期的章收据；错误或不完整返回不会推动UI完成。只有存储返回且固定Owner仍有效，才推进下一章或显示四章完成。请求、时间、课程内容和UI反馈均不作为防篡改认证，也不作为赛事胜场来源。

## 固定Owner与离页

生产ViewModel只接受 `GatedCompetitionMahjongLearningProofStore`，不在重试中重新取得Owner。它观察固定Store的epoch；Controller在接受输入、读取返回、确认返回及每次发布状态前再次检查Owner，避免仅依赖异步观察器。Owner失效后同步停收操作，隐藏完成入口并提示重新进入。

ViewModel实现现有 `LocalDataWriterRetirement`。Controller使用独立 `CompletableDeferred` 作为排空确认：退休同步停止接受新工作，已接受操作在独立于页面取消的 `NonCancellable` 工作段内结束，随后才完成确认。退休后的读写返回不能更新课程进度。`onCleared()` 同样开始退休，注册表可继续等待其确认，不能只依据ViewModel作用域取消就放行整库替换。

正式Route注册写者。返回或完成回调先开始退休，再等待排空确认；保存中返回也等待在途确认结束，不把离页当作事务完成。保存已失败且尚未离页时仍保留原“重试／留在教程／返回后重做本章”选择。所有既有题目和操作标签保留，新增退役状态标签 `competition_mahjong_learning_retired`。

## 统筹接线接口

正式入口为同模块内部函数：

```kotlin
CompetitionMahjongLearningRoute(
    store: GatedCompetitionMahjongLearningProofStore,
    writers: LocalDataWriterRegistry,
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
)
```

统筹在固定数据库及当前gate epoch对应的页面生命周期内创建Store，传入同数据库的写者注册表；旧页面不能通过重试捕获新Owner。Route的ViewModel key包含固定 `ownerEpoch`。导航完成回调只负责返回；正式资格入口仍需读取持久化证明，不能由回调赋值。

现有 `MasterOfChessStrategyApp.kt` 的旧Store import和构造需要由统筹改为新Store组合。共享 `CompetitionMahjongNavigationIntegrationTest.kt` 应改为读取新专属表，并将“旧四步完成直接视为完成”改为“旧行保留，新教材从零确认”。专属 `CompetitionMahjongLearningScreenTest.kt` 已改用明确章收据夹具；这只是页面测试注入，不是生产旧计数迁移。

## 删除的旧专门文件

- `learning/RoomCompetitionMahjongLearningStore.kt`：删除旧共享计数适配器，避免保留生产旧数据读取实现；其旧Progress/Store类型同时从Controller移除。
- `src/test/.../learning/RoomCompetitionMahjongLearningStoreTest.kt`：删除仅针对该适配器的6项旧共享行映射、时间转写和错误透传测试。旧行格式映射不再是当前课程契约。其必要边界由已冻结Proof模型16项、事务后端14项，以及本切片坏证明、准确重试、失败和Owner测试覆盖。原有逐卡、牌型、分值、读取失败、保存失败与取消行为测试全部保留并迁移到Proof。

## 独立验证与冻结

2026-10-31 缓存Kotlin2.3.21、JDK17和协程1.11.0下：

| 验证 | 结果 | 限定范围 |
| --- | --- | --- |
| Controller JVM | 19/19 | 原12项保留＋新增7项请求、确认丢失、坏证明、退休和回包Owner边界 |
| ViewModel JVM | 6/6 | 真实固定Gate配合内存证明Store，含工作线程、准确重试、epoch变化、清理和排空 |
| 定向Compose编译 | 退出0 | 生产Controller/VM/Screen/Route及专属ScreenTest；保留1项原有Compose测试规则弃用提示 |

证据位于 `.build/competition-mahjong-learning-host-20261031/r1/` 与 `compose-r2/`，含日志、结果及编译前后源码SHA256；各轮源码哈希一致。入口为同节点 `check.ps1`、`compose-check.ps1`。早期 `compose-r1` 仅有已失效缓存路径提示，脚本过滤后复编为 `compose-r2`；未改正式源码规避编译问题。

未运行共享Gradle、ADB或Git。真实数据库23、备份/恢复、正式导航、普通和150%视觉仍由统筹串行验收。自有文件到此冻结，供后续完整课程闭包纳入。
