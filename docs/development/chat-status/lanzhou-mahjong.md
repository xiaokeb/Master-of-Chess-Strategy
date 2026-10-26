# 兰州麻将对话状态

## 2026-10-26 原生与完整存储节点已发布

提交 `49ec2db2283fdedef9e42fd8bd45b7b176b917e6`，tag `v0.1.0-lanzhou-mahjong-native-storage.1`，[源码预发布](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-lanzhou-mahjong-native-storage.1) id424183711。以本节和[最终验收记录](../../games/lanzhou-mahjong/2026-10-25-native-storage-verification.md)为准，下方“尚待正式JNI/Room/备份”均为历史。

- 新局LZMJ v2炸胡各赔底分24倍，旧v1仍8倍；LZSR外层v1/v2均保留内层规则。严格Native完整恢复准入、固定时间/ID的真实Room事务及精确读回、生产完整备份已验，schema19不变；门禁顺序为兰州→方棋→围棋→Room，恢复退休旧Owner。
- 最终lzsp4：123项同源JVM，双ABI/Lint0错19警告；19个最终APK设备方法加7个明确同源继承，5个原生目标实际退出0；106次分配失败注入/6654检查及原版负例成立。8张共享围棋/方棋原PNG已查看，不冒充兰州牌桌视觉。1734文件全导出一致、60变更路径显式提交；此前计数61因已在基线的规则规格而拒绝，已更正。未清默认数据，无APK/ELF附件。
- 原生单步apply/undo具备异常回滚；bot整段可留下合法已提交前缀，宿主必须按实际动作数推进选择种子后确认保存。现有旧牌桌/VM/Store/十课公共表草稿未纳入该tag。
- 下一专属窗口：正式宿主后台串行Native资源、可等待关闭、固定Owner存储确认与重试、损坏恢复保留、bot异常前缀处理。共享导航/资格、十课独立持久化/备份、成长/音效/题量/AI梯度/真实进程恢复和实体设备继续另验；不直接启用旧宽松Store或默认补满辅助次数。
