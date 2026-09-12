# 中国象棋切片 1 实施计划

目标：在 engine-native 中交付可由统一 RuleEngine 调用的中国象棋基础规则核心，不扩展 AI 或正式 UI。

## 接口

- ChineseChessEngine 实现 RuleEngine。
- 坐标按 chinese-chess-rules.md 定义。
- EngineAction kind=0 表示棋盘移动，arguments 依次为 fromX、fromY、toX、toY。
- PieceType 和 Side 使用显式稳定编码。
- 二进制局面使用 MOCX 魔数、版本 1、当前方和 90 个交叉点编码。

## 实施顺序

1. 建立棋子、阵营、棋盘和初始摆放。
2. 实现七类棋子的伪合法走法与路径阻挡。
3. 在临时局面上验证自陷将、应将和将帅照面。
4. 实现 apply、undo、legal_actions 和基础胜负判定。
5. 实现局面序列化、输入校验和恢复。
6. 编写初始局面、兵、马、炮、自陷将、悔棋和序列化测试。
7. 使用指定 NDK LLVM 编译并链接 Android ARM64 测试目标；设备可用后运行。

## 完成条件

- C++17 编译无警告级错误，公共头文件不包含 JNI 类型。
- 基础规则测试目标可由 NDK 编译、链接。
- Gradle 四 ABI 原生构建和 Android JVM 回归测试通过。
- 未实现的竞赛历史规则在接口和文档中明确，不以简化逻辑冒充完成。
