# 象棋马炮两步题增量：中等 10、11 关

日期：2026-10-03。两关来自项目生成流 `xiangqi-horse-cannon-two-move-seed-20261009.txt` 的第 24、73 题，编排记录为 `config/games/xiangqi/reviews/2026-10-03-horse-cannon-curated.json`。题名和奖励由项目编排；不使用第三方棋谱、题名或媒体。旧 31 关前缀 SHA-256 `523522889b77a27b6d98e809b9da12da2b170cbd294e6f7952fbb63136ed0875` 不变，现行 33 关资源及发布清单 SHA-256 均为 `4a352daed89ef6968c14bbb98aea9b71eb6c5dc8353ab94d1bd462ce48164d93`。左右镜像等价后为 32 个独立局面。

## 已运行的验证

- Conda `MasterofChessStrategy`：Python 全套 88/88；旧 31 关前缀、逐题原始流字段、预览重建、红炮辅助必要性、无一步捷径和所有黑方应手通过。
- Android JVM 受影响两类 13/13；内容/许可/音效/NNUE 门禁、双 ABI Debug APK 与 Lint 均通过。APK 中正式资源与源码同 SHA-256。
- 后续目录 ViewModel JVM 回归确认中等 10、11 关按顺序解锁、各首次完成发 1 星/20 分，重复完成第 10 关不重复发奖；定向类 4/4 通过。这是目录业务单测，不代替 Room 或正式导航的设备端验收。
- 仓库层 JVM 回归用内存 DAO 保留旧版第 1 关记录，再写入新第 10、11 关并重建仓库；旧记录仍可读，新题仅首次发奖，累计积分与星级吻合。定向类 4/4 通过；内存 DAO 不证明真实 Room 数据库迁移或冷启动。
- Android NDK 30 LLVM x86_64 原生全包审计在 Pixel_7_Pro 模拟器运行：`release-pack levels=33 native-proof-nodes=15434`，退出码 0；设备资源 SHA-256 同上。测试对两步题穷举所有合法首着、黑方应手及获胜后着，主变化不是唯一依据。
- Debug 专用无 Room QA 入口从 APK 正式资源解析两关，初始棋盘截图已人工查看。`xq-medium-010` 马 `(5,5)→(4,3)`、AI 将 `(3,1)→(3,2)`、炮 `(8,3)→(8,2)`，界面显示两步红胜；`xq-medium-011` 兵 `(5,0)→(4,0)`、AI 士 `(4,1)→(5,0)`、炮 `(7,5)→(7,1)`，亦显示两步红胜。后一黑方应手并非资源示例。截图在本机忽略目录 `.build/visual/mocs-xq-medium-010-debug-qa.png`、`mocs-xq-medium-010-win.png`、`mocs-xq-medium-011-debug-qa.png`、`mocs-xq-medium-011-win.png`。

## 未验收边界

App AndroidTest 编译仍被国标麻将专属 `CompetitionMahjongTableScreenTest.kt` 第 4、5 行无效 Compose 导入阻断；按用户决定由国标麻将对话修复，本对话不跨文件编辑。正常线性解锁、奖励/存档、音效试听、所有 AI 应手的界面体验及真实玩家难度/审美仍未完整验收；Debug QA 入口不写正常进度。第 11 关棋子较少，暂不把“中等”标签当经玩家验证的难度结论。两关当前只计工作树与模拟器可玩增量，不增加保守的完整验收量 27 关。实体设备未测试。六棋种统一节点未冻结，未提交或推送。
