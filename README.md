# Master of Chess Strategy

Master of Chess Strategy 是离线多棋种策略训练 Android 项目。当前正式开发顺序为中国象棋、围棋、国标麻将、兰州麻将与兰州特色棋；中国象棋完成并通过对应测试后才进入下一棋种。

## 当前状态

中国象棋已具备标准棋盘、基础与重复局面规则、教程、四级 AI、计时、提和、
自动演局、持久化、正式音效、棋谱、残局章节/解锁/奖励，以及完整本地数据
备份恢复。扩展玩法已开放自由摆局与限时挑战；后者提供每步 10/30/60 秒
独立倒计时、超时判负，并复用正式 AI、存档、音效和棋谱链路。当前残局资源是 5 关项目自编功能
种子包，正在继续扩充经许可验证的内容并开发其余扩展玩法。进度与尚未满足的设备测试边界见
docs/development/progress.md。

## 构建环境

- Android Studio / Gradle Wrapper
- JDK 17
- Android NDK 30 LLVM；本项目不支持 MinGW
- CMake 4.1.2 与 Ninja

Debug 构建：

    .\gradlew.bat :app:assembleDebug

构建会校验 Pikafish NNUE、音效资源和 APK 内法律文档的一致性。

## 许可

项目整体按 GNU GPL 3.0 或更高版本发布，完整条款见 LICENSE。项目直接链接修改后的 Pikafish 源码，固定版本、作者与修改记录位于 third_party/pikafish。

内置 Pikafish NNUE 权重受独立条款约束：仅限合法用途，未经权利人许可不得商用。分发边界与发布检查见 NOTICE.md 和 docs/legal/distribution-checklist.md。
