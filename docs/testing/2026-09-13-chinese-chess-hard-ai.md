# 中国象棋困难 AI 节点测试记录

## 范围

- 三层极小极大、alpha-beta 剪枝、吃子排序、过河兵评价和 150,000 节点上限编译。
- C++ 简单、中等、困难合法着与搜索无副作用。
- JNI 与 Kotlin 对 HARD 的协议传递，以及对 MASTER 的拒绝。
- 15 个中等胜场后的困难卡片解锁、动态路由、对局状态和结算难度。

## 结果

| 项目 | 结果 |
| --- | --- |
| 全项目 JVM 单元测试 | 67 个通过，0 失败、0 错误、0 跳过 |
| C++ 中国象棋测试函数 | 13 个已编译进 ARM64 测试目标，未运行 |
| NativeBridge 三档 AI 测试 | 已编译进测试 APK，未运行 |
| 困难难度 Compose 选择测试 | 已编译进测试 APK，未运行 |
| App Lint | 0 错误；1 条锁定 Kotlin 版本提示 |
| Debug APK | 构建通过，31,021,913 字节 |
| App 测试 APK | 构建通过，1,313,691 字节 |
| engine-native 测试 APK | 构建通过，3,458,867 字节 |
| APK 权限 | 不包含 android.permission.INTERNET |

## 一致性结论

- CMake 明确使用 Android NDK 30 的 Clang/LLVM 工具链和 Android toolchain 文件，不调用 MinGW。
- 原生测试验证三档返回着均属于当前合法着集合，且搜索前后序列化状态不变。
- Kotlin 状态机验证 MEDIUM 与 HARD 原样传入搜索，AI 应答后仍轮到红方。
- 路由使用稳定编码 2 表示 HARD；未解锁困难和尚未实现的大师不能绕过入口校验。

## 设备限制

当前 adb 无连接设备，Android Emulator 无 AVD。困难搜索尚未取得真机耗时、节点统计和棋力样本；仪器测试只标记为编译、打包通过。
