# 方棋独立棋谱备份测试

原切片新增 `FangqiRecordBackupAdapterTest.kt` 与本文，生产适配器和公共接线由统筹实现。随后按统筹专属授权，仅修订 `FangqiRecordBackupAdapter.kt`、其专属测试与本文以兼容旧中文棋谱 ID；未修改其他生产、共享文件或全局进度，未运行 Gradle、ADB、Git。

## 原 13 项测试与历史证据

- 13 字段按实际生产 codec 往返，保留 ID、模式、可空难度、结果、格式、完整历史、动作数、收藏、残局标志、完成时间和玩家索引；`A|~` 使用小写 hex `417c7e`，`Long.MAX_VALUE` 不丢失。
- `validate` 仅检查结构，不调用 Replay；格式 1 的 `prepareRestore` 必须得到完全相同的规范历史、相同终局和零待吃子。进行中、另一胜方、非规范历史、待吃子和显式回放拒绝均不能入场。
- 旧格式 2 按原始字节保留，不调用 Replay，也不生成正式来源或授予正式胜场。
- 10001、16384 动作完整历史逐字节保留；16385、超 1 MiB 格式 1 payload、超 64 KiB 旧 payload 拒绝。
- 未知容器或线版本、错误棋种、错误字段数、非法枚举/格式、非规范十进制、溢出、非法布尔位、非小写/奇数/坏 hex、非法 ID 均拒绝。
- 准备结果持有独立字节副本；修改原对象或返回副本不会修改已准备数据。
- 同一棋谱不得同时归核心 RECORD、方棋扩展或另一扩展所有；重复扩展棋谱亦拒绝。
- 实际 `PreparedFangqiRecord` 可参与 StartAdapter 完整快照 Guard，与正式创建、首次收据和 Outcome 精确配对；缺棋谱或改完成时间拒绝。删除标记存在时允许没有棋谱，但扩展不得恢复该已删棋谱。

测试显式注入 Replay 替身和 Admission 回显：标准根返回进行中，结构化文本历史的终局由测试指定。长历史用于检查完整容量与字节一致性，不是合法长局证据。默认 Native 回放器没有被调用，没有创建数据库或写入 SQLite；这些结果不证明真实 Native 终局、事务回滚、生产 factory 注册或设备行为。

## 执行证据

2026-10-30 使用既有 JDK 17、Kotlin 2.3.21、Android 37、Room 2.8.5 实际依赖，复用 `.build/fqrs1/source/` 的实际 App/engine 编译类。未生成 stub、安装依赖或修改旧编译库。私有编译 11 份真实源：Validator、NativeReplay、RankedBackupAdapters、GameBackupAdapter、GameBackupRegistry、FangqiBackupAdapter、GameRecordPayloadPolicy、GameRecordRepository、新 RecordBackupAdapter、冻结的 14 项 RankedBackupAdaptersTest 与新增 13 项测试。棋谱模型和容量策略使用当前工作区实际源。

编译退出 0，`compile.log` 为 0 字节；JUnit 退出 0，`OK (27 tests)`。11 源运行前后 SHA 一致。证据位于 `.build/fangqi-record-backup-tests-20261030/r1/`，包括编译/JUnit 参数、日志、`source-sha.csv`、`result.json`。先前 14 项的旧 SHA 证据仍保留于 `2026-10-30-ranked-backup-tests.md` 所列目录；本次 27 项是新增棋谱适配器及 Guard 合并后的定向回归，未改旧测试。

| 原 27 项验证的关键实际源 | SHA-256 |
| --- | --- |
| FangqiRecordBackupAdapter.kt | `b7aaa706274cbbc74b2827fdff52391fceb1c75dcd27d7443f6d94e26e124bf8` |
| FangqiRankedBackupAdapters.kt | `39e5c77a524a92f88f58bc1e2a37ec5b4a74dad801392a14e8131d72a9231589` |
| GameBackupAdapter.kt | `3aeef840a0ce1aa7be7d1c24fb22a947770d3bf2f00f237a9d1a52d2f12405a1` |
| FangqiRecordBackupAdapterTest.kt | `bdc78e628d8baa0b60329a540af5308ee0b3e82a3fd6caf4534e78dab2847d00` |

## UTF-8 棋谱 ID 兼容修订

旧 `GameRecord` 只要求 ID 非空白且不超过 64 个 UTF-16 单元，允许中文及内嵌空格。原适配器复用正式来源的 ASCII ID 编解码器，导致这些合法旧棋谱不能导出。本次只把棋谱 ID 改为独立的严格 UTF-8 小写 hex：编码和解码均使用 `CodingErrorAction.REPORT`，字节上限 256，解码后继续检查原有非空白及 64 单元限制，并核规范编码往返。正式创建/收据 ID 的 ASCII 规则及其共享 Wire 均未改。

新增 3 项测试，连同更新旧 ID 预期后的原 13 项共 16 项：

- 格式 1、旧格式 2 的中文、`|`、空格、64 汉字与 32 个补充字符 ID 完整往返；Registry 中的 ID 所有权保持原字符串，旧格式不调用 Replay。
- 非法首字节、截断、多余续字节、过长 UTF-8、代理项及超 Unicode 范围拒绝；编码孤立 UTF-16 代理项时亦拒绝，不以替换字符掩盖原数据。
- 全空白、超过 64 单元、超过 256 字节或非小写 hex 均拒绝；内嵌空格不再被错误当作非法 ID。

本次只执行受影响的 `FangqiRecordBackupAdapterTest`，未重复无关测试。使用相同实际依赖，私有编译当前 10 份真实源（原 11 份清单去掉未运行的 `FangqiRankedBackupAdaptersTest`）；编译退出 0、日志 0 字节，JUnit 退出 0、`OK (16 tests)`，10 源前后 SHA 一致。证据为 `.build/fangqi-record-backup-tests-20261030/utf8-r1/`，包含参数、日志、源码指纹和 `result.json`。前述 27 项仅对应原 SHA，不冒充本修订的正式集成验证。

| 修订后专属源码 | SHA-256 |
| --- | --- |
| FangqiRecordBackupAdapter.kt | `5461244daa4eb7ee026e91774639477869f85fae6786ec25b0993696fc92ecac` |
| FangqiRecordBackupAdapterTest.kt | `720b49eb2ffcd654860658d008ab6b49a8dbeec3abd732dd45375b6239c3418a` |

格式 1 可恢复的合法终局也不单独授予正式资格；必须仍有完整创建、首次收据和伴随事实。无来源记录保持无来源，不补来源或胜场。本修订尚未进行实际 Room/Native/设备验证，由统筹按受影响范围验收。

统筹负责后续实际生产注册、Room 读写、完整导出恢复、故障回滚和模拟器验收；本切片不授正式资格，不代表棋种全部完成。
