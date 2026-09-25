# 中国象棋残局包格式 v3

状态：已启用
字符集：UTF-8
正式资源：`app/src/main/assets/endgames/chinese_chess/endgames-v3.txt`

## 记录

首行固定为 `MOCS-XQ-ENDGAMES|3`，随后各有一条 `LICENSE` 与
`AUTHOR`。空行和以 `#` 开头的行会忽略。每关由 `LEVEL`、
2–32 条 `PIECE`、至少一条 `MOVE` 与 `END` 组成。

```text
MOCS-XQ-ENDGAMES|3
LICENSE|GPL-3.0-or-later
AUTHOR|Master of Chess Strategy contributors
LEVEL|xq-easy-003|0|3|双马合围|唯一一步杀|RED|1|1|10|6|BONUS
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|1|1|RED|HORSE
PIECE|7|1|RED|HORSE
PIECE|0|2|RED|CHARIOT
MOVE|0|2|4|2
END
```

`LEVEL` 字段依次为稳定 ID、难度码、章内序号、标题、主题、先行方、
玩家限步、首通星级、首通积分、声明棋子数和解锁轨道。轨道仅接受
`MAIN` 或 `BONUS`。难度码 0–3 对应简单、
中等、困难、大师；ID 格式 `xq-难度英文-章内序号` 且后缀必须与序号
一致。只接受红方先行。每档章内序号从 1 连续递增，主线必须排在
奖励支线之前。主线完成解锁下一难度；奖励支线在本档主线完成后
逐关开放，不反向锁住已开放的更高难度。

`PIECE` 字段为 x、y、阵营、类型；坐标限制为 x=0..8、y=0..9，
各交叉点不重复，双方各有一个将帅。`MOVE` 字段为起点 x/y 和终点
x/y；数量不超过 `玩家限步 × 2 - 1`。文本字段不能为空、超过 80
字符或包含竖线。包不超过 2 MiB、每档不超过 3000 关，玩家限步 1–100。

主题 `唯一一步杀` 必须由正式规则引擎穷举全部合法首着，恰有一着
一步红胜，且该着将军；`多解胜局` 明确允许多种通关着法，不得展示
为唯一解。其他主题按各自验收定义增加测试。

## 兼容与许可

MOCX v2 保存棋盘，残局内容版本独立。v1/v2 已完成关卡的稳定 ID、
首通奖励与进度保留；新关使用新 ID，不挪用已发布 ID。v2 活动残局
快照的会话变体与 v3 不同，进入关卡时从当前正确局面重新开始。

内容仅取自项目自编局面。导入第三方内容前必须逐批记录来源、作者、
许可与 APK 再分发依据；格式转换不改变原版权状态。来源记录见
`content/manifests/chinese-chess-endgames-v3.md`。
