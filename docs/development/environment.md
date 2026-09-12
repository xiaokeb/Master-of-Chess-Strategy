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

本机尚未发现 Android 设备或 AVD。设备集成测试先保证可编译，获得设备后再执行，不得将未执行的测试标记为通过。

## C++

Android 构建使用 NDK Clang 21 和 C++17。宿主机快速测试使用 E:\Backend_Env\CPP\MinGW；CMake 来自 Android SDK。路径只记录在本文档和本机配置中，不写入可移植的 Gradle 源文件。

## Python

离线工具使用 Conda 环境 MasterofChessStrategy，当前为 Python 3.13.15。Conda 列表中的大小写名称指向同一个 Windows 目录。NumPy、Pandas 和 pytest 当前未安装；只有首个 Python 功能需要它们时才安装并记录依赖。

## Node.js

Node.js 24.14.0 已安装。当前工程底座没有 Node 运行时或构建依赖。

## 验证原则

开发中运行受影响模块的测试。每个大节点结束后统一运行 C++ 测试、Android 单元测试、Lint 和 Debug 构建，避免重复执行相同的全量检查。
