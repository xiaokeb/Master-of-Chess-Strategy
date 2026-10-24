# 项目文档

本目录保存《棋术大师》的正式需求、架构、接口、开发、测试和交付文档。

- [象棋预算v2原生准入验收](games/xiangqi/2026-10-24-budget-v2-admission-verification.md)：`d64dedd`／`v0.1.0-xiangqi-budget-v2-admission.1`／[源码 Release](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-xiangqi-budget-v2-admission.1)，干净源码71测试、实际NDK41对象/45命令及最终ELF模拟器12项身份自检通过；无搜索/新棋力结论，无二进制附件。

- [围棋正式AI入口验收](games/go/2026-10-24-ai-entry-release-verification.md)：`141ed92`／`v0.1.0-go-ai-entry.1`／[源码 Release](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ai-entry.1)，141同源JVM、74不同设备方法／107调用、双字号64图核验；真实模型应答与Activity重建已验，强度／内容／实体设备仍待。

- [围棋正式会话事务验收](games/go/2026-10-23-formal-session-release-verification.md)：`5a05da7`／`v0.1.0-go-formal-session.1`／[源码 Release](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-formal-session.1)，103 JVM、32不同设备方法／34调用与双字号4图核验；正式AI入口与重试界面待集成。

- [围棋正式新局／首次成长数据验收](games/go/2026-10-23-ranked-growth-release-verification.md)：`8b5f6b0`／`v0.1.0-go-ranked-growth-data.1`／[源码 Release](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-ranked-growth-data.1)，110 JVM、59不同设备方法／63调用与双字号16图核验；正式AI入口仍待。

- requirements/：原始需求和逐步拆分的规格。
- architecture/：系统、引擎和数据架构。
- interfaces/：Kotlin、C++、JNI 和业务接口。
- development/：环境、编码规范和二次开发说明。
- development/chat-coordination.md：多棋种对话分工、阶段线与共享文件协调。
- testing/：测试策略与测试报告。
- delivery/：变更日志、用户手册和发布资料。
- prompts/：按开发场景整理的提示词索引。
- superpowers/：已经确认的历史设计与实施计划。

文档必须描述已经确认或已经实现的内容。发生设计变更时，先更新对应正式文档，再同步代码。
