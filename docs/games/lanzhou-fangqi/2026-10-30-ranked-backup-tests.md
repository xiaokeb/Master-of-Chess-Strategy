# 方棋正式来源备份适配器测试

本切片仅新增`app/src/test/kotlin/com/masterofchessstrategy/games/lanzhou_fangqi/data/FangqiRankedBackupAdaptersTest.kt`及本文。Root实现生产适配器、完整快照验证器和共享Registry接口；本测试对话未修改这些源、既有19项快照测试、数据库或公共进度，未调用Gradle、ADB或Git。

## 14项定向验证

- 创建16字段、结算18字段的规范线格式与原始行往返；容器最低版本5及稳定顺序32/33。
- 会话ID按小写hex交换，`A|~`保存为`417c7e`，不会与线分隔符混淆；空白、控制符、非ASCII及超64字符ID拒绝。
- 非规范十进制、符号、空值、前导零、溢出、非小写hex、奇数hex、坏摘要、错误棋种/线版本/内容版本/引擎格式/字段数均拒绝，不猜测或改写版本。
- 10001、16384动作完整历史不截断地往返，16385及超1MiB历史拒绝；删除标记、无删除状态和Long.MAX_VALUE时间保持精确，删除时间早于首次结算拒绝。
- `validate`和单条`prepareRestore`只做结构入场，不调用注入Replay。Registry完成所有准备后，实际调用新StartAdapter的完整快照Guard；只有声明核心棋谱ID、没有完整核心payload时拒绝。
- 核心棋谱的难度、结果、首次时间、人类方、格式、完整历史和残局模式必须与收据一致；Outcome身份/结果/时间亦需精确。收藏标志改变不冲突。
- 无删除标记而缺棋谱不能降级；显式已删除棋谱不得通过核心记录复活。缺创建、缺首次收据、缺成果父行或重复创建均拒绝；完整重放拒绝或实际结果与声明相反，不能因线格式已通过而绕过。

测试使用明确的Replay替身：标准根返回进行中，一步文本历史的胜负由测试指定；活动Admission亦显式注入字节回显。因此上述是实际生产Wire/Registry/Guard及模型的策略验证，不是合法连续长局或真实Native终局证明。测试没有写入Room、调用实际生产factory或运行设备，Root仍须验证真实数据库、完整导出恢复、故障回滚及缺段策略。

## 执行证据

使用既有JDK17、Kotlin2.3.21、Android37和Room2.8.5真实AAR的classes.jar，复用`.build/fqrs1/source/`实际App/engine编译类；未安装依赖、生成stub或改写既有编译库。私有编译9份实际源码：Root Validator、NativeReplay、RankedBackupAdapters、GameBackupAdapter、GameBackupRegistry、FangqiBackupAdapter、GameRecordPayloadPolicy、GameRecordRepository以及新测试。后两份是Root此前的真实长局策略工作区源，不拿旧64KiB/10000模型冒充新版验证。

最终编译退出0，compile.log为空，无编译警告；JUnit退出0，`OK (14 tests)`，执行前后9源SHA一致。证据根`.build/fangqi-ranked-backup-tests-20261030/r2/`包含compiler/junit参数、日志、source-sha.csv及result.json。`r1`因私有Kotlin编译classpath缺RoomDatabase运行库在编译阶段停止，未执行测试；只从现有Room AAR提取实际classes.jar到私有目录补齐，源码未改，失败日志保留。

Root负责最终候选构建、必要公共回归及真实Native/SQLite/备份验收。本切片不能证明生产备份适配器已注册，也不授正式资格或宣称整棋种完成。
