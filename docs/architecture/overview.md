# 工程架构

《棋术大师》采用模块化单仓库：

- app：Android 应用入口和 Jetpack Compose 界面。
- engine-api：Kotlin 引擎契约与跨层模型。
- engine-native：C++17 规则核心、AI 适配与 JNI。
- tools/python：残局解析、验证、分级和打包工具。

运行时数据流为 Compose → 业务状态 → Kotlin 引擎接口 → JNI → C++。Python 不进入 APK，只生成经过版本化格式校验的离线资源。

应用不声明联网权限。设置、进度、成长和棋谱将在业务切片中通过统一 Repository 持久化，规则引擎不直接依赖数据库。
