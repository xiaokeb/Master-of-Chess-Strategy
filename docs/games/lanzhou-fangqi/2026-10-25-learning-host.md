# 兰州方棋学习：保留宿主切片

本切片在已发布的学习存储节点之上新增 `FangqiLearningViewModel.kt`、`FangqiLearningRoute.kt`、`FangqiLearningReadyScreen.kt` 和专属VM测试。不修改存储、旧LearningScreen/Progress/Content、公共导航、工厂或资源；正式菜单、资格与设备验收仍由Root后续串行集成。

## 生产装配接口

`FangqiLearningViewModel.forDatabase(database, storage)` 使用同一MocsDatabase的 `fangqiLearningProgressDao()`、该DB对应Fq gate及一次捕获的Owner构造真实RoomStore；VM使用这个Owner的epoch，不在恢复失败或等待期间刷新。`storage` 必须是App持有的同DB `FangqiLocalStorage`，构造会核对gate身份。

Root导航应以NavBackStackEntry作为ViewModelStoreOwner，按 `fangqi-learning:<epoch>` 区分已退休代次，通过ViewModelProvider创建/保留VM，并在每次组合中向GameWriter registry注册该保留实例。Compose只调用 `FangqiLearningRoute(model, onBack)`，不得在DisposableEffect中关闭Native，不能为重新组合另造Store或会话。

主构造允许显式注入Store、固定gate/epoch、Native工厂、worker及lease函数，供定向替身测试；生产工厂不会使用空保存回调或绕过真实Store。

## 进度确认与练习

- 页面先实际load；坏记录给出错误并保留，不把读取失败变成空进度。课程确认只有Store事务成功返回后才显示已保存；等待期间禁重复提交，失败保留原意图并提供显式重试。
- 练习由VM持有真实Native与原始seed。每题从自编棋盘、红先创建，重开恢复该seed，不调用标准开局reset。配置重建应保留同一VM/Native/interaction；仅明确返回课程或整页退出、恢复退休、永久清理才关闭。
- 非终局步保留在VM内，不写学习表，也不占用普通本地活动棋局槽。红胜且无待吃子时，把完整历史交给真实Store/Native准入；Snapshot只触发候选提交，不能单独证明通关。保存失败封住同一历史，重试不再走子或改变证据。
- ReadyScreen的完成标记只读已load/commit的Snapshot；练习完成与课程确认分开。六课完成沿用存储契约的课程确认定义，没有成长奖励、排名资格或新四档入口。

未完成练习当前只在保留VM中存在，本切片不宣称杀进程后恢复练习中间局面。已确认学习进度的持久化/备份由已发布存储节点负责，接线后的实际设备联调仍另验。

## 退出与退休边界

返回练习列表与退出整页分别使用VM拥有的稳定Deferred请求；Compose等待者取消不取消保存/关闭。请求身份包含编号、退出目标和练习generation，不能复用上一次练习的关闭结果。不同退出目标串行完成各自操作；失败不会因重新组合自动重试。

Native关闭在worker执行。生产练习通过既有 `FangqiLocalStorage.acquire()` 借用同DB所有者链，跨配置重建继续持有；不调用idle autosave的load/enqueue、不保存或清理普通棋局。新会话先等前任实际关闭，成功才release；失败以原异常release，下一VM仍拒绝创建，不能重进绕过失败。等待lease时epoch变化则退休，不自动刷新学习Store的Owner。

`LocalDataWriterRetirement` 在Main同步封新操作，然后异步取消/等待在途加载、保存、创建及退出，关闭所有迟到Native并返回稳定completion；释放失败显示中文操作提示，并使completion异常。原始关闭异常保留在内部失败对象中，页面不展示SQL或Native异常原文。永久清理不伪造成功、不盲重试未知关闭结果，恢复方应等待registry清退结果再执行既有固定锁序。学习宿主不重入gate，也不擅自关闭App持有的idle writer。

以上中文提示为Root在设备视觉审查后补充的正式接线修正；下述r4哈希与私有测试仅对应修正前切片。最终候选的源身份与设备结果以正式学习UI验收记录为准。

## 精确验证

最终证据 `.build/fangqi-learning-host-20261025/r4/`：既有JDK17、缓存Kotlin/Compose 2.3.21、单CPU/512MiB，显式11份真实源私有编译成功，**19/19 JVM通过，0.271秒**，源码与只读friend产物前后SHA相等。

测试覆盖真实Store结果投影、保存前不完成、失败同意图/同历史重试、非终局不写进度、原seed重开、迟到课程点击拒绝、取消Compose等待者、不同generation/目标退出、退出失败不自动重试、显式返回及直接退休的关闭失败、epoch替换、永久清理、取消后迟到Native，以及同DB所有者排空/失败重进/旧epoch等待。Store与Native均是明确替身；实际复用的lease队列不读取或保存普通活动局。

只读friend依赖来自Root的 `.build/fqlp2/source/app/build/` 真实schema19 Kotlin产物，完整方棋class及MocsDatabase身份记录在 `friend-dependencies.json`；不是本切片重新生成KSP或Room实现。`compiler.args`、`junit.args`、classpath、日志、源清单及执行收据保留。JUnit日志SHA256为 `c332a12e981c19fa0c948d632e76e1cca79f40afd01c8354e14c8429840bf094`。

r1的14项通过、r2修正退出请求身份后的16项、r3加入Native lease后的19项均保留；r4补直接退休失败的具体错误显示及中性文案后重新编译/测试，不借旧结果冒充最终源。

| 新增源/测试 | 最终SHA256 |
| --- | --- |
| `FangqiLearningViewModel.kt` | `58f75d212ef5689e66c89668f42fdbebc12e857fe37639c95c46d840456600cc` |
| `FangqiLearningRoute.kt` | `47c07b7aacc9a22641b6ec1790b0456478360c4051b8b6567ede09b3eadea91f` |
| `FangqiLearningReadyScreen.kt` | `d744270378afce004323de2f48998462493230f2e44ac7f72c78d387dc840af2` |
| `FangqiLearningViewModelTest.kt` | `633766c35606bc3df5ec7a18631ca0c57778476b40ef7eb3830f7326550cb046` |

没有运行Gradle、NDK、ADB、真实SQLite/JNI、Activity重建、正式导航或视觉测试，没有Git操作。后续Root须把新路由/VM与registry接入正式菜单，执行实际加载/课程提交、三题触控通关、失败重试、配置重建、进出页面/恢复退休、普通/150%字体及共享备份联调；资格、音效、内容量和四档棋力继续分别验收。
