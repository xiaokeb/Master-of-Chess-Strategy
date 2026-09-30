# Python 离线工具

此目录后续承载残局解析、合法性校验、难度分级、压缩和索引生成工具。工具运行在 Conda 环境 MasterofChessStrategy 中，不进入 Android APK。

新增依赖时同步维护可复现环境文件和用途说明。生成格式必须服从 config/schemas 中的版本化规范。

当前使用标准库，无需安装额外依赖。运行：

```powershell
E:\Backend_Env\Python\miniconda3\envs\MasterofChessStrategy\python.exe -m unittest discover -s tools/python -p 'test_*.py' -v
E:\Backend_Env\Python\miniconda3\envs\MasterofChessStrategy\python.exe tools/python/audit_chinese_chess_endgames.py
```

审核器读取发布的象棋残局 v5，输出确定性 JSON 索引、数量和 SHA-256；拒绝重复棋盘、非法棋子数量，以及明显不可达的宫位、士象固定落点和未过河兵卒列位。它不是完整局面逆向可达性证明、规则求解器、难度分级器或授权审查器；发布前仍须运行 Android+NDK 主变化和唯一解测试。

`stage_xiangqi_candidates.py <native-stdout.txt>` 用于暂存原生残局生成器输出：核对批次种子、尝试顺序、FEN/摆子、证明标记和左右镜像去重，并与当前发布包比较；再用独立 Python 浅层规则穷举首着和全部防守应手。它只打印 `staged-not-published` JSON，不修改 APK；主机端通过仍不能代替正式 NDK 规则运行与人工来源审核。生成器默认种子 20260925，亦可将 `uint32` 种子作为第四个位置参数，以准备独立批次。

`verify_xiangqi_forced_win.py [endgames-v5.txt]` 对现有一、两步主题做独立主机端复核；不覆盖循环棋例、自然限着或更深搜索。其范围和结果见 `docs/testing/2026-09-30-chinese-chess-host-oracle.md`。

`audit_xiangqi_ai.py <report.jsonl> [...]` 审核原生 AI 测量报告：配对开局、交换先后手、动作格式、步数上限、胜负分母和耗时样本计数。未完局不计入和棋；无已完成局时得分必须为 null。此工具只审核统计一致性，不证明棋局合法、棋力达标或授权有效。基线和复现方式见 `docs/testing/2026-09-26-chinese-chess-ai-calibration.md`。

当前正式 AI 已统一到 Pikafish；现行报告须记录 `backend=pikafish`、profile 版本和抽样种子，支持原六开局 suite 1 与独立十二开局 suite 2。旧本地搜索报告仅保留作历史对照；现行复现命令见 `docs/testing/2026-09-26-pikafish-profiles.md`。
