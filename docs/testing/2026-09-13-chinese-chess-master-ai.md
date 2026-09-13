# 中国象棋大师 AI 节点测试记录

## 范围

- Pikafish 2026-09-06 源码以 Android NDK 静态库接入。
- 标准中国象棋 FEN、UCI 坐标转换和权威合法着复核。
- NNUE 资产大小、SHA-256、首次复制和原子发布。
- 20 个困难胜场后的大师解锁、动态路由及 MASTER 协议传递。
- arm64-v8a、x86_64 构建及纯离线权限边界。

## 结果

| 项目 | 结果 |
| --- | --- |
| 全项目 JVM 单元测试 | 77 个通过，0 失败、0 错误、0 跳过 |
| Native C++ 测试目标 | ARM64 编译、链接通过，未在设备运行 |
| 大师 AI 仪器测试 | 已编译进 App 测试 APK，未运行 |
| App Lint | 0 issue |
| Debug APK | 构建通过，包含两个 64 位 ABI |
| App/engine-native 测试 APK | 构建通过 |
| NNUE SHA-256 | 7D13D73569A9B571BA0EB20CF1596247BC2A42738967E61AFEF6482B231E900E |
| APK 权限 | 不包含 android.permission.INTERNET |

## 一致性结论

- CMake 使用 Android NDK 30 Clang/LLVM；未配置或调用 MinGW。
- arm64-v8a 与 x86_64 均包含 libmocs_engine_native.so，APK 不再夹带
  可能造成不完整安装的 32 位 ABI。
- 主程序、GPL 正文、开源说明、网络许可和 NNUE 均已进入 APK。
- 大师搜索在后台执行，固定 1,200 ms；原生返回必须属于规则引擎生成
  的当前合法着集合。
- 权重缺失、不兼容或搜索失败转为可恢复异常，不终止 Android 进程。

## 设备限制

当前 adb 无连接设备，Android Emulator 无 AVD。真实 JNI 调用、首载时延、
搜索耗时、内存峰值和长期稳定性尚未实机测量，不能标记为设备测试通过。
