# 象棋自然限着：模拟器定向验收

日期：2026-09-30。设备：Pixel_7_Pro x86_64 AVD，Android 17。定向验收原生规则、Pikafish 历史回放及真实 Kotlin/JNI→ViewModel→申请界面；不代替完整长局棋力、存储事务或实体设备验收。

- NDK 30 LLVM 的 `mocs_engine_chinese_chess_test` 在 AVD 以 0 退出，覆盖 120 半回合不自动和、申请审核、存档恢复；传入已核验 NNUE 后，还确认窗口回放下的 Pikafish 简单难度搜索返回当前合法着，且不改变本地终局。
- `mocs_pikafish_search_history_test` 使用仓库内经 SHA-256 固定的 NNUE，在 AVD 输出 `Pikafish history replay and rejection checks passed`，以 0 退出。该测试未包含 120 半回合附近的实际搜索对弈。
- `:engine-native:connectedDebugAndroidTest` 仅运行 `NativeChineseChessNaturalLimitInstrumentedTest`：1/1 通过。通过正式 Kotlin→JNI→C++ 接口恢复精简局面并走满 120 个无吃子半回合，核对仍在对局、审核可申请；再次经 JNI 恢复长局存档后，申请判和有效，原引擎局面不被副作用改变。
- `:app:connectedDebugAndroidTest` 仅运行 `ChineseChessNaturalLimitScreenTest`：4/4 通过。合成状态用例覆盖申请弹窗 120/120 计数、风险提示、取消/确认回调及历史不全时按钮禁用；真实原生引擎重演 120 半回合后，ViewModel/对局界面确认申请并转为和棋；另用原生引擎核对首次误申诉扣 5 分钟、同一阶段第二次误申诉判负。测试未接 Room 仓库，不证明终局数据库事务。
- `:app:lintDebug --offline --max-workers=2`：0 错误、3 条既有依赖版本提醒；本轮 Debug APK 随定向仪器测试重新构建。
- 截图 `.build/visual/mocs-natural-limit-dialog.png`、`mocs-natural-limit-draw.png` 已实际查看：弹窗和终局文字、棋盘与操作区完整，安全区无遮挡。截图由通用 Compose 测试 Activity 产生；其浅色系统栏白色图标不是正式 `MainActivity` 的窗口配置，不能用它判断生产状态栏对比度。正式 Activity 的系统栏由既有 `AppWindowInsetsTest` 单独验收。图片为本地忽略证据，未入库。

剩余：完整对局的 Room 原子存档/冷启动回放、将军扣着与误申诉的更多设备边界、阈值附近 Pikafish 实际棋力、横屏/大字号及实体设备未验收。NNUE 使用仍受仓库 `NOTICE.md` 和 `docs/legal/` 所列非商业限制约束。

调试记录：首次阈值搜索被 Pikafish 拒绝。增强错误信息后确认原因是测试自由摆局的红兵位于其 NNUE 不支持的未过河横移点；改到已过河的合法落点后，同一 120 半回合窗口与 JNI 回归均通过。不能把这次失败归因于窗口适配本身。终局截图发现“请选择当前行棋方的棋子”仍显示；已让终局优先显示现有结束文案并用全链路用例断言。
