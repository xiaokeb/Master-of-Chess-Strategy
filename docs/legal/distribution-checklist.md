# 发布授权检查清单

本清单用于项目发布门禁，不构成针对特定司法辖区的法律意见。

## 当前允许的范围

- 项目自有源码及直接链接的 Pikafish 组合整体按 GPL-3.0-or-later 发布。
- 固定 Pikafish 源码、原始版权声明、作者名单、构建脚本和本地修改记录随仓库提供。
- 当前内置 NNUE 权重只用于合法、个人、非商业场景。
- 应用不申请网络权限，不提供联机辅助或网络对局作弊能力。

## 分发 APK 前必须满足

1. APK 对应提交已推送到公开源码仓库，且能取得生成该 APK 所需的完整对应源码和构建脚本。
2. 根目录 LICENSE、NOTICE.md、Pikafish Copying.txt、AUTHORS 和 UPSTREAM.md 保持完整。
3. 应用设置中的“开源许可与法律边界”可打开，并能查看版权、无担保、GPL 全文、源码地址和 NNUE 条款。
4. Gradle 的 verifyBundledLegalDocuments 与 verifyPikafishNetwork 均通过。
5. 修改 Pikafish 时更新 third_party/pikafish/UPSTREAM.md，写明修改内容和日期，并按 GPL 同步提供源码。
6. 不得把当前 NNUE 权重用于收费应用、广告变现、订阅、商业服务或其他商业场景，除非已取得权利人的书面许可并保存可审计凭证。
7. 若无法取得 NNUE 商业许可，必须在商业发布前移除该权重，或替换为许可证和网络结构均兼容的权重，并重新完成棋力与哈希测试。

## 不得宣称

- 不得将 Pikafish、其作者或 NNUE 权利人表述为本项目的商业背书方。
- 不得把 NNUE 权重描述为 GPL 项目自有资产。
- 未在真实 Android 设备运行 JNI、AI 和音效测试前，不得宣称这些设备测试已经通过。
