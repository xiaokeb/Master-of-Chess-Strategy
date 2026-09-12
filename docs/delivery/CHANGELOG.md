# 变更记录

## 0.1.0 Foundation（2026-09-12）

### 已实现

- 建立 app、engine-api 和 engine-native 三模块 Android 工程，统一 JDK 17、SDK 37、NDK 30.0.16138531、CMake 4.1.2 与 C++17。
- 定义 Kotlin 公共游戏模型、稳定枚举编码和 RuleEngine 生命周期契约。
- 定义平台无关的 C++ RuleEngine 契约与健康检查。
- 建立 Kotlin 安全门面、JNI 异常边界和 mocs_engine_native 动态库。
- 建立固定横屏的单 Activity Compose 工程壳，原生库不可用时展示稳定诊断而不崩溃。
- 保持纯离线边界，应用未声明 INTERNET 权限。

### 验证结果

- 8 个 JVM 单元测试通过：engine-api 3 个、engine-native 3 个、app 2 个。
- NDK Clang 21 已编译并链接 Android ARM64 原生测试目标；Gradle 已构建 arm64-v8a、armeabi-v7a、x86 和 x86_64 动态库。
- engine-native Debug 测试 APK 已成功编译，因当前无设备或 AVD，未运行。
- Gradle Lint 为 0 error、1 warning；唯一警告是项目按计划锁定 Kotlin 2.3.21，而仓库存在更新版本。
- Debug APK 构建成功，大小为 15,469,712 字节；aapt 权限检查不包含 android.permission.INTERNET。

### 未包含

本节点不包含具体棋种规则、AI 引擎、正式首页业务、持久化、残局数据和正式视觉资源。这些内容按后续业务切片增量实现。
