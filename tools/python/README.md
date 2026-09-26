# Python 离线工具

此目录后续承载残局解析、合法性校验、难度分级、压缩和索引生成工具。工具运行在 Conda 环境 MasterofChessStrategy 中，不进入 Android APK。

新增依赖时同步维护可复现环境文件和用途说明。生成格式必须服从 config/schemas 中的版本化规范。

当前使用标准库，无需安装额外依赖。运行：

```powershell
E:\Backend_Env\Python\miniconda3\envs\MasterofChessStrategy\python.exe -m unittest discover -s tools/python -p 'test_*.py' -v
E:\Backend_Env\Python\miniconda3\envs\MasterofChessStrategy\python.exe tools/python/audit_chinese_chess_endgames.py
```

审核器读取发布的象棋残局 v5，输出确定性 JSON 索引、数量和 SHA-256；拒绝重复棋盘、非法棋子数量，以及明显不可达的宫位、士象固定落点和未过河兵卒列位。它不是完整局面逆向可达性证明、规则求解器、难度分级器或授权审查器；发布前仍须运行 Android+NDK 主变化和唯一解测试。

`audit_xiangqi_ai.py <report.jsonl> [...]` 审核原生 AI 测量报告：配对开局、交换先后手、动作格式、步数上限、胜负分母和耗时样本计数。未完局不计入和棋；无已完成局时得分必须为 null。此工具只审核统计一致性，不证明棋局合法、棋力达标或授权有效。基线和复现方式见 `docs/testing/2026-09-26-chinese-chess-ai-calibration.md`。

当前正式 AI 已统一到 Pikafish；现行报告须记录 `backend=pikafish`、profile 版本和抽样种子，支持原六开局 suite 1 与独立十二开局 suite 2。旧本地搜索报告仅保留作历史对照；现行复现命令见 `docs/testing/2026-09-26-pikafish-profiles.md`。
