# 注册式游戏备份迁移：源码与轻量验证

Root 串行迁移公共文件，虎羊、国标代理独占其 adapter 和专属旧测试。此记录不是发布准入：尚未运行迁移后正式 Gradle、APK、真实 Native/Room、模拟器或视觉验收。

## 实现范围

- Snapshot 使用不可变字段记录 `gameExtensions`；公共 Codec/Repository 不引用虎羊或国标载荷、引擎准入函数。App 组装处显式注册两 adapter，直接构造 Repository 的测试工厂也明确注册。
- 保留 v1–5 读取：无虎羊输出 v4，有虎羊输出 v5；专属 adapter 保留旧数字/HEX解析及规范输出，原生档案字节不重写。虎羊先于国标，不受注册列表顺序影响。
- `BackupWireBuilder` 逐行检查最终 UTF-8 字节预算，包括头、CREATED、核心行、扩展行、换行及72字节 SHA256尾行；上限仍128MiB。不会先拼接完整超限正文再检查。
- 导出在同一 Room 事务中检查两表实际主键库存、读取全部核心及已注册扩展；事务外完成核心和全部扩展真实引擎准入，再编码。
- 恢复先完整解码及全部准入/Prepared；单事务内再次检查库存，再统一清表和写入，失败整体回滚。缺 adapter、未知实际 code、错误表归属一律拒绝，不漏导或清掉未支持数据。
- schema13、既有八张用户表及迁移不变；停写屏障仍由既有 App/备份 ViewModel 管理。完整版本注册两 adapter，单节点候选只注册实际包含的模块。

Root审查补充：校验回调结束后再次核固定 descriptor，立即拒绝回调中的注册变更。先用新增用例复现失败，再加一行固定身份检查；不是修改游戏规则。

## 实际轻量结果

各次运行存在重复用例，不相加为一次总测试数。均使用已有缓存/JDK17，单CPU/512MiB；不运行 Gradle/ADB或重建评测核心。

| 运行 | 结果 | 收据目录（仓库 `.build/` 下） |
| --- | --- | --- |
| 新 Codec 8、WireBuilder 5、Registry 20、虎羊 adapter 13 | 46/46 | `tiger-goat-backup-adapter-20261014/registered-codec-green/` |
| 象棋核心 Codec 15、新 Codec 8、WireBuilder 5 | 28/28 | `registered-backup-root-20261014/core-private-resources/` |
| 虎羊 Codec 6、原混合用例独立1、adapter 13 | 20/20 | `tiger-goat-backup-adapter-20261014/migrated-codec-green/` |
| 国标 adapter 10、Codec 4、V2Storage 5 | 19/19 | `competition-backup-migration-20261014/` |
| 修后 Registry 21、新 Codec 8、WireBuilder 5 | 34/34 | `registered-backup-root-20261014/registry-postvalidation-green/` |

首轮核心/mixed夹具因私有工具缺测试资源目录失败；只补类路径，保留原失败收据。国标工具初缺 Room 注解jar，补已有缓存；虎羊Room首编译有测试接收者遮蔽，明确成员名后编译通过。新增descriptor用例的红收据 `registered-backup-root-20261014/registry-postvalidation-red/` 为34项中该1项失败，修后34项通过。均未删除失败证据或降低断言。

核心28项成功日志SHA：`3d489f67c98eac27444c52173a04b45104c9b38986bf7a1819509f57500ede87`。修后34项日志SHA：`fd30c65e913c2e73b60456c971c3d643040dbeb633ba467be61342f3ec73c338`，Registry源 `d93cb81638561098eb190f00e297eb9d6bd8e51b6b452d97c6f8a237bdb45c7d`，其21项测试源 `34b8f5ae5c7d19e89336ead8372ae329c51c07289cb1c77ff0521ab293a3529b`。逐次参与源码SHA见各目录的清单（有清单时）；旧10月13日正式18项基础与虎羊81项属于迁移前身份，不能覆盖本次源码。

国标代理对公共源码只读审查：除已复现/修复的回调后身份检查外，未发现新的可确认缺陷；核对旧头/Settings版本、规范化、最终预算、全部准入先于清表和单事务库存/写入。审查不是设备证据。schema13 SHA仍 `7eb744a6dbbe9082fa6393c6df9b31f7a1d80290c8ed98fe060e84c92a117783`。

## 已编译、未设备运行

- 新公共 `RegisteredBackupRepositoryInstrumentedTest` 5项私有源码编译通过：已知/未知未注册实际活动槽、输入缺模块、所有Prepared在清库前完成、prepare期间新写入库存必须再次拒绝、写入失败整体回滚。真实SQLite事务测试使用受控provider，不证明任何游戏Native规则。
- 虎羊Room 7项、国标Room两类14项已随当前 Repository/Codec 在私有编译中通过；实际设备尚未执行。默认专属adapter仍走真实Native准入，包络helper不能代替此准入。
- 虎羊缺adapter的导出/恢复各1项、国标缺adapter专项保持旧档及其他表；旧非法原生历史、磁盘reopen/undo、SQL失败、旧备份全量替换断言保留。
- 公共设备源码编译收据：`registered-backup-root-20261014/repository-device-compile-only/`。App组装和两项象棋UI设备工厂改动尚待正式集成编译，不能称已建包。

## 下一串行门禁

象棋固定35局续测占用唯一AVD/大型构建窗口。本轮不启动新的评测、Gradle、NDK大构建、设备测试或发布；原前缀及冻结评测输入不改。

待续测结束并完成审计：先受影响JVM、主/测试APK及Lint，再上述真实Room/Native和象棋停写屏障回归，补备份及传统导航必要视觉检查。之后验证虎羊单节点的精确源码树、共享JNI/CMake/导航/schema/许可闭包，显式暂存、提交、推送、附注tag和prerelease；不附混合工作区APK。任一棋种独立节点通过即可发布，不等待六棋种齐备。

只读候选闭包复核：菜单/备份原静态耦合已解除；候选仍须以已发布HEAD加虎羊共享片段组装。保留纯Room国标Entity/DAO/schema13和库存，不必带国标Archive/Store/adapter/Native；候选App只注册虎羊。Registry/JNI/CMake/导航/Manifest/Gradle不能复制混合整文件，须排除其他棋种、KataGo及未发布象棋增量。混合codec测试属双注册版本；只含虎羊的候选不得声称跑过该配置。核心fixture测试资源不能遗漏。此处尚未创建候选树、索引、提交或tag。
