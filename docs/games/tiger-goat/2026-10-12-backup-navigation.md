# 虎羊备份与正式导航集成

2026-10-12 Root公共源代码窗口。新增/受影响16份源码及测试身份见 `shared-backup-navigation-freeze-20261012.tsv`；它含共享文件的整个工作区身份，不表示该文件全部改动可纳入虎羊提交。专属App16/native9仍不变。

## 备份

TigerGoatActiveArchive持有MOCT字节副本，输出副本；限制53–196658字节、动作0–65535、原生v1/v2/棋盘v1、长度/计数/FNV、非空无损UTF-8身份及非负时间。活动行只接受棋种5、本地双人、envelope5及默认象棋专用字段；未知元数据报错，不能默默丢弃或归一化掉。

含虎羊的备份写MOCS-BACKUP v5，新增八字段 `TIGER_GOAT_ACTIVE|5|1|nativeVersion|sessionIdHex|actionCount|updatedAt|MOCTHex`；无虎羊仍写v4，既有赛事/象棋的编码身份不变。解码兼容v1–v5，v1–v4不能包装虎羊记录，重复虎羊记录拒绝。ACTIVE/RECORD仍是象棋专用，不将MOCT传入MOCX解析器。

Repository先独立读取并保留虎羊5行，再读取其他旧数据；原生验证在清库前，对每份MOCT用独立真实Native会话重放并关闭。校验失败保留原库；全部准入后单Room事务替换并恢复虎羊行。返回的活动对局数包含虎羊。导入无虎羊的旧备份仍为全量替换，确认文案明确会移除对应现存存档。

只读审查发现v5设置10字段被旧解析分支误拒绝。新增携带非默认外观/收藏位/AI先手、真实象棋夹具、四种玩家身份赛事及虎羊的混合测试复现失败，加入v5分支后同断言通过。不把仅虎羊无设置的早期绿色结果当完整正常备份。

## 导航与写入屏障

正式五卡首页不变；兰州民间棋类菜单新增虎羊本地双人按钮，进入 `tiger-goat/local-game`。TigerGoatNavigationRoute使用NavBackStackEntry的ViewModelStoreOwner，注入真实RoomTigerGoatSessionStore与NativeTigerGoatRulesSession，委托专属正式Route。VM退出时按既有onCleared释放Native，不用remember裸造VM。

LocalDataWriterRegistry按Room实例共享，Activity重建/重开仍使用同一注册表；保留VM直至scope确实完成，离页后的取消SQL清理不能提前丢失。scope完成时移除，数据库键为弱引用。Root恢复前把这些scope与既有数据owner交给awaitLocalDataWriters，先停生产者、取消并等待旧写入，再开始备份恢复。普通数据库和测试独立数据库不共享屏障。

## 当前验证

2026-10-13 正式构建、磁盘 Room 备份 5/5、实际导航 2/2 及普通/150% 横屏视觉已执行通过，见 [后续正式报告](2026-10-13-integration.md)。下列“尚未运行”是 10 月 12 日历史状态。截图已改为目标应用 `cache/tiger-goat-navigation-visual/`，不再写测试包 filesDir。

- 私有Kotlin2.3.21/JDK17/API37/Compose源编译成功，无新增依赖，无Gradle/AVD操作。
- 备份＋屏障30/30：虎羊codec7、既有象棋15、既有国标codec4、新注册表3、既有屏障1。收据 `.build/tiger-goat-backup-20261012/junit.log`，SHA-256 `5dd031220d5f3abe3f8bd734364345e648bc1bb90b4a821ff3fe4e6c05fec27f`。
- 正式App导航/菜单/虎羊专属源私有编译，路由29/29。收据 `.build/tiger-goat-navigation-20261012/junit.log`，SHA-256 `7d89b47ad51f89b3692a541e5aa78f99987eb46e84242c3585d305a6aee9fe3c`。
- 真实Native/UUID磁盘Room备份5方法源码编译：象棋＋虎羊＋非默认设置往返/重开/悔棋，重算内外校验仍非法的Native历史拒绝保留旧库，坏元数据导出不改原行，旧v4全量替换，SQL触发写入失败整事务回滚。尚未设备运行。
- 正式NavHost设备2方法源码编译：从真实首页/菜单进入、棋盘触控移羊、落盘、离页等待scope完成、重进/悔棋；Activity重建保持同一存档/历史。debug-only TigerGoatNavigationCheckActivity全过程绑定UUID隔离数据库，不在重建时短暂切回生产库；无生产数据库改名/清空。两方法、四张拟取图位于测试包 `files/tiger-goat-navigation-visual/`，待普通/150%执行并逐图验。

早期私有检查脚本漏引擎类路径/测试资源，单个坏档测试把原0改为0而未扰动，首轮失败均保留；修正检查配置/测试扰动后通过。SQL回滚测试初编译因Exception/Throwable推断失败，只改测试显式泛型后复编。v5设置回归红收据保留于 `v5-settings-regression-failure.log`。未据这些夹具/检查问题改规则。

只读审查另发现debug宿主未继承生产横屏约束。现设为与MainActivity相同的landscape/adjustResize，仍非导出，截图前断言宽大于高；XML属性核验与受影响设备源码复编通过。10月12日末审批额度耗尽阻断冻结文档同步，10月13日按当前文件散列补齐；未调整模拟器旋转，未冒称视觉通过。

## 未验收与发布

象棋AI原会话75967仍独占AVD/大型CPU，当前不启动正式双ABI Gradle/APK、原生执行、仪器或视觉。私有编译和上述JVM不证明实际JNI/SQLite回滚/页面图形已通过；真实设备、正式构建、App生命周期与视觉均待串行验证。

本切片只接本地双人；虎羊AI、自动演局、残局、教程、成长/棋谱等仍按正式需求独立推进，不能称整棋种完成。节点证据齐全后才按单棋种门禁精确暂存、推送/tag/Release；当前暂存空，不修改象棋音效已发布标签。
