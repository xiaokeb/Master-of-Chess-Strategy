# 虎羊节点发布依赖审计

基线 HEAD：`0adc314f5577676c574fd159d56f3ff33d9d8935`。10 月 13 日混合工作区已通过[正式集成](2026-10-13-integration.md)，本页记录精确标签源码尚需处理的依赖，不把冻结清单当作发布闭包。

## 必需专属源码

- 原生 9 份规则/几何/状态机/异常测试、App 16 份门面/控制器/VM/RoomStore/UI/测试及专属文档。
- `engine-native/.../engine/games/tiger_goat/` 的 Session、Bindings、NativeSession 三份 Kotlin，门面 JVM 测试及实际 JNI 仪器测试。
- `engine-native/src/main/cpp/src/jni_tiger_goat_bridge.cpp`。

虎羊有独立 Bindings，不需要共享 NativeBindings 的围棋/麻将声明或 EngineTypes 的麻将注释改动。

## 共享片段

Registry 头/实现只取虎羊 include、强持有 Handle、独立 map、create/find/release、通用释放虎羊分支，并引入象棋 typed release 与 `jni_bridge.cpp` 的对应调用。否则 HEAD 象棋 JNI 仍用通用 release，传入虎羊句柄会误释放它。

主 CMake 只加虎羊 game.cpp 与 JNI；测试 CMake 取虎羊 game、exception、topology 和旧 step 编译目标。现有 `foreach(lanzhou_fangqi_step tiger_goat_step)` 必须按候选树依赖核对。Registry 测试中的其他棋种变量不能无条件复制到只有象棋/虎羊的树。

App 公共入口、备份、writer registry、字符串、debug 宿主仍按完整依赖准入，不整文件暂存：

| 文件/范围 | 当前工作区依赖 |
| --- | --- |
| LanzhouTraditionalModesScreen / App NavHost | 方棋本地与四档 AI 回调、路由及引擎 |
| HomeScreen | 虎羊真实导航测试用 HOME_LANZHOU_TRADITIONAL_TAG，未列入共享 16 清单 |
| LocalDataBackupCodec / Repository | 国标 MatchArchive、Room DAO/entity/schema、实际 Native validator |
| RoomCompetitionMahjongMatchStore | 国标赛事 checkpoint/config/ledger/policy/codec，NativeCompetitionMahjongSession |
| TigerGoatBackupCodecTest | 国标赛事模型和象棋 rule revision 测试资源 |
| AppNavigationModel/Test | 除虎羊外，还包含围棋/国标开放与路由断言 |
| debug Manifest | 还有两项未发布象棋 QA Activity，只取虎羊片段不能整份带入 |

主 CMake 含 KataGo/其余棋种，Registry 含围棋搜索/麻将/方棋，jni_bridge 含围棋方法，App Gradle 含 KataGo资产与麻将许可校验，App/字符串含其他棋种导航和许可。任何精确候选源码均须重新解析，不能据主工作区编译成功推定。

## 后续执行边界

优先比较两条完整方案：验证并发布方棋入口/国标存储备份前置节点，随后发布虎羊；或正式解耦菜单/备份注册，保留当前所有用户可见行为和数据兼容，再验证完整虎羊节点。不能删除备份、真实导航或保护测试来缩成更容易发布的 native-only 切片。

候选源码验证不使用 worktree：精确 index/tree 导出到仓库 `.build` 下源码快照，在串行窗口核对文件/许可、定向 JVM、双 ABI NDK、Lint/APK及必要模拟器视觉。不得搬迁/覆盖主工作区，不纳入其他对话正在编辑的文件。通过后才显式提交、附注 tag、推送/prerelease；APK附件必须来自标签源码，混合 APK 不能复用。

本页未宣称方棋/国标前置节点当前源码已通过 Release 门禁；准确下一顺序仍需核其最新冻结与收据。无须等待其他棋种全面完成，也无须用户重复授权已批准的普通开发/发布操作。
