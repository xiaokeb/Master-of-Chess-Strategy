# 虎羊棋存储代次与备份协调交接

日期：2026-10-31。范围：既有本地双人存储的安全前置，不改变棋规、MOCT 格式或 AI 能力。

## 本次实现

- `app/src/main/kotlin/com/masterofchessstrategy/games/tiger_goat/data/TigerGoatStorageEpochGate.kt`：每个数据库对象身份对应同一弱引用缓存门禁。读写和完整备份操作串行；页面身份在创建时固定，包含门禁身份及代次，不能靠重试刷新。恢复取得门禁后先增加代次，恢复失败也会退休旧身份；尚在排队时取消恢复不增加代次。相同协程、结构化子协程及同类门禁嵌套回到祖先门禁均立即拒绝重入，避免自己等待自己。
- `GatedTigerGoatSessionStore.kt`：包装既有 `TigerGoatSessionStore`，不另设存储。加载返回独立字节；保存先复制输入再等待门禁。旧身份的加载、保存都抛出 `IOException`，不调用底层存储。暴露只读代次和固定 `ownerEpoch`，供宿主识别退休。
- `TigerGoatCoordinatedBackupRepository.kt`：包装完整 `LocalDataBackupRepository`。导出保留代次；恢复先复制输入，等待在途操作完成后退休旧身份并调用原恢复。原始异常与恢复统计透传。数据库事务及其失败回滚仍由既有委托负责，本包装不假装提供事务。

旧发布文件、10-22 AI search owner、规则和共享入口均未改。B 型开局及已确认逐羊摆放、正交跳吃和连跳链禁重访规则保持原状；本切片不引入新规则或授权资源。

## 验证证据

私有输出：`.build/tiger-goat-storage-owner-20261031/r1/`，复现脚本为其父目录 `check.ps1`。使用现有 JDK 17、缓存 Kotlin 2.3.21、coroutines 1.11.0 与实际 App 编译快照 `.build/fqrk3/source/` 的现有内部契约，未创建生产替身、未使用 MinGW，未执行共享 Gradle、ADB 或 Git。

- `compile.log`：新增三个生产文件及专属测试编译退出 0。
- `junit.log`：`TigerGoatStorageOwnershipTest`，15/15 通过，退出 0。
- `result.json`：记录输入 SHA-256、编译/测试结果及检查前后源码未变。

覆盖数据库身份隔离、外来身份拒绝、失败恢复退休且异常不变、在途写入先完成及后续旧写入拒绝、排队取消、重入与子协程拒绝、载入/排队保存字节隔离、Missing/Incompatible 保留、旧存储不续期、保存失败不确认、导出不退休、排队恢复输入隔离及原统计返回。失败恢复的底层测试委托不提交数据；真实 Room 回滚、Native 及设备行为未在本次验证，不能用这组 JVM 测试替代。

## 申请统筹串行接入

1. 在实际数据库创建处取得 `TigerGoatStorageEpochGate.forDatabase(database)`；相同数据库的本地存储和完整备份包装必须复用这个实例。页面/写入宿主创建时捕获一次 Owner，包装现有 `RoomTigerGoatSessionStore`。禁止在保存重试时重建包装或刷新身份。
2. 公共备份工厂把此包装放在完整导出/恢复外围、Room 事务外；由统筹统一确定全部棋种锁序，检查不存在反向调用。不要在单个虎羊备份适配器里二次取得同门禁，也不要在门禁内部调用已包装的完整备份入口。
3. 旧 `TigerGoatGameViewModel` 尚未观察代次。接入时必须在代次变化后停用输入、取消并等待既有 AI owner 的工作结束，停止/排空旧写入，再关闭旧 Native，允许新宿主从存储重新加载；恢复失败也遵循退休流程。门禁只能阻止后续存储调用，不能自动撤回已经返回的载入结果或 UI 回调，宿主发布结果前仍须核对固定代次。
4. 共享入口接线后补真实 Room 事务、跨棋种完整备份、恢复失败、页面退出及真实杀进程/恢复的专项与模拟器检查，再判断此闭包是否达到发布节点。

本次不修改数据库、公共契约、导航、JNI/CMake、构建文件、全局进度或旧冻结文件；不开放 App AI，不宣称等级/提示额度、AI 保存确认、自动演局或设备验收完成。
