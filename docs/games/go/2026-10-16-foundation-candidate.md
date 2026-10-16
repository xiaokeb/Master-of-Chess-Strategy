# 围棋规则、搜索与离线分发基础候选

## 范围与身份

本节点为完整围棋功能的依赖里程碑，不代表完整围棋交付。以已发布提交 `c3cc0dfc96dcaace045788476b38f5ff68173279` 为基线，增加围棋规则、SGF、强持有 JNI 搜索、模型校验与离线许可/源码访问。共享文件仅加入本节点必要片段；不纳入未验收的 AI 对局页面、自动续局、残局、其他棋种或新版象棋规则。

候选文本副本位于 `.build/go-foundation-draft-20261016/overlays/`，项目文本使用 LF；第三方原始源码与资产保持原字节。它是普通源码快照，不是 Git worktree，不覆盖主工作区。精确清单与构建收据完成后另记散列。

## 验收计划

- 核验原始 KataGo/Eigen 392 文件及生产 13 资产；保留锁定模型、源码包、许可及来源。
- 独立候选执行受影响 JVM、NDK 双 ABI、Debug/仪器 APK 和 Lint，不能复用混合树测试冒充本候选结果。
- 模拟器执行围棋规则、SGF、真实 NN 搜索/取消/关闭、AtomicFile 校验与资产到 Native 搜索链；查看普通和 150% 字体许可页。
- 核验最终 APK 资产和 Native 身份、构建前后源码身份，再串行安排精确提交、附注 tag 和阶段 Release。未通过前不提交或发布。

## 新增设备测试

`GoFoundationDistributionInstrumentedTest` 不依赖新版页面和会话接口：使用实际生产资产在本测试专属 UUID 缓存中完成 AtomicFile 发布、重复校验、一次真实 NN 搜索与规则合法性检查，并核验内置源码包/模型许可字节。仅删除该测试自建缓存，不清空应用数据。

初始状态：测试源码已加入，尚未编译或设备运行。既有 10 月 11 日证据只作依赖参考，不证明本候选已通过。

首份快照 `.build/rc-go1` 在资源解析阶段失败：摘取的源码版本字符串使用 `tools:ignore`，而基线资源根未声明该命名空间。已只在私有片段补全 `xmlns:tools`；失败日志保留，后续候选重新组装及验证，不覆盖旧证据。

## 许可与未验收项

KataGo 来源锁定为 `92ee95c0a4b25fec214da00951ab69e97e207729`，采用其 [原始 LICENSE](https://raw.githubusercontent.com/lightvector/KataGo/92ee95c0a4b25fec214da00951ab69e97e207729/LICENSE) 及内含第三方声明；模型遵守 [kata1 网络许可](https://katagotraining.org/network_license/)，不可与 g170 的 CC0 描述混用。Eigen 使用 MPL2-only 编译边界并分发准确源码与声明。本节点不扩大 Pikafish/NNUE 的授权；现有非商用限制继续有效。

四档棋力、完整可玩入口与业务存档、内容量、API24 实际设备、实体设备、最终美术音效及发布签名仍按原计划分别验收，不能因基础节点发布标成完成。
