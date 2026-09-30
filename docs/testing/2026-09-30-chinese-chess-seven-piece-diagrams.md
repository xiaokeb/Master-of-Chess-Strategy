# 中国象棋七类棋子图解回归

日期：2026-09-30。教程第二步沿用正式棋盘的只读绘制，提供车、马、炮、相、士、将帅、兵卒七类切换。车展示直线落点，马展示蹩马腿，炮展示隔架吃子，相展示塞象眼，士和将帅展示九宫边界，过河兵展示前进及左右横走。图解不调用规则引擎落子，也不改变教程检查点或解锁记录。

## 验证

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --max-workers=2`：App JVM 243/243、0 失败/错误/跳过；Lint 0 错误、3 条既有依赖版本提醒；双 ABI Debug APK 构建与内置许可、权重、音效、内容门禁通过。
- `ChineseChessTutorialPracticeTest`：Pixel_7_Pro AVD 2/2；七个选项均能切换到对应文字，首尾两幅肖像截图已实际查看，棋子、落点和安全区正常。
- `TutorialEndgameNavigationTest#piecesLessonShowsSelectedDiagramInLandscapeAppNavigation`：同一 AVD 1/1；从首页进入正式模式与教程，先滚入横向选项行再点“兵卒”，说明文字断言通过。横屏截图实际显示过河红兵、三个绿色落点、说明和后续练习按钮，未见棋盘裁切或底部手势栏覆盖正文。
- 截图保存在本地 `.build/visual/mocs-tutorial-chariot.png`、`mocs-tutorial-soldier.png`、`mocs-tutorial-pieces-landscape.png`；测试报告不把模拟器结果写成实体设备验收。

## 调试记录与剩余边界

首次全 App JVM 运行因自然限着测试使用真实时钟而相差 1 ms，改为注入固定时钟后 243/243 通过；生产计时逻辑未改。横屏新增测试最初对屏外横向按钮执行点击，导致仍显示默认车图解；给按钮行独立测试标签并先将整行纵向滚入可见区后，真实点击与画面均通过。该失败属于测试手势定位，不将其记为生产交互修复。

当前只验证了选项、文字与首尾/横屏画面；七幅图的无障碍朗读、不同字体大小和实体设备触控仍待专项验收。AI、残局内容量及 Pikafish 授权边界未因本节点改变。
