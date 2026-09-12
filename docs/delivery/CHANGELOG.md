# 变更记录

## 未发布：中国象棋可操作纵切

- 增加中国象棋 9×10 初始局面、七类棋子基础走法、路径阻挡和轮次管理。
- 拒绝越界、己方占位、蹩马腿、塞象眼、错误炮架、未过河横兵、自陷将和将帅照面。
- 增加悔棋、合法走法生成、基础困毙胜负和 MOCX 版本 1 局面往返。
- NDK Clang 21 在 -Wall、-Wextra、-Wpedantic 下编译并链接 ARM64 原生测试目标，无警告。
- Gradle 已完成 arm64-v8a、armeabi-v7a、x86 和 x86_64 构建；测试目标因无设备或 AVD 尚未运行。
- engine-api 新增中国象棋阵营、棋子、棋盘边界和 ChineseChessRuleEngine。
- engine-native 新增 NativeChineseChessEngine、可替换 bridge 与受互斥锁保护的原生句柄表。
- JNI 已覆盖创建、释放、当前方、重置、走子、悔棋、合法着、胜负、棋盘查询和序列化往返。
- Kotlin JVM 测试覆盖稳定编码、错误映射、句柄传递、幂等释放、关闭后拒绝调用及畸形数组。
- App 新增不可变 90 点棋盘状态与 ChineseChessGameViewModel，由 ViewModel 独占并释放原生会话。
- Compose 对局页新增横屏自适应棋盘、楚河汉界、棋子、选中态、合法落点、行棋方与终局反馈。
- 本地双人对局已贯通选子、走子、悔棋和重开；AI、提示、计时和历史判定明确保持未接入。
- 新增 6 个 ViewModel 测试、3 个棋盘坐标测试和 1 个已编译的 Compose 仪器测试。
- App Lint 为 0 error、1 个锁定版本提示；Debug APK 与测试 APK 构建通过，权限仍不含 INTERNET。
- 新增正式 Compose Navigation 返回栈：首页 → 中国象棋模式 → 本地对局，以及普通人机 → 难度条件。
- 首页按 40/60 分栏展示本地初始档案和五张棋种入口卡，尚未实现的棋种保持不可进入。
- 模式页明确区分当前可用、本节点仅可查看条件和后续切片三类状态；四档 AI 难度按 PRD 条件锁定。
- Navigation 2.10.1 与 Lifecycle 2.11.0 已锁定并通过兼容性构建。
- 当前 JVM 单元测试累计 27 个，全部通过。

## 0.1.0 Foundation（2026-09-12）

### 已实现

- 建立 app、engine-api 和 engine-native 三模块 Android 工程，统一 JDK 17、SDK 37、NDK 30.0.16138531、CMake 4.1.2 与 C++17。
- 定义 Kotlin 公共游戏模型、稳定枚举编码和 RuleEngine 生命周期契约。
- 定义平台无关的 C++ RuleEngine 契约与健康检查。
- 建立 Kotlin 安全门面、JNI 异常边界和 mocs_engine_native 动态库。
- 建立固定横屏的单 Activity Compose 工程壳，原生库不可用时展示稳定诊断而不崩溃。
- 保持纯离线边界，应用未声明 INTERNET 权限。

### 验证结果

- 8 个 JVM 单元测试通过：engine-api 3 个、engine-native 3 个、app 2 个。
- NDK Clang 21 已编译并链接 Android ARM64 原生测试目标；Gradle 已构建 arm64-v8a、armeabi-v7a、x86 和 x86_64 动态库。
- engine-native Debug 测试 APK 已成功编译，因当前无设备或 AVD，未运行。
- Gradle Lint 为 0 error、1 warning；唯一警告是项目按计划锁定 Kotlin 2.3.21，而仓库存在更新版本。
- Debug APK 构建成功，大小为 15,469,712 字节；aapt 权限检查不包含 android.permission.INTERNET。

### 未包含

本节点不包含具体棋种规则、AI 引擎、正式首页业务、持久化、残局数据和正式视觉资源。这些内容按后续业务切片增量实现。
