# 中国象棋对局 UI 节点测试记录

## 范围

- 不可变棋盘 UI 状态与 ChineseChessGameViewModel。
- 9×10 交叉点坐标换算、选中态和合法落点。
- 本地双人走子、悔棋、重开及原生异常降级。
- 横屏 Compose 对局页、禁用能力边界与 Android 产物。

## 结果

| 项目 | 结果 |
| --- | --- |
| 全项目 JVM 单元测试 | 23 个通过，0 失败、0 跳过 |
| App 新增 JVM 测试 | 9 个通过 |
| Compose 仪器测试 | 已编译进 App 测试 APK，未运行 |
| App Lint | 0 error，1 个锁定 Kotlin 版本更新提示 |
| Debug APK | 构建通过，19,988,380 字节 |
| App 测试 APK | 构建通过，1,095,122 字节 |
| APK 权限 | 不包含 android.permission.INTERNET |

## 环境与边界

本节点没有修改 C++ 或 JNI，沿用前一节点已通过的 NDK 30.0.16138531、Clang 21 和四 ABI 构建结果；没有调用 MinGW。当前 adb 没有设备或 AVD，因此 ChineseChessGameScreenTest 只声明编译通过，不能声明运行通过。

当前唯一 Lint 提示为仓库已有的 Kotlin 2.3.21 锁定版本存在更新版。依赖升级需要独立兼容性验证，不在本节点顺带修改。

## 执行摘要

1. App JVM 测试验证初始快照、选子、合法走子、换方、错误反馈、悔棋、重开和引擎不可用状态。
2. 纯坐标测试验证交叉点中心命中、格线中点拒绝和棋盘外点击拒绝。
3. Android 测试源码与测试 APK 编译验证棋盘、悔棋、重开和未接入 AI 控件的语义边界。
4. aapt 读取 Debug APK 权限，只出现 Android 动态接收器内部权限。
