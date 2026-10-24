# 象棋预算 v2 准入：分发记录

适用节点 `v0.1.0-xiangqi-budget-v2-admission.1`，仅源码预发布，无APK、ELF、静态库或新增权重附件。

- Pikafish仍为提交 `4c17cee11f888ae1d48a9494f2e2239f019f0a1f` 的既有修改快照；完整对应源码、`Copying.txt`、AUTHORS、外部依赖许可及 `UPSTREAM.md` 修改记录随标签保留。本节点未改变搜索算法、生产档位或第三方源内容。
- 新增入口、构建/校验工具及测试按项目现有GPL-3.0-or-later许可随源码交付。真实构建使用明确NDK30工具和参数；本地编译产物不是公开二进制发行包。
- NNUE字节及许可不变，SHA256 `7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e`。只校验既有文件，不在自检时加载；不以引擎GPL替代权重条款，不扩大商用授权。保留 `app/src/main/assets/pikafish/NETWORK-LICENSE.md` 和 `third_party/pikafish-network/` 来源材料。
- 标签继承围棋模型及其他固定依赖，继续遵守已有来源、许可和对应源码义务，参见 [既有分发记录](go-local-data-distribution-v1.md) 与 [Pikafish源码闭包](pikafish-source-closure-2026-10-15.md)。
- 根LICENSE、NOTICE、应用通知资产及所有第三方/模型文件均与基线一致。旧曝光资料为本项目既有评测输入，此次仅用于排除类重建，不是新增外部题库或棋力结论。

源码预发布不代表完整产品或所有计划用途获得授权；后续分发APK仍须按当时实际产物核对完整源码、构建材料、媒体与权重许可。
