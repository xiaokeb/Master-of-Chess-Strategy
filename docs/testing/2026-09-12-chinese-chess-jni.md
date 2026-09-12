# 中国象棋 JNI 节点测试记录

日期：2026-09-12

## 验证范围

- engine-api 中国象棋稳定编码、棋盘边界和公共接口。
- NativeChineseChessEngine 的协议映射、句柄生命周期与关闭行为。
- C++ 中国象棋核心、句柄表和 JNI 动态库。
- Android 四 ABI、测试 APK、应用单元测试、Lint 和 Debug APK。

## 结果

| 检查 | 结果 |
| --- | --- |
| JVM 单元测试 | 14 个通过，0 失败、0 跳过 |
| NDK ARM64 严格编译 | 通过，使用 -Wall、-Wextra、-Wpedantic，无警告 |
| Gradle NDK ABI | arm64-v8a、armeabi-v7a、x86 和 x86_64 均构建通过 |
| JNI 导出 | llvm-nm 确认 12 个 NativeBindings 符号 |
| Android 测试 APK | 构建通过 |
| Lint | engine-api、engine-native 无问题；app 0 error、1 个锁定版本提示 |
| Debug APK | 构建通过 |

## 未执行

当前 adb 未发现设备或 AVD，因此 NativeBridgeInstrumentedTest、中国象棋 C++ 测试可执行目标均未在 Android 运行。报告只声明编译和链接通过。

## 关键命令

1. CMake 使用 SDK 4.1.2、NDK android.toolchain.cmake、arm64-v8a、android-24 和 MOCS_BUILD_TESTS=ON 配置后执行 cmake --build。
2. Gradle 执行 engine-api 与 engine-native 单元测试、engine-native 测试 APK、app 单元测试、lintDebug 和 assembleDebug。
3. 使用指定 NDK 的 llvm-nm 检查 libmocs_engine_native.so 导出符号。
