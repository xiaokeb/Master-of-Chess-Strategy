# 老虎吃羊棋功能与验收清单

更新时间：2026-10-12。本文将正式规则纵切与产品全部功能分开跟踪，未附行为及设备证据的项目不得标为验收完成。

依据：[PRD](../../requirements/PRD.md)、[原始需求第 2.4.6/3.2 节](../../requirements/项目文档.md)、[页面交互](../../requirements/page-interactions.md)、[任务分级](../../development/task-levels.md)、[已确认规则决定](../../development/coordination-decisions-2026-09-30.md)。最新用户确认优先于旧状态中的待决问题。

## 正式规则与原生纵切

| 项目 | 通过条件 | 必要测试 |
| --- | --- | --- |
| B 型双山形棋盘 | 固定落点编号、坐标、逐边连线；图形为项目重绘 | 点位唯一、边无自环且双向；逐边快照；正交跳线只跨连续两条已印制边 |
| 开局与移去羊 | 2 虎、中央环 8 羊、14 待摆；虎先移去 1 只已在盘羊，再轮到羊摆放 | 初始计数；8 个合法移去动作；空点/虎点/第二次移去被拒绝；移去后 7 在盘+14 待摆 |
| 摆羊阶段 | 羊每回合摆 1 羊至空点，虎与之交替走步或跳吃；14 羊摆完后羊才可走步 | 占用点与越界拒绝；待摆计数单调减少；摆羊期间羊不能移动；虎仍可走/吃；最后一羊后转换 |
| 普通走步 | 当前方棋子沿一条印制边到空点；虎不得斜移，羊不得吃子 | 正交虎步、沿斜边羊步；非连线、跳步、占位、错方、越界；非法动作局面不变 |
| 单次跳吃 | 虎沿正交印制线跨相邻恰好 1 羊至下一空点，羊即时移除 | 4 方向、山形接点；空中点、虎中点、斜跳、跨多羊、未印制边、占用落点拒绝 |
| 自愿连跳 | 同一虎可继续正交跳吃且可转向；每次落地可结束回合；普通步不能接吃 | 连续与转向；结束后换手；继续时禁止另一虎/羊动作；无后续吃时正确结束 |
| 本链禁重访 | 当前连跳保存已到落点；本链不得再次落到旧点，新回合可以重访 | 起点及历次落点禁重访；捕获羊不能再次使用；新回合清空限制；悔棋与恢复一致 |
| 终局 | 剩余羊不足 8 则虎胜；两虎均无普通步及合法跳吃则羊胜 | 8/7 总剩余羊边界，剩余羊含待摆；开局 7 在盘不能误判虎胜；一虎可动/仅能吃不能误判羊胜；终局拒绝后续动作 |
| 羊方强制停着 | 羊待摆数0且无合法一步时唯一棋盘动作是停着，交虎行动，胜因保持上述两类 | 禁止有步主动停着、摆羊期间停着、虎方停着；合法虎步封锁羊后仍能推进；停着序列化/悔棋往返 |
| 撤销/重开 | 合法动作可撤销，恢复棋子、阶段、待摆、行棋方、结果及连跳链；重开回到正式开局 | 移去、摆羊、普通步、跳吃、结束链、终局逐项撤销；拒绝动作不增加历史；空历史撤销 |
| 版本化存档 | 保存完整局面与可撤销历史；恢复先完整校验，失败不破坏现有会话 | 所有阶段及连跳中间态往返；截断、尾随数据、未知版本、非法棋子/计数/阶段/链/历史拒绝；重建历史和结果 |

规则来源及取舍使用统筹已确认的 B 型几何和开局；后续走法采用确认的本项目规则，不额外加入来源玩法的虎重定位或多羊跳吃。棋盘图只作拓扑参照，不复制第三方素材。

## JNI、Kotlin 与可玩页面

