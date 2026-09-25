# 内容来源门禁与残局解析一致性测试

日期：2026-09-25。当前发布范围为项目自编象棋残局 v4 和定式 v3。

- `verifyReleasedGameContent` 与 Debug APK 同时执行通过，并在第二次运行
  `verifyReleasedGameContent` 时成功复用 Gradle 配置缓存。
- Android 解析器对包头顺序错乱、重复包头、棋子记录位于走法之后三个反例
  的新 JVM 测试先失败，修正后通过；既有 v4 正常解析。
- Python 离线审核器原有 9 项测试通过，当前十关来源哈希保持不变。
- 完整门禁：JVM 203/203、App AVD 50/50、JNI AVD 4/4、Debug APK
  构建通过；Lint 0 错误、3 条依赖版本提醒。

本门禁只验证登记、固定哈希和格式；法律授权仍须人工逐来源审查，不能以
机器清单的存在替代。
