# 本地数据备份与恢复测试记录

日期：2026-09-19

## 已运行

- JVM：版本化格式完整往返、Unicode 标识、SHA-256 篡改检测、重复主键、
  非法模式/变体，以及 ViewModel 精确导出、成功后刷新、I/O 失败不刷新。
- 全项目 JVM 单元测试累计 118 个通过，0 失败、0 错误、0 跳过。
- Android 测试源码编译通过：Room 内存数据库用例覆盖七类数据的事务替换、
  引擎状态验证与损坏文件在写事务前拒绝；Compose 用例覆盖两个显式按钮。

## 完整门禁

- App Lint：0 错误；仅保留锁定 Kotlin 2.3.21 的版本更新提醒。
- Debug 主 APK、App 测试 APK和 engine-native 测试 APK构建通过。
- 指定 NDK 30 LLVM 的 ARM64 C++ 目标重建通过；Ninja 无待处理工作，不调用
  MinGW。
- Debug 主 APK权限清单只有 Android 自动生成的非导出动态接收器权限，不含
  INTERNET 或存储权限。
- 主 APK：100,893,034 字节，SHA-256
  1ce3ea0d11214d7d5879940fdd321cc673957a8053e514ca9e245266cf3cfc01。
- 备份功能不改变 Room schema，当前仍为 schema 9。

## 未运行边界

当前 ADB 没有设备或 AVD，Room/Compose 仪器用例与系统文档选择器尚未在
Android 运行。它们的当前状态是“编译通过”，不是“设备测试通过”。连接设备后
还需实际完成导出、覆盖恢复、取消、无权限 URI 和损坏文件交互验收。
