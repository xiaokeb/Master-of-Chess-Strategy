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

## 已验证

- JVM 单元测试：8 个通过。
- NDK：Clang 21，C++17，Android ARM64 测试目标编译和链接通过。
- Gradle NDK：arm64-v8a、armeabi-v7a、x86、x86_64 构建通过。
- Android：Lint 0 error，Debug APK 与 engine-native 测试 APK 构建通过。
- 离线边界：Debug APK 不包含 android.permission.INTERNET。

## 条件限制

当前没有 Android 设备或 AVD。NativeBridgeInstrumentedTest 仅完成编译，尚未运行；该项不会标记为测试通过。C++ 构建由 CMake 强制要求 Android NDK 工具链，不接受 MinGW 或其他宿主机工具链。

## 下一节点

1. 从原始需求拆分正式 BRD、PRD、页面交互与任务分级文档，减少后续实现对长文档的重复读取。
2. 定义首个棋种切片的规则状态、动作与序列化协议。
3. 以中国象棋为首个纵向切片，实现棋盘状态、基础合法性和 Kotlin/JNI 调用，不同时铺开六种棋类。
