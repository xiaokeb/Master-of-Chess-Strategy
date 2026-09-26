# 棋盘缩放与反馈验收

日期：2026-09-26。对应正式需求 2.2.3：双指缩放、落子反馈动画。

## 实现边界

共享棋盘将视图变换与规则状态分离：100%–250% 缩放、双指平移、内容边界钳制、
复位和无障碍操作只作用于绘制。点击使用同一变换的逆函数映射到 9×10 交叉点。
两指操作从第二指按下开始即排除单点走子；单指拖动交还页面滚动。

落子光圈根据相邻不可变棋盘的单子位移识别，持续 180 毫秒；吃子和普通走子
分别使用红、绿反馈。恢复、摆局多点变化、仅选择和盲棋不产生该动画。
它不驱动音效、存档、AI 或奖励；连续更新时旧动画取消，以最新快照为准。

## 本轮验证

| 范围 | 结果 |
| --- | --- |
| JVM 棋盘几何 | 3/3，原交叉点命中与空隙拒绝保持有效 |
| JVM 视图与反馈 | 5/5，三种宽高比/三个倍率的可见交叉点回算、缩放锚点、缩放和平移边界、非法输入、普通走子/吃子/重置区分 |
| AVD 棋盘交互 | 5/5，真实双指缩放和平移不走子、缩放后单击正确、静止双指不走子、复位、只读无障碍缩放、锁定中开始的点击不泄漏、页面单指滚动、动画结束/不重播 |
| AVD 横屏完整对局 | 1/1，在教程进入真实残局后双指放大，再以变换后的坐标落子，Native 判红胜、首通奖励保存、返回教程及重复进入均通过 |
| Android Lint | 0 错误、3 条既有依赖升级提醒 |
| 构建 | Debug APK、既有双 ABI 构建与内容/法律副本/NNUE/音效资产门禁通过 |

设备为 Pixel_7_Pro Android 17 x86_64 AVD；未改动 C++ 规则或 Python 工具，
没有重复运行它们的全量测试。仪器测试按类单独执行，核对报告类名与测试数，
不将多类筛选命令的成功提示视为全部执行证据。

横屏放大截图由完整链路测试写入 `/data/local/tmp/mocs-board-zoom.png`，
本地验看文件为 `.build/board-zoom.png`；画面中的边缘裁切是放大后的视窗，
可以双指平移查看边缘或点击复位。默认适配状态仍显示完整棋盘。

实体设备触控手感、TalkBack 朗读体验与长期性能仍待真机验证；本节点不代表
象棋全功能验收，尤其不改变待确认的自然限着口径。

## 复现

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.masterofchessstrategy.ui.ChineseChessBoard*Test'
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.ChineseChessBoardInteractionTest' --no-configuration-cache
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.TutorialEndgameNavigationTest' --no-configuration-cache
```

手势实现参考 Android 官方的[手势事件与消费说明](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)
和[多点触控说明](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch)。
本项目采用统一的触摸序列识别，以明确区分双指查看与单指行棋；不在同一
`pointerInput` 中顺序调用两个阻塞式顶层识别器。
