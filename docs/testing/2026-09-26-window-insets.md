# 系统栏与横屏安全区验收

日期：2026-09-26。设备：Pixel_7_Pro Android 17 x86_64 AVD；应用 minSdk 24、targetSdk 37。

## 问题与修正

连续演局截图显示浅色页面上仍使用白色系统状态栏图标。旧 XML 主题固定关闭浅色状态栏，
导航根部也没有消费系统栏/刘海边距，按钮和内容可能进入系统 UI 区域。

- 正式 MainActivity 使用 `enableEdgeToEdge()`，图标随系统昼夜配置切换；不隐藏系统栏。
- 根部 `MocsAppWindow` 绘制主题背景，统一消费 `safeDrawingPadding`，所有导航目的地和加载页共享安全区。
  页面不重复消费边距；增加 `adjustResize`，保留系统输入法边距支持。
- 启动窗口同步项目昼夜配色。导航图标 XML 属性仅放在 API 27 资源层，API 24–26 保留可读的旧版导航背景。
  删除失去引用的四个模板颜色，不新增权限或依赖。
- 安全区减少横屏可用高度后，首页末行说明出现压缩裁切；右侧棋种列表改为独立滚动，保持文字字号和卡片内容。
  窄屏继续原页面整体滚动，不嵌套两个同方向的无限高度滚动容器。

设计依据为 Android 官方的[边到边设置](https://developer.android.com/develop/ui/compose/system/setup-e2e)
和[窗口边距处理](https://developer.android.com/develop/ui/compose/system/insets-ui)。

## 设备证据

| 专项 | 结果与范围 |
| --- | --- |
| AppWindowInsetsTest：窗口矩阵 | 1/1；浅/深主题 × 手势/三键导航四组合，首页/对局/设置/返回及显式 Activity 重建 |
| AppWindowInsetsTest：首页与截图 | 1/1；末行说明可滚动显示，浅/深实际背景和手势条像素检查，截图稳定后输出 |
| AppNavigationScreenTest | 22/22；首页、设置、模式、许可与既有导航回归 |
| ChineseChessBoardInteractionTest | 5/5；双指缩放/平移、点击、页面滚动与反馈 |
| TutorialEndgameNavigationTest | 1/1；安全区内真实放大落子、Native 通关、奖励及返回教程 |
| Lint | 0 错误、3 条既有依赖升级提醒 |
| 构建门禁 | 两 ABI APK/测试 APK、法律副本、NNUE、音效、内容门禁通过 |

合计 30 个不同设备用例通过，重复运行不重复计数。窗口矩阵比对系统真实 WindowInsets 和 Compose
内容边界（允许 1 像素取整差异），核对图标明暗标志；重建后仍为同一棋局 ViewModel，存档不变。
测试只在模拟器变更昼夜和导航方式，结束后恢复原设置（本机为昼间、手势导航）。

导航/手势/教程回归在根安全区接入后执行；追加首页滚动后重跑窗口类，随后只对截图同步增强用例复验。
未修改引擎、存储或 AI，因此未重复 JVM、C++/JNI、迁移、连续演局和冷启动全量。
XML 和图片位于本地 `.build/window-insets/`，包括 `window-test.xml`、`catalog-screenshots-test.xml`、
`navigation-test.xml`、棋盘/教程类 XML，以及 `home.png`、`game-light.png`、`game-dark.png`。
图中对局操作区仍按设计纵向滚动，没有为了截图缩小文字或隐藏操作。

## 过程中发现的问题

- 初次 Lint 指出导航图标属性要求 API 27，已改为版本化主题，没有抬高最低 SDK 或压制错误。
- 新用例漏导入 Compose 的 `onLast` 扩展，补齐后编译通过。
- 初版截图捕获了昼夜切换的旧 Activity 过渡画面：配置/语义已更新不代表屏幕合成完成。
  增加实际背景像素检查后，又发现 SystemUI 手势条颜色稍晚更新；补充其亮度检查后才保存截图。
  最终深色截图的状态栏和底部手势条均为浅色，浅色截图则使用深色图标。

## 剩余边界与复现

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.masterofchessstrategy.ui.AppWindowInsetsTest' :app:lintDebug --no-configuration-cache --console=plain
```

其他类按表中名称单独执行。API 24–26 本轮只经资源分层和 Lint 检查，未宣称实际旧设备运行通过。
不同厂商设备、自由窗口、大字号及输入法交互仍需独立兼容性验收。本节点不改变规则口径或 Pikafish/NNUE 许可。
