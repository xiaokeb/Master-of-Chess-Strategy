# 开发进度

更新时间：2026-09-12

## Foundation 节点

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| 仓库与目录治理 | 完成 | 原始需求已归档，源码、配置、内容、工具与文档边界已建立 |
| Gradle 多模块 | 完成 | app、engine-api、engine-native 均可解析和构建 |
| Kotlin 引擎契约 | 完成 | 公共类型、稳定编码、规则接口及 3 个测试已落地 |
| C++ 核心契约 | 完成 | 指定 NDK LLVM 编译、链接原生库和 ARM64 测试目标 |
| JNI 安全门面 | 完成 | 健康检查、固定错误映射、3 个 JVM 测试和测试 APK 已落地 |
| Compose 工程壳 | 完成 | 固定横屏、40/60 分栏、国风简约主题及 2 个状态测试 |
| 文档与构建门禁 | 完成 | Lint、Debug APK、权限与测试结果已记录 |
| 中国象棋规则纵切 | 完成 | 基础规则、句柄表、12 个 JNI 接口与 Kotlin 安全门面已贯通 |
| 中国象棋对局 UI | 完成 | 不可变棋盘状态、ViewModel、选子、合法落点、走子、悔棋与重开已接入 |
| 首页与模式导航 | 完成 | 五张入口卡、模式页、难度条件页和对局返回栈已落地 |

## 已验证

- JVM 单元测试：27 个通过，0 失败、0 跳过。
- NDK：Clang 21，C++17，Android ARM64 测试目标编译和链接通过。
- Gradle NDK：arm64-v8a、armeabi-v7a、x86、x86_64 构建通过。
- Android：App Lint 0 error、1 个锁定版本更新提示；Debug APK、App 测试 APK 与 engine-native 测试 APK 构建通过。
- 离线边界：Debug APK 不包含 android.permission.INTERNET。
- JNI：ARM64 动态库已导出并核对 12 个 NativeBindings 符号。

## 条件限制

当前没有 Android 设备或 AVD。NativeBridgeInstrumentedTest 仅完成编译，尚未运行；该项不会标记为测试通过。C++ 构建由 CMake 强制要求 Android NDK 工具链，不接受 MinGW 或其他宿主机工具链。

中国象棋基础规则和句柄表测试目标已完成 NDK ARM64 编译和链接，但受相同设备条件限制尚未运行。当前不能据此宣称规则用例运行通过。真实 JNI 集成测试已编译进 Android 测试 APK。

## 下一节点

1. 设计本地对局自动保存与异常恢复，沿用 MOCX 版本边界并明确恢复失败路径。
2. 保存上次棋种、模式与难度，为首页长按快速进入建立可信数据。
3. 在设备或 AVD 可用后运行 Compose、中国象棋与 NativeBridge 集成测试。
4. 单独设计长将、长捉、长拦和自然限着历史判定，不与基础走法混写。
