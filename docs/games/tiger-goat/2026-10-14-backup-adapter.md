# 虎羊备份 Adapter 专属切片

日期：2026-10-14。依据 `AGENTS.md`、独立发布实施计划及 `game-backup-adapter-v1.md`。本次只新增专属 adapter、JVM 测试和本报告；不改公共 Codec/Repository、schema、Native、旧冻结清单或游戏规则。

## 已实现

`games/tiger_goat/data/TigerGoatBackupAdapter.kt` 接入公共 `GameBackupAdapter`，descriptor 固定为 `TIGER_GOAT_ACTIVE`、容器最低 v5、active_games 的 gameTypeCode=5、最多一行、规范排序 0。

沿用既有 kind 后七字段顺序：game code、archive version、Native version、session ID UTF-8 HEX、action count、更新时间、MOCT HEX。MOCT v1/v2 原始字节不重写，不转换成 MOCX。Archive 继续校验 53–196658 字节、0–65535 次动作、geometry=1、内嵌计数、FNV 校验、非空 ID（最多 64 个 UTF-16 单元）和非负时间。

`normalize` 保留旧解析器可接受的整数拼法（前导零、正号及合法负零）与大写 HEX；先解码再规范化，避免用规范行限误拒合法的旧拼法。ID 的 256 字节线限、UTF-8 回编码校验及 Native HEX 解析语义保持。公共 raw record/全容器仍受 128 MiB 限制，规范记录上限为 **393879 字节**（不含 LF）：17 字节 kind＋7 个分隔符＋3 个版本/游戏数字＋512 字节 ID HEX＋5 位计数＋19 位时间＋393316 字节 MOCT HEX。该保守上限不放宽 Archive 的实际 ID/状态限制。

`read` 仅查本槽，经 `TigerGoatActiveArchive.fromEntity` 拒绝不匹配的元数据；不静默丢弃坏行。`validate`/`normalize` 只校验包络，不调用 Native。`prepareRestore` 默认使用既有实际 Native validator：独立会话 `use` 中恢复并关闭，失败或抛错不返回 Prepared。Prepared 只持有已准入、owned 的 Archive 和固定槽；写入仅 `upsert` 本槽，不清库、不另开事务。完整快照/恢复事务与清表前调用顺序由公共 Repository 负责。

## 验证范围

已有轻量 Kotlin 编译环境中私有编译通过（JDK17、单逻辑处理器、512 MiB 编译堆），JUnit **13/13** 通过。仅使用注入准入 lambda 与内存 DAO；没有执行实际 Native、Room/SQL、Gradle、NDK、ADB 或 Git 操作，不占象棋续测的设备窗口。

测试覆盖 descriptor、旧拼法规范化及超规范字节限的合法前导零、v1/v2 字节保留、容器限制、错误 kind/字段数/owner/格式、非法计数/时间/HEX/UTF-8/ID、MOCT magic/geometry/计数/FNV、最大包络状态、row 元数据、准入拒绝/异常、Prepared 防御复制及只写本槽不清库。最大状态中的占位动作只是包络边界用例，不宣称 Native 接受该历史。

收据：`.build/tiger-goat-backup-adapter-20261014/junit.log`，SHA256 `4a50bcef4b4d93f46c0d1672a919acca9065271d90df031bf2bfafeed9d48c2c`。编译器无输出，进程返回 0；空输出管道未生成 compiler.log，不虚构日志文件。

## 待统筹集成

公共 Snapshot/Codec/Repository 与 Root 注册尚未迁移到本 adapter；当前生产备份仍使用旧路径。需统筹完成公共集成，验证 v1–5、缺 adapter fail-closed、清表前实际 Native 准入、真实磁盘备份/SQL 回滚及候选源码测试后，才能归入节点发布。旧 10 月 13 日验收不自动证明新注册路径。实体设备与项目其余功能仍未验收，不宣称虎羊完整开发完成。

专属源码 SHA256：

- Adapter：`ed320de89e9294b1490d3a32d7bfd64eb30819f8b921890210bddd63a5adac76`
- JVM 测试：`6b7bec543af4a1b12ff2875727f4d09cb7bde02c7d5b1ae00e33e5b36e9833bb`

无新增第三方资源或依赖，无许可口径变化。

## 追加公共 normalize 接口私有验证

按 Root 指定追加只读验证：当前实际 Record/Adapter/Registry/Inventory/InventoryDao、Archive、虎羊 adapter 源码与 Root 新增后的 `GameBackupRegistryTest` **20 项**、虎羊 **13 项**共同编译运行，结果 **33/33**。编译/运行前后九份源 SHA 相同，未套用旧 18 项结果，未修改公共源码/测试。

首轮私有 classpath 缺少 Room 注解 jar，编译失败（`registry-33/compiler.log` 保留）。第二轮仅补充缓存 `room-common-jvm-2.8.5.jar`，源码无改动，编译返回 0；JDK17、单逻辑处理器、编译堆 512 MiB/运行堆 256 MiB。仍未运行真实 Native/Room，也未执行 Gradle/NDK/ADB/Git。

成功收据位于 `.build/tiger-goat-backup-adapter-20261014/registry-33-green/`：`junit.log` SHA256 `ada20f2722e5684677416cd600a3bc317acdec99642c4ab1b91bf9f1c8162db2`；九份参与源的完整身份见 `sources.sha256.tsv`。其中 Registry SHA `0289f885d571cc3d34cf9b16e13dedd520bca74e6e65a76e7829eb6396589b37`，Root 20 项测试 SHA `a0e15fd0c0107e76dd0312b0874e5a003df5fab25e5420566a82db45af9d0b67`。

