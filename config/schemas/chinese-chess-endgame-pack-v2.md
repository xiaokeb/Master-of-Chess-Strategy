# 中国象棋残局包格式 v2

状态：已启用  
字符集：UTF-8  
正式资源：`app/src/main/assets/endgames/chinese_chess/endgames-v2.txt`

## 记录顺序

文件以 `MOCS-XQ-ENDGAMES|2` 开始，随后各出现一次 `LICENSE` 与
`AUTHOR`。空行和以 `#` 开头的整行注释会被忽略。每关由一条
`LEVEL`、若干 `PIECE`、至少一条 `MOVE` 和一条 `END` 组成。

```text
MOCS-XQ-ENDGAMES|2
LICENSE|GPL-3.0-or-later
AUTHOR|Master of Chess Strategy contributors
LEVEL|xq-easy-001|0|1|双马锁宫|一步杀|RED|1|1|10|7
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|3|1|RED|CHARIOT
PIECE|0|1|RED|CHARIOT
PIECE|2|2|RED|HORSE
PIECE|6|2|RED|HORSE
MOVE|3|1|4|1
END
```

## 字段

- `LEVEL`：稳定 ID、难度编码、章内序号、标题、主题、先行方、玩家限步、
  首通星级、首通积分、棋子数。
- 难度编码固定为 `0/1/2/3`，依次对应简单、中等、困难、大师。
- 稳定 ID 使用 `xq-难度英文-章内序号`，难度和数字后缀必须与字段一致；
  v2 只接受红方先行、由玩家执红求胜的关卡。
- `PIECE`：横坐标、纵坐标、阵营、棋子类型。坐标范围为 `x=0..8`、
  `y=0..9`；每个交叉点至多一子，双方必须各有且仅有一个将帅。
- `MOVE`：解题主变化的一步，依次为起点和终点坐标。v2 至少保存一步，
  总记录数不得超过 `玩家限步 × 2 - 1`。
- 标题、主题、作者等文本不得为空、包含分隔符或超过 80 个字符。

## 完整性与运行时边界

解析器限制单包不超过 2 MiB、每档不超过 3000 关、每关 2–32 子、玩家
限步 1–100。局面先编码为带 CRC32 的 MOCX v2，再由原生规则引擎执行
恢复和合法着校验；Android UI 不直接构造或持有原生对象。

稳定关卡 ID 是进度主键。不得将已发布 ID 静默改为不同任务；错误局面的
修正须提升内容版本、保存来源与验证记录，并明确旧进度和活动会话策略。
v1→v2 保留既有通关奖励，v1 活动残局会话按内容版本失效并重开正确局面。
章内序号从 1 连续递增。

## 内容许可

当前种子关卡由项目自行编制，随项目使用 GPL-3.0-or-later。后续导入任何
外部残局前，必须逐批记录来源、作者、许可证和再分发依据；来源不明、仅能
在线浏览、禁止再分发或许可证不兼容的棋谱不得进入 APK。格式转换不改变
原始内容的版权状态。
