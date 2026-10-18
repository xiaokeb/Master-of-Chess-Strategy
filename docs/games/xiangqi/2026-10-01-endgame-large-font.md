# 象棋残局目录：横屏大字体可达性

日期：2026-10-01。设备：Pixel_7_Pro x86_64 AVD，横屏，系统字体从原值 100% 暂调至 150%，测试结束已恢复并核验为 100%。仅改象棋专属目录布局与专属 UI 测试；不改通用首页、导航或其他棋种。

实际安装版在“主题”模式多出第三排筛选项时，左侧固定列将进度摘要挤到可视区域外；修改前的专项测试因摘要没有可滚动父节点而失败。现在左侧标题、筛选与摘要共用可纵向滚动的列，右侧关卡网格保持独立滚动。150% 实装截图中，滑动左列后可完整看到 `总进度 0 / 27`、星级与积分；筛选项和关卡卡片未被覆盖。证据仅存本机 `.build/visual/mocs-xiangqi-font150-theme-before.png`、`mocs-xiangqi-font150-theme-after.png` 和 `mocs-xiangqi-font150-theme-scrolled.png`。

- 红灯：新增 `progressSummaryRemainsReachableWithThemeFiltersAtLargeText` 首跑失败，缺少 Scroll 语义父节点。
- 绿灯：修正后 `ChineseChessEndgameLandscapeTest` 2/2；原有 `ChineseChessEndgameScreenTest` 5/5；双 ABI Debug APK/AndroidTest APK 构建通过。
- `:app:lintDebug` 通过，报告 3 条既有依赖更新提醒、无错误。

此项只证明该 AVD 的横屏 150% 字体可达性，不代替更小屏幕、200% 字体、TalkBack、实体设备触控或通用首页的大字体验收。统一提交和推送仍待六棋种节点闸口。
