# 象棋残局逐题编排预览专项

日期：2026-10-02。范围仅限离线 Python 预览流程，不代表四道新题已完成 APK 内规则与棋盘验收。

## 结果

- `prepare_xiangqi_endgames.py` 读取明确编排的 `MOCS-XQ-REVIEW|1` 记录，锁定旧包和原始候选流 SHA-256，复用候选暂存器的局面、镜像及独立浅层全应手校验，再审计完整 v5 包。输出只允许写在仓库 `.build` 下，状态为 `prepared-not-published`；不会修改正式 APK 资源、内容清单或授权文件。
- 四题编排记录位于 `config/games/xiangqi/reviews/2026-10-02-rook-horse.json`。定向金样测试从现行 31 关包截取旧 27 关前缀，重建结果与现行文件逐字节一致；这只证明现有内容的可复现编排，不补足玩家难度或设备规则验收。
- 定向单测 `conda run -n MasterofChessStrategy python tools/python/test_prepare_xiangqi_endgames.py`：5/5。覆盖金样重建、过期包与原始流、缺失编排说明、非法路径/重复候选/章节及奖励、已入包重复题，以及 CLI 拒绝写正式资源。
- 全 Python 工具回归 `conda run -n MasterofChessStrategy python -m unittest discover -s tools/python -p 'test_*.py'`：70/70；正式 v5 资源 SHA-256 仍为 `a67710cabb464d08990422d7989f41128f6537be5b262045c96b2a39127dec61`。

## 未验收

四道新题的 APK 内规则/棋盘仪器专项仍被国标麻将专属测试的无效 Compose 导入阻断；用户决定由国标麻将对话修复，统筹/象棋对话不跨专属文件修改。预览工具没有新增或发布关卡，象棋已完整验收量仍按旧 27 关记录。正式扩大内容前仍需逐题质量判断、原生与 APK 内全防守证明、视觉及来源/权利核查。
