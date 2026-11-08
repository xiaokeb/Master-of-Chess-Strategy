# 国标赛事页面可等待退休

专属 `match/play` 前置实现，尚未接正式Room宿主或公共导航。本次只修改赛事ViewModel、Route及其专属测试；未改规则、辅助额度、Controller、数据库、JNI或构建脚本。

## 生命周期

ViewModel实现 `LocalDataWriterRetirement`。同步入场门禁先封闭新输入；已接受的任务由独立scope保留，不能在ViewModel被清除时丢失在队列中。保存、读档及排队动作依原事务mutex串行处理；退休阻止后续AI批次及新提示，已接受的提示返回后不扣费或发布。任务数归零后，唯一关闭任务等待同一mutex并执行Controller.close，关闭结束后取消独立scope。

`retirementCompletion`固定且重复调用相同。Native close失败直接保留为该收据的异常，页面停留，注册表可继续拒绝替换；不重试关闭、不吞掉失败。Controller.close本身直接调用session.close且传播异常。本切片不将失败收据声称为资源释放成功。

发布状态与退休使用同一门禁，退休后不得晚回包重新开放界面；重复退休也不覆盖关闭失败提示。`onCleared()`复用同一退休流程，不另起无收据关闭任务。

## 正式宿主需要提供的契约

Route改为模块内入口，接收明确 `ownerKey`、`ownerIsCurrent` 和 `LocalDataWriterRegistry`。缺任何一项时不会创建赛事。ViewModel的checker默认null并拒绝入场，不提供生产默认true。每次load/save前后、AI/提示回包和发布前检查同一checker；checker不能在重试时重新acquire或更新为新Owner。

Root正式宿主必须从同一数据库的固定lease/epoch构造key和checker，提供同数据库写者注册表；拥有期变化时应调用退休/宿主屏障。VMkey包含viewer及固定ownerKey，Route持有原checker；不能把更换checker而不更换ownerKey作为续用方式。本模块不自行申请或重新取得FormalRoom归属。

Route在factory与SideEffect注册写者，所有实际返回回调先同步退休、等待稳定收据，再导航。已有保存失败时的“重试/仍然离开”交互保留，显式仍然离开也经过退休屏障。失败不会调用返回回调。

## 验证

私有 `.build/competition-play-retirement-20261101/r7/`：原45项VM行为测试保留，加6项退休测试，共51/51。新增覆盖在途保存、清理前尚未执行的已接任务、过期Owner晚回包、close失败重复await、缺Owner不创建session及在途提示不扣费。这里使用内存Store和SessionDouble，不是真实SQLite或Native设备验收。

`compose-r2/` 对最终VM/Route及实际依赖的Kotlin Compose定向编译退出0。两个最终收据均保存输入SHA且验证执行前后相同；入口 `.build/competition-play-retirement-20261101-check.ps1`。早期r1/r2因私有编译闭包不含内部Checkpoint/hint源码而失败，保留日志；补真实依赖后通过，未改生产可见性绕开错误。中间r3–r6保留，不代替最终r7。

专属Hint/Playback/Content三份Android测试仅显式注入测试Owner，不代表生产固定Owner来源；尚未运行设备。Root另需适配共享 `CompetitionMahjongRoomV2InstrumentedTest` 的VM构造，以及 `CompetitionMahjongRoomVisualTest` 的Route调用（测试Owner/key与UUID数据库注册表），本对话未修改这两份共享文件。

真实Room宿主、代次变更、注册表恢复屏障、Native关闭与Route返回的设备/视觉检查仍由Root串行集成；当前不宣称正式赛事入口或完整国标功能完成。
