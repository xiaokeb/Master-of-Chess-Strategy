# 正式备份 Wire 与旧源只归档关联

本节点只新增 `data/CompetitionMahjongFormalBackupWire.kt`、专属测试和本说明。不修改已冻结Bundle、准备器、Room、旧adapter或公共备份接口；未注册生产正式kind。Wire只完成结构与归档来源关联校验，不执行Native、数据库、安装、资格计算或旧源清理。

## 格式与规范化

仍使用既有 `COMPETITION_FORMAL` kind：

| 形式 | fields | 使用边界 |
| --- | --- | --- |
| 旧三字段 | `[2, viewer, mctxHex]` | 同容器没有旧 `COMPETITION_MATCH` 时可用 |
| 新五字段 | `[2, 1, viewer, mctxHex, retainedHash或-]` | 版本1；角色仅 `-` 或64个小写十六进制字符 |

`normalize(record)` 复用Bundle的归属、座位、容量和完整MCTX结构校验。旧格式仍返回三字段；新格式保留版本与角色，不在整容器关联核对前丢弃这些字段。既有可接受的viewer数字拼写、MCTX十六进制大小写会规范化；棋种必须精确为 `2`，新wire版本必须精确为 `1`，角色不接受大写hash或其他拼写。

新记录预算为 `128 + 2 × MCTX.MAX_BYTES`，包含kind、分隔符、版本、viewer与最长64字符角色。剥离标记后交旧Bundle时，仍执行旧三字段预算。公共adapter继续负责备份容器版本、总容量和其他owner的wire校验；本独立Wire API没有备份头输入，不代替这些检查。

## canonical旧源SHA256

`retainedLegacySourceSha256(record)` 先调用已发布旧adapter的 `normalize(record, 5)`，只做旧信封校验，不调用它的Native准入。随后按完整规范行计算SHA256：

```text
kind|field1|field2|...|fieldN\n
```

实际输入为UTF-8字节，末尾是单个LF字节；包含kind及全部字段，而非仅checkpoint。规范化保留私有checkpoint的原字节。实现按kind、分隔符、逐字段和LF分段更新digest，避免额外拼接整条大记录。这个hash是候选记录之间的归档关联，不是认证、防篡改凭据或可信胜场来源。

`encode(viewer, mctx, retainedLegacySource?)` 总是输出新五字段。有旧源时绑定其canonical完整行SHA，无旧源时写 `-`；MCTX通过既有Bundle复制和结构校验，不保留可被调用者修改的输入数组。

## 整容器split

`decodeContainer(records)` 首先复制候选集合，并限制最多4条Formal和最多1条旧record。它校验全部Formal、canonical viewer唯一及各座角色一致，才返回以下明确分支：

- `CompetitionMahjongFormalBackupCandidate.LegacyOnly(records, legacyRecord)`：有旧record但没有Formal。保留全部候选记录，旧记录规范化；不生成空MCTX或恢复计划，继续交给既有legacy-only流程。
- `CompetitionMahjongFormalBackupCandidate.Formal(strictRecords, retainedLegacyRecord)`：有Formal时，关联源必须存在且hash一致；没有旧源时全部角色必须为 `-`。关联成立后才移除这一条只归档旧record，并把Formal剥为Bundle三字段，供严格准备器一次处理。MCLP和其他owner的记录保留，未生成权限或胜场。
- 同时没有Formal和旧record也返回Formal空候选，保留其他records；是否生成四座明确空事实由后续严格准备器负责，不在Wire中执行。

所有已有Formal席的角色必须相同。旧三字段隐含无旧源，因此在有legacy时不能与带归档角色的新五字段混合通过；无legacy时，旧三字段与新 `-` 表达相同无源含义，可以并存。缺源却声明hash、有源却声明 `-`、hash不符、多源、重复或越界座位、超量、未知版本及坏MCTX均在split拒绝。

返回集合为独立不可变列表；`GameBackupRecord`本身持有不可变字段，不暴露载荷数组。Wire不会丢弃坏/重复MCLP来选择一个看似有效的证明：这些候选保持原样，公共wire校验和后续严格准备器须拒绝它们。

## 后续生产接入

公共层应先验证全部wire，再一次调用整容器准备。Formal分支把 `strictRecords` 交给已有 `CompetitionMahjongFormalRestorePreparer`；只归档旧记录继续由旧adapter准入与同事务安装，不能作为活动正式来源授予权限。LegacyOnly分支保持旧导入语义，不能把它当作Formal空候选补四座空库。

Native准入仍在Room事务外；安装前的实际库存保护、整库退休、唯一替换事务、提交后发布Bridge及失败退休由统筹负责。此节点不自行决定legacy-only替换时旧正式归属行的退役/删除方式，不会改写旧行以绕过兼容边界。

## 验证与冻结

2026-11-01 私有Kotlin2.3.21/JDK17/JUnit4.13.2编译无警告，`-ea` 下16/16通过。覆盖legacy-only及空Formal分支、旧三字段兼容、新角色保留、四席一致关联、完整canonical行/LF与字段变化、旧拼写规范化、缺/错/多源、旧新混合、重复/坏席/超限、不可变输出和MCLP不丢失。

证据：`.build/competition-formal-backup-wire-20261101/r1/` 的编译、JUnit、结果、源码及二进制依赖前后哈希，均一致。入口复用旧私有检查器的明确快照来源但输出到新目录，不改准备器r1/r2：正式依赖来自 `.build/cmlp5/source/` App/API/Native classes；JVM夹具来自同源核验的 `.build/cmlp2/source/`。测试使用结构夹具，不执行Native或数据库。

未运行共享Gradle、ADB、Git或引擎搜索。三个新增正式文件到此冻结，供统筹后续集成；纯Wire通过不代表生产备份、真实Native、Room安装或可玩赛事已通过。
