# 国标麻将正式 Room 后端验收

2026-10-31，候选 `cmfr1`，基线 `5ad300faf3598ab58ea5d1b403723e12de59ced9`。只纳入正式仓库、Room桥、历史/统计模型、备份Bundle及公共座位保护；其他对话WIP不纳入。

## 本次行为

- MCTX统一保存活动MCMT、统计和公开16盘结果；派生视图禁止独立写入。真实Room存储采用64KiB分块读取、固定Owner/epoch及revision CAS，退休实例拒绝读写和导出。
- Bridge在SQLite事务之外执行真实Native恢复准入，包括v4提示回执的位置和动作合法性。未显式迁移的空载荷不能打开；迁移确认前后重查来源SHA，不改写旧MCMT，同字节重试幂等。
- Bundle严格使用 `["2", viewer, hex]`，拒绝旧两字段、错误棋种、重复/越界座位和超限数据；先准备全部候选，缺席座位安装显式空事实，禁止旧来源复活。
- 公共导出/恢复在各自Room事务内枚举全部实际座位。异常座位在读取adapter或清表之前拒绝；目标测试设置`BEFORE DELETE`拒绝触发器，避免“已清表但回滚后仍在”的假阳性。
- schema仍为22，实体及JSON均未变化。生产工厂仍是旧MCMT适配器，非空正式槽继续由库存保护拒绝；本节点没有注册不完整的正式备份支持。

## 正式测试

封存源码1904文件，28个明确覆盖文件；加本报告、发布说明及清单后，完整Git导出1907文件、31个变更路径。正式入口为 `.build/cmfr1-build.ps1`、`.build/cmfr-device.ps1 -Candidate cmfr1`。身份与收据见[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-formal-room.1.json)。

| 范围 | 实际通过 |
| --- | ---: |
| FormalRepository / Migration | 16 / 12 JVM |
| RoomStorage / MCTX Codec | 15 / 6 JVM |
| Bundle / 注册表字段 | 20 / 2 JVM |
| 公开历史 / 统计 | 5 / 5 JVM |
| 公共Registry / Codec / 混合格式 | 23 / 15 / 1 JVM |
| 真实Room/Native桥 | 9模拟器方法 |
| 公共清表前保护 / 库存回归 | 2 / 6模拟器方法 |
| 方棋完整备份 / 实际设置与围棋重开 | 5 / 2模拟器方法 |

共120 JVM、24不同设备方法/24次调用，无失败或跳过。KSP、NDK30 LLVM双ABI、主/测试Debug APK、Release Manifest及Lint通过，Lint0错误23既有警告。实际平台为Pixel_7_Pro/API37/x86_64/16KiB；ARM64只编译，未运行实体设备。复用现有AVD，不清默认数据，字体恢复1.0。

主APK SHA256：`d085bd5c0ff087cc75f50397f168f8189c9e99dbd91e2f0b8af05cbcdd2f98c5`；测试APK：`b83f2235589045a1edcece68dcf2883498f533fb6de0065b8e053ad3f43ed6ab`。APK仅作本地同源验证，Release不附二进制。

设备收据在 `.build/cmfr1/device-8fbd542692a0450daaf6a9ae4a5728a0/device-receipt.json`。6张原始PNG中4个不同画面已直接查看：设置导出、恢复成功、坏文件拒绝、围棋重开继续落子。画面清楚，既有滚动视口上下裁切及围棋底部控件部分出框保留记录；不宣称全横屏完成或国标新页面已验收。此节点不改UI，无须重复上一节点150%字号检查。

## 验收边界与后续

仪器测试以真实Native生成初始及一步MCMT v4，提示回执和难度许可是明确QA数据；它们不等同于真实教程、顾问支付链、16盘完整比赛、可信胜场或AI梯度。数据库关闭重开不等于杀进程恢复。MCHR只含公开结果，不包含过去16盘的完整牌墙与动作日志，不能据此宣称过去全场均经Native验证。

下一依赖：独立四章29卡学习确认及持久化；正式创建/胜场来源；旧写者退休与封源；预览式迁移；整容器准备、清表、四座安装和提交后发布；生产工厂、宿主及三Route。完整内容、美术音效、性能、实体设备和强度验收继续单列，整体目标仍进行中。未新增第三方资源，Pikafish/NNUE仍遵守NOTICE和既有法律文档，不扩大授权。象棋旧异常评测未操作。
