# 围棋生产存储切换：完整数据包接线

## 范围与前置

本切片依赖已发布的 AI 原始 Room/备份与可等待资源清退契约。这里只新增围棋专属构造器、四源准备器及测试；不修改生产工厂、导航、数据库 schema、公共注册表、完成 sink 或成长账本。生产切换和旧 AI 磁盘保护撤除须由统筹冻结实际接线并在真实 Room/备份上验收后进行，不能以本切片的源码编译代替。

`GoCompleteStorageBundle` 同时构造同一数据库的 epoch gate、AI Room checkpoint 仓库、四旧源准备器，以及完整五适配器列表（本地活动、唯一共享 Go 棋谱、进度、AI 活动、AI 原始归档）。`backupRepository(createDelegate)` 把完整列表传给主机创建全棋种备份注册表，不再向主机暴露独立旧三件备份路径。AI 每次工厂调用新建 gated facade，捕获当前 epoch；不能在长期 bundle 内缓存旧 gated 页面仓库。

## 事务与旧源边界

- 导出准备在 gate 内建立单一外层 Room 事务，按本地局、教学、残局、AI 依次 load，四套仓库的嵌套事务加入同一事务。第四源校验失败或取消，前三源行和全部迁移标记一起回滚。最终父引用检查与取消检查在事务成功标记前执行。
- 迁移成功后，实际快照/编码仍走既有备份仓库，整个准备与导出期间持有同一 gate。若之后编码/容量校验失败，可以保留已完整迁移的四源；不声称备份失败必然撤销成功迁移，也不允许前三源单独成功。
- 恢复准备不读、不迁入旧数据；有效备份可以替换旧损坏文件。实际替换沿用公共 Room 事务：清理、四个 scope 永久封口、写入及最终父引用校验全部原子进行；空 AI 备份也不得从旧磁盘复活。失败/取消恢复依然使旧页面 epoch 过期，但数据库按原事务回滚。
- 仍使用现有只读旧源策略：已提交 `.bak` 优先，其次主文件，孤立 `.new` 不提升；不调用 AtomicFile 恢复，不改写或删除任何旧文件。新构造器没有磁盘清理代码。`GO_LOCAL_RECORD` 继续独占共享 `game_records`，AI 原始归档不重复投影，不授予排名资格。

## 统筹接线建议

以同一 `GoCompleteStorageBundle(database, context)` 替代 GoLocalStorage 中旧 AI 工厂与旧备份准备路径，`gate` 委托 bundle、`aiRepository()` 委托 bundle。原本地/教学/残局页面工厂可继续使用同 DB gate。

GoLocalStorage 的备份方法改为接受 `createDelegate` 回调，委托 `bundle.backupRepository(createDelegate)`。唯一 App 调用点在回调内部创建 `RoomLocalDataBackupRepository` 与全棋种 `GameBackupRegistry`，把回调收到的五个 Go adapters 与其他棋种 adapters 合并。不要保留 `backupAdapters()` 三适配器接口，也不要只切换 AI 仓库而沿用 `GoLocalBackupPreparation` 的旧 guard。旧实现文件不必删除，历史/保护回归仍可保留。

## 新增验证

`GoCompleteStorageBundleInstrumentedTest` 共8个方法，全部使用 UUID 专属磁盘 Room 数据库及独立临时源文件：

1. 四源导出包含 AI 活动及首次原始终局，坏主文件由有效 `.bak` 优先，`.new` 不提升，所有源字节不变。
2. 无效第四源导致前三源和四封口回滚。
3. 在第四源读取点观察前三源已写入，注入取消后验证原子回滚，随后连接仍可成功迁移。
4. GAI 外层校验合法但 Native 历史损坏仍拒绝并回滚前三源。
5. 实际迁入 AI 后恢复空 AI 备份，真实关闭/重开数据库，确认四封口持久化、旧源不复活、重新导出无 AI 行。
6. 有效空备份可替换全部旧损坏源；旧 AI facade 失效、新 facade 可读取空状态，旧字节不变。
7. 无效备份不读取/迁入任何旧源、不创建封口，旧页面仍因 epoch 变化而拒绝读写。
8. 取消恢复验证完整五适配器交付、数据库/封口无迁移及旧 epoch 失效；该方法取消注入位于 delegate，不冒充实际 SQL 提交中断。

私有检查 `.build/go-complete-storage/compile-r3/` 使用既有 gal5 编译类作为 friend 与既有缓存依赖，编译上述两个生产新文件及8个设备方法源码成功，单 CPU/512MiB，输入前后散列一致；未运行设备、Gradle、NDK 或 Git。r1 是诊断驱动 classpath 反斜杠错误导致的编译失败，日志保留；r2 是此前6方法版本源码编译，不能混用为8方法运行证据。正式接线、构建、设备实跑与发布由统筹继续，完成前不宣称生产已切换或围棋 AI 已可玩。
