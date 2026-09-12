# 活动对局持久化节点测试记录

## 范围

- Room active_games 表与 schema 1 导出。
- 版本化会话信封、稳定枚举编码和 BLOB 防御性复制。
- 中国象棋进入时恢复、成功动作后保存及忙碌态冲突保护。
- 不兼容、损坏、读取失败和保存失败的可恢复路径。

## 结果

| 项目 | 结果 |
| --- | --- |
| 全项目 JVM 单元测试 | 34 个通过，0 失败、0 跳过 |
| 新增 Repository 测试 | 3 个通过 |
| 新增 ViewModel 持久化测试 | 4 个通过 |
| Room DAO 仪器测试 | 已编译进测试 APK，未运行 |
| Room schema | 版本 1 已生成并核对 |
| App Lint | 0 error，1 个锁定 Kotlin 版本更新提示 |
| Debug APK | 构建通过，20,647,707 字节 |
| App 测试 APK | 构建通过，1,270,764 字节 |
| APK 权限 | 不包含 android.permission.INTERNET |

## 一致性结论

- SQLite 写入由 Room 事务保证原子性；保存进行时页面禁用走子、悔棋、重开和返回，避免 ViewModel 提前释放。
- 读取与写入均复制引擎字节；调用方修改数组不会改变数据库请求或恢复结果。
- 信封版本、MOCX 版本、模式编码、难度编码和状态大小任一不合法均返回 Incompatible。
- MOCX 内容合法性继续由 NativeChineseChessEngine.restore 判断，Repository 不复制规则。
- 恢复后不伪造原生撤销历史，悔棋从恢复后的新走子开始计数。

## 设备限制

当前没有 Android 设备或 AVD，ActiveGameDaoInstrumentedTest 及其他仪器测试只完成编译。Room、Compose 和 JNI 的真实设备运行仍列为待验证，不标记为已运行。

本节点未修改 C++，没有调用 MinGW；Gradle 四 ABI 原生产物随 APK 构建成功。
