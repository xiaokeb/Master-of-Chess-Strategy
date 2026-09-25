# Python 离线工具

此目录后续承载残局解析、合法性校验、难度分级、压缩和索引生成工具。工具运行在 Conda 环境 MasterofChessStrategy 中，不进入 Android APK。

新增依赖时同步维护可复现环境文件和用途说明。生成格式必须服从 config/schemas 中的版本化规范。

当前使用标准库，无需安装额外依赖。运行：

```powershell
E:\Backend_Env\Python\miniconda3\envs\MasterofChessStrategy\python.exe -m unittest discover -s tools/python -p 'test_*.py' -v
E:\Backend_Env\Python\miniconda3\envs\MasterofChessStrategy\python.exe tools/python/audit_chinese_chess_endgames.py
```

审核器读取发布的象棋残局 v4，输出确定性 JSON 索引、数量和 SHA-256；拒绝重复棋盘、非法棋子数量，以及明显不可达的宫位、士象固定落点和未过河兵卒列位。它不是完整局面逆向可达性证明、规则求解器、难度分级器或授权审查器；发布前仍须运行 Android+NDK 主变化和唯一解测试。
