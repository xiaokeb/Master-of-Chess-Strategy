# 国标麻将 MCAE 棋谱导入与导出

自动赛事棋谱页通过 Android 系统文件选择器读写 MCAE v1。用户确认导出后，文件包含十六盘事件和用于重放的私有牌局数据。

导入按当前初始座位检查归属，并限制读取至 1,100,016 字节。文件封装通过检查后，Native 从合法起点逐盘重放，并核对终局结算。存储代次仍有效时，Room 才在事务中写入归档。

同一键和内容重复导入时保留首次保存时间和收藏。冲突、坏文件和失效页面均拒绝写入。导入只写自动赛事归档，人机成长和当前活动保持独立。

## 验收

- NDK 30 双 ABI、Debug APK、仪器 APK 和 Release Manifest 检查通过。七套 JVM 共 78 项通过，Lint 为 0 错误、25 警告。
- Pixel 7 Pro 普通字号 3 项、150% 字号 1 项通过。通过真实系统选择器写出并读回同一文件，核对完整字节、删除后回导和超限文件拒绝。
- Room 与 Native 用例覆盖坏校验码、错误席位、校验码有效但牌墙与种子不符、活动及归档冲突、重复导入、存储代次失效与人机战绩隔离。
- 核看 3 张横屏截图，覆盖已导入列表、普通与大字号的导入入口以及取消导出后的回放。

本次发布源码。后台执行、杀进程恢复和实体设备仍需独立验收。[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-mcae.1.json)

提交 `b319fdc747ec4f085531f2357931087610100313` 已推送到 `main`。[v0.1.0-competition-mahjong-mcae.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-competition-mahjong-mcae.1)