## 公共 Codec 迁移的只读红绿验证

Root 在迁移前委托确认新测试接口缺失：旧 Codec 编译配合 `RegisteredBackupCodecTest` 返回 1，诊断为必传 registry/字节预算参数及 `LocalDataSnapshot.gameExtensions` 尚不存在；未修改测试。红收据 `registered-codec-red/compiler.log` SHA `ed650c35f91c25541842a9534ed11db4dc8361f4d8317be74c312fe367fc77cc`；旧 Codec SHA `22b33938dabdf885b6273df9e657af2fca1bc91e335d9637e17fdd8a68c64383`。该失败是预期接口红，不是设备/运行失败。

Root 迁移公共 Codec 并加入 `BackupWireBuilder` 后，再次只读编译当前实际源码；**46/46** 通过（注册 Codec 8、线构建器 5、Registry 20、虎羊 adapter 13），十三源前后 SHA 相同。只使用单 CPU、编译堆 512 MiB/运行堆 256 MiB，不执行 Gradle/NDK/ADB/Git。

收据目录 `.build/tiger-goat-backup-adapter-20261014/registered-codec-green/`：`junit.log` SHA `8649b7733e8814933a9db9e527438f0c0883a20287698b86f85989e221b8eb3b`，完整源身份见 `sources.sha256.tsv`。新 Codec SHA `a50c5ad7947296b3d6b3aaa04f9c6635d178383bf8a9a7fc158d7f8d16a3c5bc`；WireBuilder SHA `f2dbd988562b0bb04fdd6b977e276d2a9bb932ca65c467115ec2a3b8d3375c36`。这些结构/包络测试不证明迁移后的生产 Repository 已集成或真实 Native/Room 已通过。

虎羊旧 Codec/Room 测试迁移方案已交 Root：adapter 提供内部包络 encodeArchive/decodeRecord helper，旧测试改用显式注册和 `gameExtensions`，保留混合备份、旧版本、实际 Native 拒绝、磁盘 reopen/undo、SQL 回滚断言；尚未修改旧文件，待协调共享边界与新 Repository 签名。

## 授权后的旧测试迁移

上述方案随后获 Root 串行授权。本次只修改专属 adapter 的包络 helper 可见性/命名，以及获独占授权的旧 `TigerGoatBackupCodecTest.kt`、`TigerGoatBackupRoomInstrumentedTest.kt`，新增共享混合测试 `RegisteredGameBackupMixedCodecTest.kt`；未改任何其他公共源码、冻结清单或全局状态。

虎羊 Codec 测试仅显式注册 Tiger adapter（注入准入函数一旦被调用即报错），保留 opening/nativeChecksum fixture，剩余 **6 项**测试。原混合方法完整移至共享测试 **1 项**，显式注册 Tiger/国标 adapter，继续覆盖全部四个 viewer、两种 Archive 内容、象棋状态与 ID、设置、重复编码字节；没有以单棋种测试隐式依赖国标。

Room 测试仅显式注册默认真实 Tiger adapter，并传新 Repository 的 `gameBackups` 参数；五个原有实际 Native 拒绝、坏 row、旧全量替换、磁盘 reopen/undo、SQL 回滚断言保留。另加 **2 项**：现有 Tiger 行在空注册下导出拒绝，以及旧空 v4 全量恢复拒绝；均要求磁盘重新打开后原行仍在。源码共 **7 项设备用例，尚未在设备执行**。

私有 JVM **20/20** 通过＝迁移后 Tiger Codec 6＋共享混合 1＋专属 Adapter 13。首轮 mixed 用例因私有 classpath 漏加 `app/src/test/resources` 失败，保留 `migrated-codec/junit.log`；仅补资源目录重验，断言未改变。成功收据 `migrated-codec-green/junit.log` SHA `07feaf02b64de66314ff0c03025fc6a72b4311d2465177ba17e04d6f58b50448`，十三源前后 SHA 相同，完整身份见同目录 `sources.sha256.tsv`。

迁移后 Room 测试与当前实际 Repository/Codec/Adapter 在私有 friend 编译中返回 0；十源前后 SHA 相同，清单 `migrated-room-compile-green/sources.sha256.tsv`。首编译发现 runBlocking 接收者遮蔽 `this.tiger`，仅将测试 adapter 成员明确命名为 tigerAdapter 修复，失败收据 `migrated-room-compile/compiler.log` 保留。该结果只证明私有编译，不冒称设备通过。所有目录均相对 `.build/tiger-goat-backup-adapter-20261014/`，无 Gradle/ADB/NDK/Git 操作。

当前身份（覆盖前文初版的对应源码身份）：

- Tiger adapter：`984f1318a4d48b4876b45466eea5aa2b1ab52a6cfe61c39188dff2cf340a4a0f`
- Tiger Codec 测试：`f0020a5b99ecfbe7326a00a913b03fe2d831ab5f5e939a6545d205c8e8e02aba`
- 共享混合测试：`7e4886d27f4883c54cc00651cf9d887810fb1e47f4f0ee0af069b2cb6c60465b`
- Tiger Room 设备测试：`a0d6b7b36e6e3f45325117738b879c64e58b11138fa1fa1e5dfd093cbffc58c7`

后续仍需 Root 正式跨模块构建、注册后的真实 Native/磁盘设备验证与精确发布树验收，不能以私有结果复用旧 10 月 13 日设备收据。