| 项目 | 通过条件 | 必要证据 |
| --- | --- | --- |
| 原生句柄 | 不暴露指针；跨棋种句柄、无效句柄及释放后操作被拒绝；关闭幂等 | Registry 单测、JNI 设备测试；正常与异常生命周期 |
| 类型化动作/快照 | 独立表达移去羊、摆羊、普通步、跳吃与结束连跳；快照包含阶段、行棋方、待摆/被吃数量、活动虎、已访问点与结果 | Kotlin 协议编码/解码单测、错误码及边界数组测试；JNI 与原生动作一致 |
| 页面进入 | 首页兰州特色棋二级选择进入虎羊模式，返回路径明确；未验收模式不显示为可玩 | 导航测试；实际安装 APK 从首页进入及返回 |
| 棋盘交互 | 重绘 B 图，落点可选且命中稳定；合法点、活动虎、待摆计数、阶段提示清晰；连跳有继续/结束入口 | 摆羊、步行、转向连跳、主动结束、非法点击及终局完整操作录像或测试日志 |
| 局内操作 | 悔棋、重开、认输与提示按模式/难度约束；加载、思考、暂停、终局期间禁用冲突动作 | ViewModel 状态测试、Compose 操作测试；未实现动作不能提供虚假成功反馈 |
| 恢复/数据 | 活动对局跨页面、重建和杀进程恢复；连跳中间态准确恢复；存档失败能重试，损坏数据有稳定反馈 | Room/JVM 测试、实际杀进程恢复；终局统计和棋谱只能提交一次 |
| 视觉/适配 | 横屏棋盘、山顶、羊计数及按钮不裁切；系统条、大字体、深浅色、触控选择可用 | 模拟器 100%/150% 字体、深浅主题及目标尺寸截图并逐张检查 |

最小本地双人可玩纵切需要上述原生规则、JNI/Kotlin、正式页面及设备操作全部通过。规则代码、编译期断言或独立原生运行均不能替代这一验收。

2026-10-12原生增量写MOCT v2并兼容v1旧动作；新动作6需要统筹在Kotlin类型/解码/JNI及专属页面提供明确停着入口。原生字段形状不变。私有双ABI严格语法与x86_64链接通过，统筹在x86_64 AVD运行12组通过、退出0，详见 [修复与冻结记录](2026-10-12-native-turn-repair.md)；JNI或页面尚未据此通过。

## 产品后续完整功能

| PRD 功能 | 验收范围 | 当前判断 |
| --- | --- | --- |
| 四档人机与提示 | 虎/羊两方决策合法；按兰州特色棋任务实现 Minimax/Alpha-Beta 与评估；深度/时间/受控偏差分级；大师无悔棋和提示；棋力梯度及移动端耗时有独立评测 | 待实现及评测；不得用随机合法步冒充完成 |
| 自动演局 | 双 AI，暂停、0.5x–4x 调速、续局、后台降频、重开与状态恢复；指定开局保持不可变 | 待实现及设备验收 |
| 教程 | 独立规则讲解、分步练习、知识测试，完成后解锁简单；教程与正式动作一致 | 待内容与功能验收 |
| 残局 | 四级/主题/每日/随机入口、合法题目与索引、线性解锁、首次奖励防重复；内容来源与许可有记录 | 待实现；种子题量与最终内容量分别报告 |
| 棋谱/成长 | 捕获链含结束动作可步进回放、收藏/导出；分棋种统计、胜场/星级/积分、难度解锁；终局原子提交 | 待共享数据集成及验收 |
| 扩展训练 | 依据产品扩展范围逐项实现限时、连胜、盲棋、自由摆局、棋力评测与每日挑战，并记录本棋种适用规则 | 待设计/实现；不把入口或壳页计作完成 |
| 音效/性能/离线 | 资源项目自有或授权；飞行模式核心操作可用；实际设备性能、稳定性、音效及发布许可验证 | 未验收；模拟器不能代替实体设备 |

全棋种 9000+ 残局是总体交付目标，现有文档未给虎羊独立配额，不能自行宣称该棋种已满足内容总量。任何新第三方引擎、题库、权重和素材均先审来源与许可。

## 共享接口与窗口申请

2026-10-01 队列只分配虎羊专属拓扑/完整回合窗口。统筹持有 Registry/JNI/`NativeBindings`/`engine-api`/共享 CMake 编辑和构建窗口；虎羊尚无单独 JNI、导航、Room、Gradle 或 AVD 窗口。模拟器空闲不等于已授权独占。申请写入本棋种状态，由统筹串行排程。

