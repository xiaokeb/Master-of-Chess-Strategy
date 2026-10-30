# 方棋正式来源完整快照测试

本切片仅新增`app/src/test/kotlin/com/masterofchessstrategy/games/lanzhou_fangqi/data/FangqiRankedSnapshotValidatorTest.kt`及本文。生产`FangqiRankedSnapshotValidator.kt`由统筹实现；本测试对话没有修改生产、共享、旧冻结源码或公共进度，未执行Gradle、ADB或Git。

## 验证范围

新增19项JVM测试全部通过。测试使用明确的`FangqiRankedNativeReplay`替身：标准根返回进行中，一步文本历史的终局结果由测试给定；活动准入使用显式字节回显或拒绝替身。它们验证完整聚合的来源与伴随策略，不证明该一步即为真实Native胜局，不代表SQLite、JNI、设备或正式工厂已验收。

- 正式创建、首次收据、Outcome与完整棋谱一致才返回有效人类胜场；真实结果口径由注入重放结果确定，输了的对局不计胜。旧通用成果及独立格式1记录均不补造正式来源。
- 缺父行、坏创建摘要、任一Outcome身份/结果/时间冲突、四类重复主键、无收据的创建成果均拒绝；原生重放明确拒绝、结果与收据相反或尚有待吃子同样拒绝。
- 未标记删除但缺棋谱不能推断为用户删除；有显式删除标记且棋谱不存在时保留原收据及有效胜场；标记早于首次结算或标记后棋谱仍在均拒绝。
- 棋谱核棋种、模式、难度、玩家、结果、格式、非残局标志、完整字节、动作数及首次结算时间。`isFavorite`属于可变用户偏好，改变该位不造成首次收据冲突。
- 正式进行中活动局连接准确创建；已结算活动局必须等于首次终局完整快照。无来源旧本地对局可返回可续状态，但无正式资格胜场。
- 活动局严格拒绝错误棋种/模式/难度/格式、结果覆盖、计时、提和、自动演局与变体字段、计数及负时间；活动Native准入失败不能降为缺记录。返回创建/结算映射不可修改，活动字节保持拥有副本。

## 实际执行与证据

使用既有JDK17、Kotlin2.3.21编译器、Android37 API及`.build/fqrs1/source/`实际App/engine编译类，未安装依赖、未生成替身类或修改已编译库。仅编译统筹Validator、实际`NativeFangqiRankedReplay.kt`及新增测试三份源码，再限定执行本测试类。最终编译退出0、编译日志0字节，JUnit退出0，`OK (19 tests)`；三份源文件在编译/运行前后SHA一致。

证据根`.build/fangqi-ranked-snapshot-tests-20261030/r3/`包含`compiler.args`、`compile.log`、`junit.args`、`junit.log`、`source-sha.csv`及`result.json`。`r1`因私有Java参数文件反斜杠被解释而停止，`r2`因Kotlin编译classpath未含实际Android API而停止；这两轮未执行测试，日志原样保留。只修正参数及真实classpath，未为使测试通过而改生产或测试源码。

Root仍须在正式候选中完成编译与必要回归、实际Native完整胜局、Room同事务读取/回滚、备份全部来源/缺段/删除状态及生产入口验收。上述19项不能代替这些工作，也不能宣称方棋完整交付。
