# 中国象棋对话状态

2026-10-24 18:49：已发布 `d64deddb83447b4cc0c419604aa440aca49c2fe6`／`v0.1.0-xiangqi-budget-v2-admission.1`／[源码Release](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-xiangqi-budget-v2-admission.1) id422672166。新六源规则＋完整搜索树身份、严格输入桥、读取锁冻结副本构建及独立依赖/命令后验通过；干净xbva2实际71测试、41对象/2库/45命令，最终ELF在API37/x86_64/16KiB模拟器12项（1成功/11拒绝）通过。32份PF的CRLF差异只在新portable规格重审/重编解决，旧规格/结果/异常槽不改。1579完整索引导出、19路径与工作区保护通过，无App/JNI/生产参数修改、无二进制附件；构建阶段不执行ELF，独立设备阶段执行validate-plan但不搜索/加载NNUE。复现、证据与未验收范围见 [新报告](../../games/xiangqi/2026-10-24-budget-v2-admission-verification.md)。下一有限诊断与新完整协议仍待，search_authorized=false/strength_accepted=false；不宣称完整象棋完成。

更新时间：2026-09-30。当前节点：自然限着模拟器定向验收。

- 已实现：教程第二步可切换车、马、炮、相、士、将帅、兵卒七幅只读规则示意；新增选项行测试标签，未改教程存档与规则引擎。
- 验证：App JVM 243/243、Lint 0 错误、双 ABI Debug APK；Pixel_7_Pro AVD 独立教程 2/2，正式导航横屏专项 1/1。证据见 `../../testing/2026-09-30-chinese-chess-seven-piece-diagrams.md`。
- 视觉：已查看肖像车/兵与横屏兵图；实体设备、大字号及无障碍仍未验收。
- 共享文件：本节点修改 App 通用 `strings.xml`，仅添加 `tutorial_*` 资源；其他棋种不得覆盖或批量暂存本节点代码。Gradle 与 AVD 操作需继续串行。
- 下一步：继续自然限着存储与误申诉设备边界、Pikafish 梯度和经许可残局内容。象棋独立内容配额仍未确定，80% 不再是其他棋种启动门槛。本节点提交与远程状态以 Git 日志核验。

## 本轮新增

- NDK 原生象棋规则测试与使用已核验 NNUE 的 Pikafish 历史测试均在 Pixel_7_Pro AVD 运行通过；自然限着界面/JNI 定向仪器测试 4/4 通过。报告见 `../../testing/2026-09-30-chinese-chess-natural-limit-emulator.md`。
- 已查看申请弹窗及真实 ViewModel 和棋截图；风险提示、有效着数、和棋反馈与按钮状态可见。Room 持久化和棋力边界未验收，不宣称自然限着所有链路完成。
- 本轮只新增象棋专属仪器测试及测试报告，并维护本状态。公共构建与 AVD 窗口已释放。
- 追加 `NativeChineseChessNaturalLimitInstrumentedTest`：模拟器通过正式 Kotlin/JNI 接口重演 120 个无吃子半回合、存档恢复及审核判和，1/1 通过；随后真实 ViewModel 与对局界面联动 1/1 通过。
- 追加阈值搜索冒烟：经核验 NNUE 的 Pikafish 在 120 半回合适配窗口返回当前合法着，原生规则测试以 0 退出；尚非长局棋力验收。上游拒绝历史时现保留具体校验原因，便于定位非法摆局。
- 真实原生引擎配合 ViewModel 的误申诉设备回归通过：首次扣 5 分钟，第二次判负；终局操作区不再错误提示选择棋子。生产业务仍待 Room 原子提交与冷启动验收。
