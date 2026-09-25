# 开发环境

## Android

| 项目 | 当前基线 |
| --- | --- |
| JDK | Temurin 17.0.12 |
| Android SDK | E:\Backend_Env\SDK |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |
| Build Tools | 36.0.0 |
| Android NDK | 30.0.16138531 |
| CMake | 4.1.2 |
| Gradle Wrapper | 9.6.0 |
| Android Gradle Plugin | 9.4.0 |
| Compose Navigation | 2.10.1 |
| AndroidX Lifecycle | 2.11.0 |
| Room / KSP | 2.8.5 / 2.3.12 |
| Coroutines Test | 1.11.0 |

2026-09-25 已确认 API 37.1 x86_64 系统镜像和 Pixel_7_Pro AVD。可启动模拟器执行仪器测试；实体设备音频和性能结果仍需另行验收。

本机未安装 cmdline-tools/latest/bin/apkanalyzer。当前使用 E:\Backend_Env\SDK\build-tools\36.0.0\aapt.exe 检查 APK 权限；该工具已确认 Debug APK 不包含 android.permission.INTERNET。

## C++

所有 C++ 构建统一使用 NDK 30.0.16138531 自带的 LLVM/Clang 21 和 C++17，不调用 MinGW。工具目录为 E:\Backend_Env\SDK\ndk\30.0.16138531\toolchains\llvm\prebuilt\windows-x86_64\bin，CMake 来自 Android SDK。

自 Pikafish 2026-09-06 大师引擎节点起，正式 ABI 收敛为 arm64-v8a 与
x86_64。该上游版本固定使用 128 位整数棋盘，不能为 Android 32 位
目标生成完整功能库；历史节点记录的四 ABI 结果仍代表接入前的规则
引擎状态。

已验证 aarch64-linux-android24-clang++ 能生成 Android ARM64 目标。NDK 的通用 clang++ 不提供 Windows 宿主链接环境；x86_64 原生测试目标可由 NDK 编译后在 AVD 执行，ARM64 目标继续仅验证编译和链接。

当前 PATH 未解析到 clang++、cmake、ninja 或 g++，不存在隐式工具链覆盖。Gradle 通过 local.properties 定位 Android SDK，通过 ndkVersion 锁定 NDK；手工原生验证使用上述 SDK 内 CMake、Ninja 和 NDK toolchain 的绝对路径。local.properties 与机器绝对路径不提交到构建源码。

## Python

离线工具使用 Conda 环境 MasterofChessStrategy，当前为 Python 3.13.15。Conda 列表中的大小写名称指向同一个 Windows 目录。NumPy、Pandas 和 pytest 当前未安装；只有首个 Python 功能需要它们时才安装并记录依赖。

## Node.js

Node.js 24.14.0 已安装。当前工程底座没有 Node 运行时或构建依赖。

## 验证原则

开发中运行受影响模块的测试。每个大节点结束后统一运行 C++ Android 目标编译、Android 单元测试、Lint、Debug 构建与 AVD 集成测试；避免重复执行相同的全量检查。

常用门禁：

1. 原生目标：使用 SDK CMake 配置 .build/native-android，指定 NDK android.toolchain.cmake、arm64-v8a、android-24 和 MOCS_BUILD_TESTS=ON，再执行 cmake --build。
2. Android：运行 gradlew :engine-api:testDebugUnitTest :engine-native:testDebugUnitTest :app:testDebugUnitTest lintDebug assembleDebug。
3. 设备测试：启动 Pixel_7_Pro AVD 后运行 gradlew :app:connectedDebugAndroidTest :engine-native:connectedDebugAndroidTest。
