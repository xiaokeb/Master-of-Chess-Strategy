# 中国象棋残局验证记录

日期：2026-09-19

## 自动验证范围

- 应用与引擎 JVM 单元测试：内容解析、局面编码、进度仓库、目录状态、导航、
  残局胜负及所有既有回归。
- Android 测试源码：Room 8→9 迁移、首页到残局目录导航和既有仪器测试。
- NDK：仅使用 `E:/Backend_Env/SDK/ndk/30.0.16138531` 的 LLVM 工具链，
  编译并链接 ARM64 C++ 测试目标；未调用 MinGW。
- Android 构建：Lint、Debug APK、App 测试 APK、engine-native 测试 APK、
  双 64 位 ABI 原生库、音效哈希、NNUE 哈希和法律文档一致性。

## 人工/设备待验

Android Studio 已提供“08 中国象棋残局”横屏预览。由于当前无 Android 设备
或 AVD，真实触摸流程、Room 迁移运行、JNI 规则执行、Pikafish 性能和五类音效
试听仍待设备环境；这些项目不计入已通过测试。

## 本次结果

- JVM：112 个测试通过，0 失败、0 错误、0 跳过。
- Lint：0 错误；1 条警告为项目主动锁定 Kotlin 2.3.21 的版本更新提示。
- NDK ARM64：新增残局 C++ 测试目标编译、链接通过；未在宿主机运行 Android ELF。
- 产物：Debug APK、App AndroidTest APK、engine-native AndroidTest APK 均构建成功。
- 离线清单：源码与合并清单均未出现 `android.permission.INTERNET`。
- 构建门禁：Pikafish NNUE 大小/SHA-256、五类 WAV 大小/SHA-256、APK 内法律
  文档与仓库权威副本一致性全部通过。
