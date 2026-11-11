# 国标麻将自动演局存档与回放验收

Room 23→24 新增自动演局表。活动与完成归档按观察者隔离，归档由赛事 ID 和序号确定。活动与归档同事务保存。重复提交保留首次归档时间及收藏。删除归档后，保存播放设置不会重新生成该归档。

整库备份使用独立 `COMPETITION_AUTO` v1 记录，包含私有 MCAU。替换前校验所有身份、配对和重复键，并用 Native 重放历史。标准开局须匹配保存种子生成的牌墙。旧页面在备份替换后失去写入资格。自动结果保持独立，设备用例确认成长结果为空。

## 测试与证据

- 候选 `cmautoroom1` 从 `81ac035` 和 36 个冻结文件构建。Android NDK 30 双 ABI、Debug APK、仪器 APK 与 Release Manifest 检查通过，Lint 0 错误、24 警告。七套 JVM 共 77 项通过。
- Pixel 7 Pro 的 8 项原生、Room 与备份用例通过，覆盖真实十六盘样本、版本迁移、SQL 触发失败回滚、磁盘重开、原档保护、收藏与删除。后续只修改页面测试，应用 APK 及这 8 项测试的源码散列一致。
- 普通字号的 3 项与 150% 字号的 2 项页面用例通过。核看五张独立测试宿主截图，覆盖单步、难度锁定、选盘回放、设置滚动和私有导出确认。观察席暗手可见，其余三席仍显示牌背。

本次只发布源码。正式大厅导航、系统文件选择器、横屏生产入口、后台执行许可、真实跨进程恢复、容量评测和实体设备仍待验收。源码、测试包和截图散列见[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-auto-room.1.json)。

提交 `d5ca56671c9501e04e798364f2555b5d0c3283f1` 已推送到 `main`。[v0.1.0-competition-mahjong-auto-room.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-competition-mahjong-auto-room.1)
