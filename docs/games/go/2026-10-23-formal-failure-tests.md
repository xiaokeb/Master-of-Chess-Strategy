# 正式 Session 完成失败与丢失确认专项

## 状态

新增 `GoFormalCompletionFailureInstrumentedTest.kt`，**4 个设备测试方法**，其中正式创建确认方法含两种独立数据库情形。当前仅完成源码与 API 静态核对，**未编译、未运行**，不得作为设备通过或发布证据。此前冻结的 6 项 Session 测试与生产文件未修改。

## 四项补强

1. **恢复终局 reservation 写入前失败。** 先以真实 Room 存入未 reserved 的旧 Native 终局，包装 repository 在 reservation 写入前抛 I/O 错误。断言 Session 仍 ready、completionPending=true、不能悔棋，数据库未冒报 reservation；解除故障后 retry 只保存同 UUID，普通棋谱可写而没有正式来源或成长。
2. **真实完成事务已提交，返回确认丢失。** 包装真实 Room sink，先成功提交，再第一次抛 I/O 错误。断言数据库已是一份成长，UI 仍待重试且不可悔棋；重试后仍一份 outcome/marker/projection/record，收藏保留，没有另开局。
3. **已进入 sink 后退休。** sink 使用 entered/release Deferred 显式挂起。进入后调用 beginRetirement，立即封住动作/重试/重开；已开始的 closeAndJoin 在 200ms 有界观察内不得返回。释放 sink 后关闭完成，保持同 UUID，实际成长只提交一份。finally 必定释放私有挂起点，避免失败用例遗留 owner。
4. **正式创建确认必须同时有来源与活动 GAI。** Session 首次 load=null 触发 createStandardMatch；包装真实门面，一种调用真实 create 后抛确认丢失，真实 confirms=true 时允许继续；另一种只调用真实 save 后抛错误，GAI 虽完全相同但没有来源，真实 confirms=false，Session 必须 closed 封页。两种情形均核对真实库内容，无伪造确认布尔值。

## 隔离与边界

每情形独立 UUID 磁盘 Room；使用真实 Native 的双虚与双方确认终局，Pass 搜索仅为业务驱动，非 NN 或棋力验证。Session 有独立 SupervisorJob，所有控制在 Android Main dispatcher；关闭等待 Session worker，再取消父 scope，只删除本测试自建数据库，不清应用数据。

本切片没有运行 Gradle、NDK、ADB 或 Git；不包含视觉、正式路由、新局界面、模型性能、真实杀进程或备份退休测试。200ms 只验证已确认进入 sink 后关闭不能提前完成，不作为性能时限或启动时序猜测。后续统筹需编译并保存逐方法运行结果、APK 与源码身份，才能更新验收状态。
