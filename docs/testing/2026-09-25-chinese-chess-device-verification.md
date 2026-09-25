# 中国象棋设备验证与内容修正（2026-09-25）

## 环境与结果

| 门禁 | 结果 |
| --- | --- |
| Android 模拟器 | Pixel_7_Pro，API 37.1，x86_64 |
| JVM 单元测试 | 182/182 通过（app 164、engine-api 5、engine-native 13） |
| App 仪器测试 | 44/44 通过，含 Room 迁移、Compose 页面、定式、残局和 Pikafish 大师走子 |
| JNI 仪器测试 | 3/3 通过，含规则引擎序列化、AI 合法着和联合捉 |
| C++ 规则目标 | NDK LLVM x86_64 构建后在 AVD 执行，退出码 0 |
| 构建与 Lint | Debug APK 成功；Lint 0 错误、3 条锁定依赖版本升级提醒 |

Gradle 门禁：`gradlew :engine-api:testDebugUnitTest :engine-native:testDebugUnitTest :app:testDebugUnitTest :app:connectedDebugAndroidTest :engine-native:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug`。
原生 C++ 目标使用 SDK CMake 与 NDK LLVM 构建，再通过 ADB 推送至
`/data/local/tmp` 运行；不调用 MinGW。App 的依赖文件、音效文件和 Pikafish
NNUE 哈希校验随构建执行。

## 本轮发现并修正

1. 旧残局包有三关实际不能一步将死。v2 更正防逃棋子位置，逐关由发布资产经 Native 引擎验证；稳定 ID 与首通奖励保留，旧活动残局会话按版本失效后从正确局面重开。
2. 2020 规则第 24.11 的长捉可交替追捉数子；不再错误要求每着都捉同一枚棋子。联合捉测试改用真实合法回吃和净得子局面，等价交换保持和棋。
3. Room 2.8.5 迁移测试的生成序列化器需要 `GeneratedSerializer` 的 JVM 默认方法；显式统一 `kotlinx-serialization` 1.8.1 后九项迁移设备测试通过。
4. 定式“马二进三”的起点误写为车位，已更正并提升内容版本；Compose 过渡期间零尺寸棋盘不再触发绘图前置条件。
5. Pikafish 直接 `Engine::go` 未设置搜索起始时间，导致大师测试远超 1.2 秒预算；现在设置与上游 UCI 相同的时钟起点。
6. `engine-native` 原先缺少仪器测试运行器依赖，Gradle 曾把进程启动崩溃显示为“0 测试成功”；补齐后实测 3/3。

## 发布与剩余边界

项目与直接链接的 Pikafish 按 GPL-3.0-or-later 提供源码、许可、作者和修改记录；构建验证 APK 内法律副本与仓库一致。NNUE 权重有独立使用条款，本项目不把它重新授权为 GPL；当前权重仅用于合法个人非商业场景。商业分发前需取得权利人书面许可或更换兼容权重，详见
`docs/legal/distribution-checklist.md`。本报告不构成法律意见。

模拟器测试不代替实体设备音效试听、触控手感和长局性能验收。中国象棋职业棋例边界及完整内容规模仍在开发，不宣称整个棋种或其他棋种已完成。

规则依据：[象棋竞赛规则（2020 版）第 24 条](https://cnchess.net/rules/ChapterSixSection24.html)、
[第 26 条](https://cnchess.net/rules/ChapterSixSection26.html)。
