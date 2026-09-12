# 工程架构

《棋术大师》采用模块化单仓库：

- app：Android 应用入口和 Jetpack Compose 界面。
- engine-api：Kotlin 引擎契约与跨层模型。
- engine-native：C++17 规则核心、受控 JNI 入口和 Kotlin 安全门面。
- tools/python：残局解析、验证、分级和打包工具。

当前运行时分为两条单向数据流：Compose → NavHost → ChineseChessGameViewModel → engine-api → engine-native → JNI → C++ 规则核心；以及 ViewModel → GameSessionRepository → Room。Python 不进入 APK，只生成经过版本化格式校验的离线资源。

应用不声明联网权限。活动对局已经通过统一 Repository 和 Room 持久化；后续设置、成长和棋谱继续复用该边界，规则引擎不直接依赖数据库。

## 依赖方向

- app 依赖 engine-api 和 engine-native。
- engine-api 不依赖应用、Android UI 或 JNI。
- engine-native 提供 app 可调用的安全状态接口；JNI 声明保持 internal。
- C++ 核心不包含 JNI 类型，JNI 桥只负责边界转换和异常收敛。
- Room 只保存版本化会话信封和引擎字节，不复制棋类规则或解析 JNI 句柄。

依赖不得反向。新的棋种规则优先进入独立 C++ 实现，通过 engine-api 暴露稳定模型，不在 Compose 层复制规则。
