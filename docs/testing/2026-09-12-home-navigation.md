# 首页与模式导航节点测试记录

## 范围

- 五张首页棋种入口卡及六类 GameType 覆盖。
- 中国象棋模式分流、本地对局入口和 AI 难度条件入口。
- 四档难度的锁定状态及产品解锁条件。
- Compose Navigation 返回栈、对局 ViewModel 目的地作用域和页面返回入口。

## 结果

| 项目 | 结果 |
| --- | --- |
| 全项目 JVM 单元测试 | 27 个通过，0 失败、0 跳过 |
| 新增导航模型测试 | 4 个通过 |
| 导航 Compose 仪器测试 | 已编译进测试 APK，未运行 |
| App Lint | 0 error，1 个锁定 Kotlin 版本更新提示 |
| Debug APK | 构建通过，20,372,866 字节 |
| App 测试 APK | 构建通过，1,096,138 字节 |
| APK 权限 | 不包含 android.permission.INTERNET |

## 兼容性与边界

- navigation-compose 2.10.1 与 lifecycle 2.11.0 已通过当前 AGP 9.4.0、Kotlin 2.3.21、Compose BOM 2026.08.00 和 minSdk 24 构建。
- 当前无 Android 设备或 AVD，因此 AppNavigationScreenTest 和对局仪器测试只记录编译通过。
- 围棋、麻将、AI、残局和教程入口均未冒充完成；没有可执行能力的卡片不可导航。
- 本节点没有修改 C++/JNI，未调用 MinGW，沿用上一原生节点的 NDK 验证结果。

## 执行摘要

1. JVM 测试验证首页恰有五张卡且完整覆盖六类游戏，中国象棋是唯一开放棋种。
2. JVM 测试验证本地双人进入对局、普通人机进入难度设置，其余模式保持锁定。
3. Android 测试源码验证首页可进入模式页和难度条件页，过程不创建原生对局会话。
4. Lint、Debug APK、测试 APK 和 aapt 权限检查全部完成。