| 共享精确路径 | 所需变化与兼容要求 |
| --- | --- |
| `engine-native/src/main/cpp/CMakeLists.txt` | 新正式运行时源码加入 `mocs_engine_core`；保留其他棋种条目 |
| `engine-native/src/main/cpp/tests/CMakeLists.txt` | 注册虎羊行为测试目标；原编译期测试继续可编译 |
| `engine-native/src/main/cpp/include/mocs/engine/native_engine_registry.hpp`、`engine-native/src/main/cpp/src/native_engine_registry.cpp` | 虎羊类型化创建/查找/释放；沿用正整数不暴露指针句柄，全局句柄唯一且跨棋种误用拒绝 |
| `engine-native/src/main/cpp/src/jni_bridge.cpp` | 增加正式动作、快照、结果、撤销和存档入口；负索引检查须先于无符号转换，数组/字节均复制；异常不穿越 JNI |
| `engine-native/src/main/kotlin/com/masterofchessstrategy/engine/internal/NativeBindings.kt` | 加虎羊入口；已有象棋方法签名和结果码保持兼容 |
| `engine-api/src/main/kotlin/com/masterofchessstrategy/engine/EngineTypes.kt` | `GameAction` 为 sealed，专属动作须由统筹在同模块同包增加文件；沿用 `GameType.TIGER_AND_GOAT=5`、现有难度/错误/胜负码，不重排 |
| `app/src/main/kotlin/com/masterofchessstrategy/navigation/AppNavigationModel.kt` | 兰州特色入口及二级选择、虎羊路由；当前该入口不可用，不能一次开放未完成的方棋 |
| `app/src/main/kotlin/com/masterofchessstrategy/ui/MasterOfChessStrategyApp.kt` | 真正 `NavHost` 宿主，连接模式与虎羊专属 Compose 页面/工厂；`MainActivity.kt` 当前仅装 Compose，预计无需改动 |
| `app/src/main/kotlin/com/masterofchessstrategy/navigation/AppNavigationViewModel.kt` | 上次虎羊模式/难度和快速进入分棋种保存；当前实现只加载象棋 |
| `app/src/main/kotlin/com/masterofchessstrategy/ui/HomeScreen.kt` | 二级选择入口标签及 UI 反馈；是否需要改动由新路由方案定稿后确认 |
| `app/src/main/kotlin/com/masterofchessstrategy/data/GameSessionRepository.kt` | 按棋种选择存档格式版本；当前加载/保存无条件使用象棋格式版本 2，不能直接作为虎羊兼容依据 |
| `app/src/main/kotlin/com/masterofchessstrategy/data/CompletedGameSessionRepository.kt` | 正式虎羊终局提交/棋谱与奖励原子性；当前残局完成逻辑依赖象棋题库 |
| `app/src/main/kotlin/com/masterofchessstrategy/data/LocalDataBackupCodec.kt`、`app/src/main/kotlin/com/masterofchessstrategy/data/LocalDataBackupRepository.kt` | 当前活动对局/棋谱校验限定象棋；加入虎羊格式校验与恢复，保留旧备份兼容 |
| `app/src/main/kotlin/com/masterofchessstrategy/data/ActiveGameEntity.kt`、`app/src/main/kotlin/com/masterofchessstrategy/data/MocsDatabase.kt` | 仅当现有存储字段不足时申请迁移；已有按 `gameTypeCode` 存储的字节字段，不能因新棋种无条件改 schema |
| `app/src/main/res/values/strings.xml` 及其他公共语言资源 | 通用模式/导航字符串走统筹；虎羊新资源尽量用独立 `tiger_goat.xml` 与名称前缀 |

专属新 Kotlin 页面、状态、测试放 `app/src/.../kotlin/com/masterofchessstrategy/games/tiger_goat/`。原生桥门面可沿用象棋的 bridge 接口注入、同步句柄所有权、关闭幂等、数组形状与返回码检查方式，但不复用象棋坐标矩形校验、棋子编码、红黑棋钟或 MOCX 存档。

## 旧原语 API 兼容风险

旧 `tiger_goat.hpp` 与 `tiger_goat_step_test.cpp` 保留普通步切片；新增完整状态机应使用独立类型或复用已有声明，避免同命名空间重复定义 `Point`、`Move`、`empty/tiger/goat`。旧 `Position` 不含待摆数、阶段、捕获链和历史，不能据其有效性判断完整正式局面或存档。`Topology::valid()` 只查节点数、坐标不重、对称及无自环，不查正式图边、连通或相邻点；正式拓扑须另有完整验证和逐边测试。`legal_steps()` 不列跳吃，不可据空集合判虎方失败。公开辅助函数 `tiger_moves_diagonally()` 本身不检查索引，调用方须先界限检查。

## 证据记录与关闭条件

审计所见旧节点证据只有 NDK 30 ARM64/x86_64 编译期断言；统筹另已将原语编译目标接入 CMake。新正式规则本轮行为测试结果由实施者写入本棋种状态及专属报告后更新，本清单不先行标通过。

每项验收记录实际命令、退出码/通过数、源码版本、ABI、APK/设备标识、操作场景与截图路径；失败记录修复和定向复测。大节点执行必要的跨语言构建、单测、Lint、Debug APK、AVD 操作与视觉检查，Gradle/NDK 集成和 AVD 由统筹串行安排。实体设备、最终题量、棋力梯度、音效性能、发布许可分别记录。达到阶段切片只能冻结该切片，完整产品清单和设备依赖全部核验后才可宣称本棋种功能及测试验收完毕。
