# 正式赛事 schema22 与库存保护验收

前置HEAD为 `69583ad`。本节点注册 `CompetitionMahjongFormalRoomRow/Dao`，建立 `competition_mahjong_formal`，并使新表进入完整备份库存检查。它是统一正式仓库的数据库前置；正式MCTX准入、RoomStorage/Bridge、旧源迁移、三Route与完整备份适配器仍待后续接入。

## 数据行为

21→22迁移仅执行一次建表。生成的22.json与21.json相比，原21个实体定义完全相同，只新增正式表；不复制、清理或升级旧MCMT。新库和迁移后均不自动创建归属行。生产数据库构造器已注册此迁移。

每个viewer的归属由owner/epoch/revision字段记录，DAO提供轻量metadata、substr分块读取和带旧身份/版本条件的CAS。原始DAO没有MCTX语义校验，正式宿主仍须经现有仓库/Bridge处理，不能把任意BLOB或直接DAO更新称作合法赛事。

库存使用无过滤条件的`SELECT 2 FROM competition_mahjong_formal`，每个归属行都要求国标专属COMPETITION_FORMAL槽；待迁移null、损坏字段和未知viewer也不能被过滤掉。旧COMPETITION_MATCH适配器不覆盖新槽。完整适配器未接前，只要存在正式归属行，生产导出会在读取快照前拒绝，替换恢复会在唯一写事务清表前拒绝。现有正式表为空时，原完整备份流程继续工作。

## 验证

候选cmfs1冻结1874份源码。真实KSP生成22.json，schema identity为`1ba6cf192e9119f68d4131ddd2cccdc0`。NDK30双ABI、Debug APK/测试APK、Release Manifest及Lint通过；Lint0错23警告。六组57项JVM本轮全部执行通过：新库存7、Registry23、GoAI库存6、Go库存5、容器15、混合容器1；没有用私有替身测试冒充设备验证。

API37/x86_64/16KiB模拟器通过23个不同方法、25次调用：新schema6、新生产库存4、共享库存6、方棋完整备份5，以及真实设置页2项×普通/150%字体。新schema覆盖实际21→22迁移保留旧表字节/学习/Outcome、无隐式迁移、3132064字节任意BLOB经64KiB分块和数据库重开、旧owner/epoch/revision拒写、事务失败同时回滚lease与payload，以及viewer主键隔离。此大BLOB不是合法MCTX或完整赛事夹具。

新库存用真实生产备份工厂及UUID磁盘库验证：空表往返可用；待迁移、空BLOB、坏字段/未知viewer均能阻断导出和替换且保留原行/Outcome；数据库重开后保护仍在，旧MCMT字节不被替换清表。方棋备份和设置页回归覆盖原其他棋种数据、实际SAF导出/恢复、取消/坏导入保留和新Owner继续保存。

设置与恢复后围棋页面共12张PNG、8个不同SHA画面均已直接查看。成功/失败提示可辨，普通/150%字体所见区域无重叠；滚动视口有上下边缘截断，不表示完整横屏或国标牌桌视觉验收。当前没有新增国标界面。

首次设备预检因AVD未在线而停止，发生在安装/字号/仪器测试之前。确认无emulator/qemu进程后保数据启动原Pixel_7_Pro（无窗口、不保存退出快照）；启动器因renderer不匹配转为冷启动，未wipe、未重启活跃模拟器。其后25次调用均成功，原字体1.0已恢复。未清默认应用数据、未启动象棋搜索；此处数据库关闭重开不等于杀进程验收。

主APK SHA256：`02456f4061f7449051a8dea45ca446bff8c925f37e1bc09daaad08cef6c8512c`。

测试APK SHA256：`9d30ea8c3bb3560e13bf7351b5152550510c665471e82ef8a95eb48a3d25d9b5`。

证据位于`.build/cmfs1/`，成功设备目录`device-a7c2c12603984479a34c55c1919f746e`；最初未连接目录`device-f4277cedba5847c1936e23b5aeb309ae`保留。发布清单记录源码/日志/图片/产物散列。既有第三方资源、协议和许可边界未扩大。

下一按[统筹集成计划](../../development/competition-formal-integration-2026-10-31.md)接入真实RoomStorage/Bridge、可信权限、旧写者退休与明确迁移，再实现整容器准备/四座安装/提交后发布。上述功能、正式导航、完整内容/音效、AI强度和实体设备仍未由本节点完成。
