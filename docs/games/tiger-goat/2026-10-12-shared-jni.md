# 虎羊公共JNI集成进度

2026-10-12 Root串行修改公共文件；专属App16冻结不变。原生旧8冻结经专属授权修复OOM后，由 `native-freeze-20261012-oom.tsv` 的新9份取代。

## 已实现

- NativeEngineRegistry新增按棋种隔离的创建/查找/释放、强持有会话及单会话mutex；通用release覆盖虎羊，错误棋种释放不删其他句柄。
- 独立jni_tiger_goat_bridge覆盖静态几何、原子快照/合法列表、动作0–6、撤销/重置、MOCT读写。Java整数转换前检查、pending Java异常保留、分配失败映射OOM；restore长度检查/复制及原生准入失败不改状态。
- Kotlin默认真实桥、动作6与点位形状校验；新增停着安全门面单测。
- 正式CMake登记规则源与JNI；game和exception为可执行测试，topology只有static_assert而无main，登记OBJECT编译目标，不伪装可运行测试。
- 真实JNI测试源码5方法：开局/摆羊/虎步/悔棋/坏档、v1停着写v2与悔棋、连跳恢复/自愿停/认输、错/关闭句柄与未窄化Java整数、并发读取/释放。

## 当前证据

新增注册表测试先以缺create/find/release_tiger_goat接口失败，接口实现后严格NDK语法通过。Root使用NDK30、C++17、Wall/Wextra/Wpedantic/Werror对新JNI、注册表和测试做轻量语法检查；不调用MinGW。

已有Kotlin2.3.21/JDK17私有门面编译及JVM6/6通过，收据 `.build/tiger-goat-jni-20261012/jvm-tests.log`，SHA-256 `110e11b86237e50add25c85926d695cfc31e97065325c26ccca554f7024be008`；新设备5方法私有编译退出0且无诊断，故Tee未创建空compiler日志。实际编译class经javap复核5个方法，收据 `instrument-bytecode.txt`，尚未设备运行。专属App已有JVM16/16，但同样不能替代正式集成。

审查发现原生两份历史append在第二次分配失败时不一致；专属已统一record_action回滚，新增16例可控分配失败源码，两个独立二进制已链接，实际运行仍待。具体见 `2026-10-12-native-exception-safety.md`；不把旧12组设备收据套用到新二进制。

## 后续顺序

1. 保持原象棋AI会话75967独占AVD/大型CPU，当前不并发Gradle/设备测试。
2. 先补全公共备份对虎羊5行的版本化独立记录、完整导出/原生准入/事务恢复测试。现有RoomGameSessionRepository/ACTIVE只支持象棋，直接开放存档会阻断导出，不能默默过滤。
3. 用NavBackStackEntry正式ViewModel工厂接专属RoomStore/Native/Route，再开放兰州民间棋类中的虎羊本地双人入口；首页仍保持既有五卡结构。
4. 评测窗口释放后，正式双ABI/JVM/Lint/APK，原生12组及16次异常注入、注册表、真实JNI5、App页面2/Room1及生产导航/磁盘恢复/备份、普通/150%逐图验收。
5. 节点证据齐全后按单棋种发布门禁显式暂存、提交推送/tag/Release；当前公共源码增量不提交，已发布象棋音效tag不变。
