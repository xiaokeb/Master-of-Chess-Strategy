# 象棋马炮删子审校与中等 12、13 关

日期：2026-10-04。项目原创候选流 `xiangqi-horse-cannon-two-move-seed-20261009.txt` 中第 23、74 题经显式删子编排为 `xq-medium-012`、`013`；分别删除原始局面 `(7,4)`、`(1,1)` 的装饰性红兵。来源流 SHA-256 `6410a99e4693f85bc45c6f2555917d50be8afa6f628e4fe1b1b7ee405b5340a9`，逐题转换记录在 `config/games/xiangqi/reviews/2026-10-04-horse-cannon-trimmed.json`。标题、奖励及转换由项目自行编排；不复制第三方棋谱、题名或媒体，沿用 GPL-3.0-or-later 项目内容许可，不改变 Pikafish/NNUE 的独立许可边界。

编排工具新增可选 `drop_pieces`：只接受原流中确有、未参与主变化的红方非将帅棋子；拒绝不存在、重复或走动子。删后重新验证合法摆位、主变化、唯一首着、无一步捷径、所有黑方应手和镜像去重，不能沿用原局证明。旧 33 关字节前缀 SHA-256 `4a352daed89ef6968c14bbb98aea9b71eb6c5dc8353ab94d1bd462ce48164d93` 不变；35 关正式工作树、Debug APK 条目和发布清单 SHA-256 均为 `1551ce2a61002326edf15b17f5c3ab1c3e1740b03aedd15148749f0630db6f95`。

## 已运行

- Conda `MasterofChessStrategy` Python 全套 92/92；历史 31、33 关前缀锁定，当前 35 关逐字节预览重建及删子拒绝用例通过。
- Android 受影响 JVM 类、内容/法律/音效/NNUE 门禁、双 ABI Debug APK 与 Lint 通过。
- Android NDK 30 LLVM x86_64 原生全包审计在 Pixel_7_Pro 模拟器输出 `release-pack levels=35 native-proof-nodes=17176`，退出码 0；APK 资源与源码同 SHA-256。
- Pixel_7_Pro Debug QA 入口从正式 APK 资源加载两张棋盘，初始截图已查看。第 12 关马 `(6,3)→(4,2)`，AI 将 `(3,2)→(3,1)`，马 `(6,1)→(5,3)` 后界面显示两步红胜。第 13 关马 `(3,4)→(2,2)` 后 AI 将 `(3,0)→(3,1)`，炮 `(4,1)→(5,1)` 后界面同样显示两步红胜；两关实际 AI 应手均不同于包内示例。
- 续验定向 JVM：目录 ViewModel 4/4、奖励仓库 5/5，JUnit XML 均无失败、错误或跳过。覆盖中等 10–13 关逐关解锁、首次奖励、重玩不增奖励、内存 DAO 上仓库重建后奖励幂等；新增 12、13 关以 0 或 3 步申报通关均拒绝且不写入奖励。命令：`gradlew.bat :app:testDebugUnitTest --tests 'com.masterofchessstrategy.endgame.ChineseChessEndgameViewModelTest' --tests 'com.masterofchessstrategy.data.RoomEndgameProgressRepositoryTest' --offline --max-workers=2 --console=plain`。该项不替代真实 Room 数据库或正式导航验收。

## 边界和待续

此前第 13 关最后触控被自动审批用量限制拒绝，未执行；审批恢复后发现模拟器已关闭，故恢复 Pixel_7_Pro 并重开本关完成上述实战，不声称恢复了无存档 QA 对局。没有清空应用数据。终局截图 `.build/visual/mocs-xq-medium-013-win.png` 已人工查看，补验应手截图为 `mocs-xq-medium-013-restarted-first.png`；此前第 12 关及初始棋盘证据保留。测试结束返回系统首页，旋转设置恢复为 `user_rotation=0`、`accelerometer_rotation=1`，释放 AVD 窗口。

旧App AndroidTest阻点后来已由国标麻将专属对话自行修复，统筹已复核编译通过，没有代删导入；不再作为当前阻点。正常解锁、真实 Room 奖励/冷启动、音效试听、玩家难度与实体设备尚未验收；完整验收量保守维持 27 关。后续实施见 `endgame-acceptance-next-node.md`，围棋共享窗口结束后串行执行。六棋种节点未冻结，未提交或推送。
