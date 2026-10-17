# 围棋本地数据节点：来源与分发边界

2026-10-17，适用于 `v0.1.0-go-local-data.1` 源码预发布，不附 APK，不扩大任何第三方或模型的商用许可。

本节点保留已发布围棋基础节点的 KataGo/模型及依赖来源，见[既有 CPU 分发说明](katago-cpu-distribution-v1.md)。Pikafish、NNUE 继续遵守根 `NOTICE.md` 及各自源码/权重条款；本地双人、棋谱或备份功能不改变这些条件。

国标备份依赖保留完整 vendor 源码及 MIT 版权/许可，应用内副本与 vendor/LICENSE 逐字节一致；wrapper 和规则修正不代表上游背书或官方规则全覆盖认证。

本节点固定 `com.android.tools:desugar_jdk_libs:2.1.5` 及同版 configuration，用于最低 API24 的 Java 日期兼容。来源是既有 Google Maven，runtime JAR SHA `d8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976`，POM SHA `2e195880f15d4545c8d60b2d2cac52201ccf98db7a29db2577ad5826a930fcca`。既有 POM 声明 GPLv2 附 Classpath Exception，指定[上游 LICENSE](https://github.com/google/desugar_jdk_libs/blob/master/LICENSE)；正文已保留，不宣称从没有通知文件的 JAR 提取了许可。

新增 MIT 通知后的根 NOTICE 与应用内 `legal/OPEN-SOURCE-NOTICES.md` SHA 均为 `fbe49253df44ece273574de3b362dacf091e64fc61a78cdde0d5752100cbc027`。更早报告中的散列仅代表当时版本。

准确对应源码/provenance、源码获取方式及二进制分发义务仍是 APK 分发前的门禁；通知正文入包不等于全部义务完成。本次仅发布源码，不作法律完备保证。API24/25、ARM64 与实体设备仍无本节点实际运行验收，不能以 Lint 或 API37 模拟器代替；不得为回避测试提高 minSdk。

测试、候选身份与未验收项见[本地数据节点报告](../games/go/2026-10-17-local-data-verification.md)。
