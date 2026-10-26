# 兰州麻将24倍规则与完整活动局生产集成

本次从已发布 `9e4c37691b051a1a1e84c45e7a97ec88dae277c9` 基线接入。新局每家24倍、旧v1每家8倍分别保留；明确规则见 `../requirements/lanzhou-mahjong-false-win-v2.md`。不接尚缺独立十课存储/资格及可等待退出的牌桌入口。

## 公共变更与所有权

- CMake仅加入兰州规则、game/bot/settlement及独立JNI文件；注册表只增加Lz类型句柄和类型限定释放。NativeBindings仅加入Lz方法。其他棋种未发布的共享改动不混入候选。
- engine-api新增专属Lz协议；Native Kotlin门面固定viewer，只公开其手牌，保存完整私有历史。新规则v2为默认；旧历史按其内层版本重放和规范重存。
- 生产备份工厂签名不变。内部注册 `LanzhouMahjongBackupAdapter`，由同DB Lz gate包住原方棋/围棋完整组合；固定顺序Lz→Fangqi→Go→Room。每个实际进入的gate按现有取消/失败语义退休，不越过未取得的锁。
- 使用严格 `RoomLanzhouMahjongAdmittedSessionStore`，生产传入同一个MocsDatabase及其activeGameDao；保存先真实Native准入，再于Room事务写入及读回确认。旧宽松Store、同步关闭VM、十课公共表草稿不纳入本节点。
- 无新表或迁移，保留schema19。game3活动行原先已受库存守卫监管，新增adapter补齐其完整导出/恢复所有权，不跳过坏行。69字符ID、原辅助消耗、决策点及固定时间均保留。

## 验证与后续

先冻结最小依赖闭包及公共文件的干净基线变体，再执行定向Gradle/JVM、双ABI/Lint/APK和真实JNI/Room/完整备份测试。复核共享方棋/围棋恢复路径；未经实际执行的测试不记通过。原生旧8/新24与既有杠分、溢出原子性须关联精确输入，不以单纯容器CRC当合法历史证明。

设备测试只用自行创建的唯一QA磁盘库。旧局关库重开、四种外/内版本组合、非法历史先验拒绝、SQL清写故障回滚、旧容器缺段清理和旧Owner拒写均需留证。节点完成后发布明确源码范围；牌桌视觉、教程资格、成长、音效、自动演局和四档强度继续独立验收。
