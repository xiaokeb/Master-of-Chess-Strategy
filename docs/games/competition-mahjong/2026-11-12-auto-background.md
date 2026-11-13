# 国标麻将后台演局验收

在自动演局设置启用「允许后台演局」，保存后继续演局，再从牌桌选择「启动后台演局」。应用确认通知可见后才启用，用户可从通知返回牌桌或停止。停止会保存当前活动为暂停，并释放后台授权和唤醒锁。设置随活动存档，后台授权随本次演局所有者存续，进程退出后须由用户重新启动。

服务沿用现有牌桌的事件调度和 Room 写入。熄屏或离开可见页面后使用已保存的后台最小间隔。唤醒锁只在后台授权期间使用，若超过当前间隔加 30 秒仍无提交则结束授权。Release Manifest 将服务声明为 `specialUse` 且仅供应用内部调用。[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types) · [通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

## 验收

- 候选 `cmautobg1` 通过 79 项 JVM、NDK 30 双 ABI、Debug APK、仪器 APK 和 Release Manifest，Lint 为 0 错误、25 警告。
- Pixel 7 Pro 模拟器在普通和 150% 字号分别执行一次正式导航测试。拒绝通知时未启动服务，允许后显示停止动作。两次运行均经历实际熄屏及 Activity 重建，同一活动持续存盘，配置 2500 毫秒时相邻存档的观测间隔为 2493 和 2495 毫秒。
- 通知返回同一牌桌后唤醒锁释放。再次熄屏，通过系统通知的停止动作写入暂停，并确认等待超过一个后台间隔后 Native 记录无变化。应用通知权限及字号恢复为测试前状态。
- 核看 8 张截图，覆盖两档字号下的牌桌控制、系统通知、返回与暂停。早期失败记录保留，最终候选由演局所有者持有授权，覆盖了熄屏导致界面重建的情况。[发布清单](../../releases/manifests/v0.1.0-competition-mahjong-auto-background.1.json)

本次发布源码。强制 Doze、厂商省电策略、长时功耗和实体设备另行验收。

提交 `95b4768d541f26fac0acb453d4df4bbc29cc55ae` 已推送到 `main`。[源码预发布](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-competition-mahjong-auto-background.1)
