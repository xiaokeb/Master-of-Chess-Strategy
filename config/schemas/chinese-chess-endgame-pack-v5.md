# 中国象棋残局包格式 v5

状态：当前发布格式。资源为 `app/src/main/assets/endgames/chinese_chess/endgames-v5.txt`，UTF-8，最大 2 MiB。

非空、非注释首三条依次为 `MOCS-XQ-ENDGAMES|5`、`LICENSE|GPL-3.0-or-later`、`AUTHOR|作者`。每关依次为 `LEVEL`、2–32 条 `PIECE`、至少一条 `MOVE`、`END`，不接受未知或乱序记录。

`LEVEL` 记录稳定 ID、难度码 0–3、章内连续序号、标题、主题、先行方 `RED`、玩家限步、星级、积分、棋子数和 `MAIN` / `BONUS`。主线必须排在同章奖励关前。坐标 x=0..8、y=0..9；双方各一将帅，不得照面，棋子数量和固定宫位、士象、未过河兵卒落点均须合法。`MOVE` 为主变化，不能超过 `玩家限步 × 2 - 1`；主题“唯一一步杀”还须穷举全部合法首着，验证恰有一个立即红胜着。

v5 保留 v4 十关的 ID、摆位、主变化与奖励，新增四关项目自编简单奖励题 `xq-easy-005` 至 `008`。旧完成记录依稳定 ID 保留；旧活动残局因会话变体升级，在进入时从 v5 局面重新开始。Python 审核负责结构和明显不可达落点；Android+NDK 测试负责规则和唯一性；两者都不替代人工难度、趣味性及授权验收。

来源、生成方法和发布许可见 `content/manifests/chinese-chess-endgames-v5.md`。第三方棋谱或解说未经逐项核实再分发权不得并入包。
