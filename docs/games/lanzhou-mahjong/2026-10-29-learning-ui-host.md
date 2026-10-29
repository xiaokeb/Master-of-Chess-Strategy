# 兰州麻将十课学习宿主接入

本切片解冻原有自编十课草稿，内容版本改为2。炸胡课明确项目新局桌规：每家24倍底分、合计72倍，不叠加自摸或点炮倍率，已发生杠分保留；旧v1局仍按每家8倍。沿用项目已确认决定，不宣称地方官方规则，无新增外部文字、图片或依赖。

正式Controller/ViewModel只接`LanzhouMahjongLearningRepository`，不接旧通用教程表的`RoomLanzhouMahjongLearningStore`。旧Progress/Store类型仅为保留该未发布草稿的源码兼容，本节点不能把旧Store及其DAO替身测试计作独立十课存储验收。

- 十课按顺序解锁。答错不写，已掌握课程复习不写，只有Repository返回完全相同的after快照才增加已掌握课数。
- 答对时只生成一次Advance与时间戳。普通失败冻结课程选择和答案，显示“重试保存本题”；重试复用同一请求。确认丢失后可由仓库幂等确认，界面不提前掌握。
- CAS冲突保留原记录，只提供明确“重新读取教程进度”入口；重新读取成功后采用仓库快照。读取失败不生成空记录、不覆盖原记录，不提供重置入口。
- 每个Controller固定ownerEpoch。VM主动监听epoch；操作入场与退役同步互斥。退役同步拒绝新操作，等待已接受的非取消后台操作结束，拒绝晚到的界面掌握更新。ViewModel清理亦触发同一稳定retirementCompletion。
- 默认IO调度器承载读取与推进。内部lastFailure保留原始异常；页面只显示固定中文反馈。菜单、资格、成长奖励和答题后开局均不在本切片范围。

页面保留纵向滚动与横向十课目录，顶部改为可换行纵排。保存失败/冲突/读取失败均有明确操作。纯Screen增加150%字号与240dp宽度测试场景。

## 验证与交接

使用现有JDK17、Kotlin2.3.21与Compose编译插件及真实缓存类。命令：`& .build/lzlearning-ui-20261029/r1/verify.ps1`。最终编译退出0，无编译警告；限定JVM执行15项通过，其中Controller10项、ViewModel5项。覆盖线性解锁、复习不写、完整恢复、失败读取保留、精确重试/同时间戳、确认丢失、CAS重新读取、固定Owner和退役等待、worker调度及ViewModel清理。测试仓库为显式替身，不等同真实SQLite。

同时编译6项Compose仪器测试：原有答对解锁/保存失败，加窄屏150%、失败冻结/重试、冲突重读、读错保留。仅编译，未由本子任务运行设备或截图检查。真实同DBStore、migration/backup、Gradle/Lint、API37设备与视觉验收由统筹串行完成；未验证实体设备、横屏或真实杀进程。

可纳入本切片的专属文件为`LanzhouMahjongLearningLibrary.kt`、`LanzhouMahjongLearningController.kt`、`LanzhouMahjongLearningViewModel.kt`、`LanzhouMahjongLearningScreen.kt`，位于`app/src/main/kotlin/com/masterofchessstrategy/games/lanzhou_mahjong/`；同棋种JVM目录的`LanzhouMahjongLearningControllerTest.kt`、`LanzhouMahjongLearningViewModelTest.kt`；同棋种androidTest目录的`LanzhouMahjongLearningScreenTest.kt`及本文。Root的data契约、Room/schema与导航不归此文件清单。私有日志和精确SHA清单位于`.build/lzlearning-ui-20261029/r1/`，不作为发布源码路径。

本子任务未调用Gradle、ADB或Git，未修改旧RoomStore、公共导航、数据库、备份或其他棋种文件。上述专属文件冻结，交统筹集成；不宣称十课设备验收或整棋种交付已完成。
