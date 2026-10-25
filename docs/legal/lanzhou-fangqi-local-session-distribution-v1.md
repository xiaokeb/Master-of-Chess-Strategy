# 兰州方棋本地对局节点分发记录

适用 `v0.1.0-lanzhou-fangqi-local-session.1`，仅源码预发布，无APK、ELF、静态库、题库或媒体附件。

- 本节点新增方棋规则实现、JNI、Android页面/存储/备份及测试属于项目源码，沿用根目录 `LICENSE` 的GPL-3.0-or-later。棋盘与棋子由Canvas绘制，没有新增外部图片、音效、权重或Python依赖。
- 正式规则取自项目需求和用户已确认的开局、成方、逐枚吃子、整局禁重决定。只实现规则逻辑，不复制第三方棋盘素材，也不宣称官方或地方规则机构认证。
- 基线中的Pikafish源码、修改记录、GPL与NNUE独立条款保持不变，继续保留 `NOTICE.md`、`third_party/pikafish/`、权重来源和许可。源码节点不会把NNUE重新授权为GPL或扩大商用权限。
- 标签继承KataGo、围棋网络、MIT麻将算法与Android依赖的既有通知和对应源码材料；本节点不修改其内容。参见 [既有围棋分发记录](go-local-data-distribution-v1.md) 与 [Pikafish源码闭包](pikafish-source-closure-2026-10-15.md)。
- 本地Debug APK仅用于指定候选的模拟器验收，不附于Release。后续APK发行仍须核对实际标签、完整对应源码、构建材料、资源来源及当时有效的各项许可。
