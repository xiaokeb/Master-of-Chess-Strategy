# 兰州麻将棋谱收藏与删除

正式棋谱支持收藏、取消收藏和「仅看收藏」。删除操作先确认，再从全部列表、收藏列表和回放入口移除这份棋谱。正式首结、四席统计和难度资格仍保留，备份也保留这些结算依据。

Room schema 27 给首结收据增加归档标记。已有收据迁移后默认可见、未收藏。收藏和删除与其他兰州麻将数据操作使用同一存储门禁，恢复备份前的页面无法修改恢复后的记录。

带收藏或删除标记的备份使用本次更新后的读写器。此前的备份仍可恢复，省略标记时按默认可见处理。新标记随完整备份一并恢复，终局证据继续由原生棋谱重放验证。

## 2026-11-26 验收

Pixel_7_Pro 的六项棋谱测试和两项备份回归通过。验证了 schema 26→27 迁移、收藏筛选、删除确认与取消、标记随整库备份往返，以及删除后的正式胜场和收据保留。已检查实际删除确认画面。NDK 30 双 ABI、Debug APK、仪器 APK 构建通过，Android Lint 为 0 错误、25 警告。

最终模拟器回执与截图位于 `.build/lz-archive-management1/device-cb5fbf7d5b01479ea82fe9817a690dad/`。Xiaomi M2007J3SC（arm64-v8a，Android API 31）通过四项棋谱数据用例，覆盖迁移、标记与整库备份往返、原生回放与首结保留。另通过五项原生结算和旧备份回归。真机界面测试未进入前台，等待超过五分钟后结束该次仪器进程。界面筛选和删除确认由模拟器验证。真机日志位于 `.build/lz-archive-management1/device-c1db401babee41a7afdcbfeb17d38e5f/`。

另补验 150% 字号横屏正式牌桌。Pixel_7_Pro 实际点击弃牌后，原生活动局从 0 步保存至 8 步，并可安全返回。三个截图均为 3120×1440，字号回执为 1.5。证据位于 `.build/lz-archive-management1/large-table/`。本次仅覆盖行棋、存盘和返回。

## 发布后真机复验

重新安装 `7244c2c` 验收使用的两份 APK。每项用例启动后，通过 ADB 将同一测试 Activity 启动到前台，未调整设备权限。Xiaomi M2007J3SC 的删除确认与回放前进、后退和返回各通过一项。连同此前九项数据、结算和备份测试，真机累计通过十一项。

| 用例回执（`.build/lz-archive-management1/`） | SHA-256 |
| --- | --- |
| `xiaomi-deletionRequiresConfirmationAndRemovesTheRecordFromTheList.log` | `edaaf1934d961faa7a2af855b91a11edfadba177da82ff70e54dd7d92982af96` |
| `xiaomi-realArchiveScreenStepsForwardBackAndReturns.log` | `ee4fc7dacadab169ddc11e52c0ee4c4e4961f58f1aa94f0b91803bfb0e6e17e9` |
