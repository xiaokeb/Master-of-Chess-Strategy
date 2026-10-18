# 象棋新四关 Debug 实装视觉核验

日期：2026-10-03。正式残局目录按顺序解锁；不写入虚假完成记录、不清空模拟器数据。为检查尚未解锁的资源，新建只在 `app/src/debug/` 编译且以 `android.permission.DUMP` 限制调用的 `XiangqiEndgameVisualCheckActivity`：由 APK `AssetManager` 读取正式 v5 包，经现有解析器、真实 `ChineseChessGameViewModel` 和 `NativeChineseChessEngine` 显示棋盘；无 Room 仓库，完成时不写正常进度。此入口不是正式关卡导航。

## 核验

- `:app:assembleDebug --offline --max-workers=2` 通过，内容/许可/音效/网络门禁及双 ABI 原生库构建通过。`app:lintDebug` 通过，0 错误；仅有国标麻将专属 Compose `ModifierParameter` 警告。
- `:app:processReleaseManifest --offline --max-workers=2` 通过；合并 Debug Manifest 有 QA Activity，合并 Release Manifest 无该 Activity。正式包不提供直达关卡入口。
- 加入 `DUMP` 调用权限后重新构建、覆盖安装 Debug APK，ADB 仍可显式打开 `xq-easy-017`；未测试普通第三方应用能否调用该受限入口。
- 在 Pixel_7_Pro x86_64 AVD 安装 Debug APK，四关逐关启动并检查标题、红方行棋、目标步数及真实棋子摆位。截图位于本机 `.build/visual/`：`mocs-endgame-017-debug-qa.png`、`mocs-xq-easy-018-debug-qa.png`、`mocs-xq-medium-008-debug-qa.png`、`mocs-xq-medium-009-debug-qa.png`，均已人工查看。
- 四关均通过真实触控完成：`xq-easy-017` 车 `(8,5)→(8,0)`、`xq-easy-018` 车 `(8,6)→(4,6)` 均显示一步红胜；`xq-medium-008` 马 `(0,3)→(1,1)` 后 AI 将 `(3,2)→(3,1)`，炮 `(0,7)→(0,1)` 两步红胜；`xq-medium-009` 马 `(4,8)→(5,6)` 后 AI 象 `(6,0)→(4,2)`，炮 `(7,7)→(5,7)` 两步红胜。第 9 关 AI 应手与资源示例主变化不同，证明本次不是仅回放固定棋谱。
- 退出全部四次 QA 对局后回到正式残局目录，仍显示 `总进度 0 / 31 · 残局奖励 0 星 / 0 分`。QA 入口未写入正常解锁或奖励。第 8 关终局截图为本机 `.build/visual/mocs-endgame-008-win-debug-qa.png`，已人工查看。

复现时先退出当前 QA Activity，再用 `adb shell am start -n com.masterofchessstrategy/.ui.XiangqiEndgameVisualCheckActivity --es level_id xq-easy-017` 启动下一关；对已经位于栈顶的 Activity 直接重复 `am start` 可能只递送新 Intent，不能据此认为关卡已切换。错误 ID 会直接结束 Activity。

## 边界

本次是 Debug APK 的正式资源解析、真实 Native/ViewModel 渲染和四关实际人机终局；四关的初始棋盘视觉与第 8 关终局均已查看。尚未在正式线性解锁导航中逐关进入，也未覆盖四关的全部可能 AI 应手、奖励持久化、音效试听、难度趣味性或实体设备验收。App AndroidTest 仍被国标麻将专属两处无效导入阻断，按用户决定由其对话修复。因此完整验收量暂按 27 关，六棋种统一提交节点未冻结。
