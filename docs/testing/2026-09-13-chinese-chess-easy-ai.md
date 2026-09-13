# 中国象棋简单 AI 节点测试记录

## 范围

- C++ 简单 AI 候选生成、合法性、确定性选择和搜索无副作用。
- Kotlin AI 能力协议、畸形数组与未实现难度拒绝。
- 人类落子、后台 AI 应答、交互禁用、双检查点存档和整回合悔棋。
- 教程解锁后的难度点击、带 EASY 的快速进入及未解锁防绕过。

## 结果

| 项目 | 结果 |
| --- | --- |
| 全项目 JVM 单元测试 | 59 个通过，0 失败、0 错误、0 跳过 |
| C++ 中国象棋测试函数 | 13 个已编译进 ARM64 测试目标，未运行 |
| NativeBridge AI 仪器测试 | 已编译进测试 APK，未运行 |
| App AI/导航 Compose 仪器测试 | 已编译进测试 APK，未运行 |
| App Lint | 0 issue |
| Debug APK | 构建通过，30,471,169 字节 |
| App 测试 APK | 构建通过，1,309,656 字节 |
| engine-native 测试 APK | 构建通过，3,425,907 字节 |
| APK 权限 | 不包含 android.permission.INTERNET |

## NDK 与协议核对

- 直接 CMake 构建使用 Android NDK 30.0.16138531 工具链、CMake 4.1.2、Ninja 和 android-24。
- Gradle 完成 arm64-v8a、armeabi-v7a、x86 和 x86_64 构建。
- llvm-readobj 确认直接构建产物为 elf64-littleaarch64、EM_AARCH64。
- llvm-nm 确认 NativeBindings_chineseChessBestMove 已作为动态 JNI 符号导出。
- 全程未调用 MinGW。

## 一致性结论

- C++ 测试断言 AI 返回当前合法着，并在选择前后保持序列化局面完全一致。
- Kotlin 门面只接受 0 或 4 个整数的 AI 返回结构，拒绝畸形数据。
- AI 状态机测试确认思考期间不可交互、AI 完成后轮到红方，并且一次悔棋撤销完整双方回合。
- 人类待应答快照与 AI 完整回合快照顺序提交，最终快照包含 HUMAN_VS_AI 与 EASY。
- Debug 构建提示原生调试库未剥离符号并按原样打包；该提示不影响调试 APK 生成。

## 设备限制

当前 adb 无连接设备，Android Emulator 无 AVD。C++ Android 可执行目标和两类仪器测试均已编译、链接或打包，但不能在当前机器执行，因此不标记为运行通过。
