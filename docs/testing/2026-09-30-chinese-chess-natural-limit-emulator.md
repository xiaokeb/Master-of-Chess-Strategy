# 象棋自然限着：模拟器定向验收

日期：2026-09-30。设备：Pixel_7_Pro x86_64 AVD，Android 17。仅验收本次涉及的原生规则、Pikafish 历史回放与申请界面，不代替真实长局及实体设备验收。

- NDK 30 LLVM 的 `mocs_engine_chinese_chess_test` 在 AVD 以 0 退出，覆盖 120 半回合不自动和、申请审核、存档恢复等既有断言。
- `mocs_pikafish_search_history_test` 使用仓库内经 SHA-256 固定的 NNUE，在 AVD 输出 `Pikafish history replay and rejection checks passed`，以 0 退出。该测试未包含 120 半回合附近的实际搜索对弈。
- `:app:connectedDebugAndroidTest` 仅运行 `ChineseChessNaturalLimitScreenTest`：2/2 通过。完整记录可以申请时弹窗展示 120/120 有效半回合和误申诉风险；取消不调用申请回调，确认恰好调用一次；记录不完整时按钮禁用。测试使用合成界面状态，不冒充 JNI 长局端到端测试。
- 截图 `.build/visual/mocs-natural-limit-dialog.png` 已实际查看：弹窗文字与按钮完整、无手势栏或棋盘遮挡。该图片是本地忽略的测试证据，未入库。

剩余：真实 120 半回合从 JNI 经 ViewModel 到界面的回放、阈值附近 Pikafish 实际搜索质量、横屏/大字号及实体设备未验收。NNUE 使用仍受仓库 `NOTICE.md` 和 `docs/legal/` 所列非商业限制约束。
