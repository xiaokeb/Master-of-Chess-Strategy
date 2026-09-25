# 中国象棋自然限着终局优先级回归

## 范围

- 连续 119 个未吃子着后，以不吃子的第 120 着将死对方，应判红胜。
- 同一终局经 MOCX 序列化和恢复后，仍应判红胜。
- 第 120 着后对方仍有合法着法的既有用例，应继续判自然限着和棋。

## 依据与边界

[《象棋竞赛规则（2020 版）》第 4 条](https://cnchess.net/rules/ChapterOneSection4.html)分别规定将死、困毙的胜负和连续 60 回合未吃子的自然限着和棋。本项目在两者同着满足时将胜负置于和棋之前；这是为解决规则碰撞所作的引擎优先级判断，并非声称条文逐字明确写了该优先级。

## 回归

- 修正前：新增 C++ 用例在 Android AVD 上触发 `game_result() == first_player_win` 断言失败。
- 修正后：原生 C++ 象棋规则目标在 Android AVD 上退出码为 0。
- Gradle 门禁 `BUILD SUCCESSFUL`：JVM 184/184，App AVD 46/46，JNI AVD 3/3，Debug APK 构建通过。
- App Lint：0 错误、3 条依赖版本更新提醒；`git diff --check` 无格式错误。
