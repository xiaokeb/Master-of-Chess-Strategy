# 象棋混合子力残局增量验收

2026-10-01，Pixel_7_Pro x86_64 AVD、NDK 30 LLVM、项目 Conda `MasterofChessStrategy`。在现行 v5 包后追加项目生成的六道奖励题：`xq-easy-011` 至 `013` 为车炮、马炮、双车唯一一步杀；`xq-medium-003` 至 `005` 为对应的两步强制胜。旧 16 关字节前缀 SHA-256 仍为 `7c694f769a10768f406b9df3117208fb506616c9e51db15e07e2a94cfcb82166`，会话版本与旧 ID 不变。新资产 SHA-256 为 `968e734fdac7447cd8f177b279213928dfd3372d2bdd0ad103b2a6cf6420b3fb`，已登记于 `content/manifests/release-content-v1.tsv`。项目自有来源与 GPL-3.0-or-later 见 `content/manifests/chinese-chess-endgames-v5.md`；未引入第三方棋谱或媒体，Pikafish/NNUE 许可边界不变。

| 检查 | 结果 |
| --- | --- |
| Conda 结构、镜像和来源对应 | 22 关、21 个镜像独立局面；六关摆子与主变化逐字段匹配固定种子原始流；全 Python 56/56 |
| 独立主机浅层规则 | 22 关主变化及主题复核，访问 9711 节点；不代替原生循环棋例规则 |
| Android JVM | 包解析、旧 ID 兼容和新增中等奖励顺序解锁 13/13 |
| APK 内资产与原生规则 | AVD 3/3：22 关主变化、12 道唯一一步杀、5 道两步全防守与唯一首着 |
| Compose 肖像目录与棋盘 | 修正测试误用 `performScrollTo` 后，同一类完整重跑 4/4 通过 |
| 横屏目录 | 真实安装包首次检查发现筛选区挤占高度，卡片下半段难以阅读；改为左侧筛选/进度、右侧可滚动关卡列表后，新增横屏设备用例 1/1，实际安装包首页导航再次截图确认 |
| 构建 | `:app:assembleDebug` 与双 ABI 原生库、发布内容/法律/NNUE/音效门禁通过；`:app:lintDebug` 0 错误、3 条既有依赖新版提醒 |

肖像目录与棋盘截图位于本机 `.build/visual/mocs-xiangqi-medium-004.png`、`.build/visual/mocs-xiangqi-medium-004-board.png`；横屏专项为 `.build/visual/mocs-xiangqi-medium-004-landscape.png`，实际 Debug 包从首页导航后的最终画面为 `.build/visual/mocs-xiangqi-medium-final.png`，滚动至第 5 关见 `.build/visual/mocs-xiangqi-medium-catalog-scroll.png`（构建产物，不纳入 Git）。已逐张查看：中等奖励卡的标题、两步目标与分值可读；棋盘八枚棋子、楚河汉界和目标栏完整；横屏右侧前两行卡片完整可读，第 5 关滚动可达。前三幅由独立 Compose 测试宿主生成，后两幅来自实际 Debug 包；均不等于实体设备验收。其余五道新增关卡未逐张截图。难度标签是编排分层，未做玩家样本标定，不宣称专业残局审美或 9000+ 内容目标完成。

本地 Debug 包中的正式内容现为 22 关；统一节点 Git 提交/远程推送仍待其他五棋种冻结与共享集成验收。象棋 AI 梯度、自然限着真实杀进程恢复和实体设备音效/性能另行跟踪。
