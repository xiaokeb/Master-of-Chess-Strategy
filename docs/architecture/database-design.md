# 本地数据库设计

当前 Room schema 为 12，数据库文件为 master-of-chess-strategy.db。全部表
仅保存离线功能数据，所有版本升级使用显式 Migration，不允许破坏性回退。

| 表 | 主键 | 用途 |
| --- | --- | --- |
| active_games | gameTypeCode | 活动对局、玩家执子方、引擎状态、棋钟、控制状态与玩法变体 |
| last_game_selections | gameTypeCode | 每棋种上次模式和难度，用于可信快速进入 |
| app_settings | id=0 | 默认难度、AI 先手、自动续局、音效、时长、人物选择与精彩局条件 |
| tutorial_progress | gameTypeCode | 分棋种、带内容版本的教程检查点 |
| match_outcomes | matchId | 已完成排位人机对局幂等账本 |
| game_records | recordId | 终局棋谱、玩家执子方、收藏、分类与回放状态 |
| endgame_progress | levelId | 残局首通奖励、最佳步数和内容版本 |

## 一致性边界

- 对局结算以 matchId 去重，段位、成就和分棋种统计从账本派生。
- 残局奖励以 levelId 去重，重复通关只允许改善最佳步数。
- 设置采用单行整行写入，保存失败回滚内存状态。
- 活动状态和棋谱 BLOB 均由当前规则引擎验证后才可从备份恢复。
- 本地备份恢复在单个 Room 事务中先完成全部格式、内容和引擎校验，再替换
  七张表，避免半恢复状态。

## 迁移记录

1–9 的迁移分别引入快速进入、设置、教程、对局账本、控制状态、棋钟与
自动演局、棋谱、残局进度及玩法变体。9→10 仅向 app_settings 增加非空
selectedAppearanceCode，默认值为 0，既有用户数据不变。10→11 增加非空
highlightConditionsMask，默认 0，即既有用户的精彩局自动收藏保持关闭。

11→12 增加 app_settings.aiFirstEnabled（默认关闭），以及 active_games 和
game_records 的 playerIndex（默认 0，即历史玩家执红）。活动会话信封从 v4
迁移到 v5，棋谱绝对结果与 MOCX 2 字节保持原样；不靠当前设置推断历史身份。
