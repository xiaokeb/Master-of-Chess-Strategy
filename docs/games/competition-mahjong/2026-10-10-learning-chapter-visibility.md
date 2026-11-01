# 教程当前章节可见性修订

统筹真实MainActivity/Room普通4/4、150%两方法各1/1、保存重试补图1/1与9图已通过并查看，见 `../../testing/2026-10-10-competition-mahjong-integration.md`。完成进度重进第4章的150%图中，章节条可手动横滚，但选中第4章不在初始视口；本修订仅处理此可见性问题。

只改专属 `learning/CompetitionMahjongLearningScreen.kt` 与 `CompetitionMahjongLearningScreenTest.kt`。保留原横向Row与全部章节节点，选中章的BringIntoViewRequester在页面布局后把该按钮滚入视口；恢复/切章均触发。未改内容、顺序、答案、保存/完成门槛、公共导航或Room。新增独立测试在360dp宽度和150%字体恢复已完成第4章，核按钮可见，再切至第1章和第4章核自动可见，保留完成进度4。断言前不调用performScrollTo，避免测试自行滚动掩盖生产问题；旧4项设备测试断言全部保留。

## 验证与冻结

`.build/competition-mahjong-match-play-20261010/compose-check.ps1 -Published` 对17份麻将main与31项仪器源码编译退出0，只有原两项ComposeRule弃用提醒。既有Kotlin2.3.21/Compose插件/JDK17，未新增依赖或操作共享Gradle/设备。收据副本 `.build/competition-mahjong-learning-visibility-20261010/compose-compiler.log`，SHA-256 `08e4da747ecbe2363f7a72c12e22430eb66006d2886641cbb15a788e73e1f68b`。

新增方法与真实宿主修后视觉已定向通过；修前4项与共享9图收据保留，不充当修后结果。

同日统筹修后实际复验：新增窄屏150%方法 **1/1**，`.build/instrumented-class/43941ea84c144ec9b178e270ec4acff9/instrumentation.log`；真实MainActivity150%完成行重进 **1/1**，`da0374fa05dd41ef93b6bbd52b31c6e1/instrumentation.log`。两份已读；新图 `.build/competition-mahjong-public-v2-integration-20261010/visual-150/8ee163c3-6c07-43e6-b653-fd20d21d6aef/completed-reopen.png` 已在本对话查看，第4章完整可见，标题、完成按钮、正文无重叠。未重复29步/SQL或原4项；旧9图仍为旧快照。新8/30冻结最终一致，详见 `../../testing/2026-10-10-competition-mahjong-public-v2.md`。

| 冻结身份 | SHA-256 |
| --- | --- |
| `CompetitionMahjongLearningScreen.kt` | `91c0c2c618e6f333f0365cda3a3a5a58dd1c79e57bc3f4ae256a34c19e4f5d18` |
| `CompetitionMahjongLearningScreenTest.kt` | `086b756ade6b72adfe9ee81ee2390bb879efad93b49c37f12b296f88052617cd` |
| 新8份 `source-hashes.json` | `2a9c19f6e8713c61ed3525935b83c1563d116df53eb1226f251ab65beb6fd05a` |
| 新30份 `combined-source-hashes.json` | `cef887559944055fed7cc3c73fbdde8ef847686b4874e5fddb72d83d6e9447e0` |

两份清单同目录，分别相对原教程8份/修后排序30份只有这两份变化，旧文件保留；逐番9、赛事10及新赛事页面4未改。源码重新冻结供只读集成。

定向编译/设备及150%真实MainActivity截图复验已完成；没有重复29步完整链/SQL或旧教程4项。普通UI修复没有新增用户决定问题，完整国标目标与其他验收边界继续。
