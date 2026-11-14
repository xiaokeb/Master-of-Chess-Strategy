# 围棋熄屏自动演局

在自动演局运行时，用户可从棋盘启动后台演局。前台服务提供通知，允许返回棋盘或停止演局。开启服务后仍由当前牌桌的 ViewModel 搜索和写入存档。进入后台后相邻着法至少间隔三秒，前台继续使用当前倍速。

电源锁仅在熄屏或离开前台时持有，并在有新着法保存时续期。45 秒未保存新着法时暂停并停止服务。通知停用、切换对局、出错、退出、备份清退或进程终止也会结束后台执行。回到棋盘可从最后一次成功保存的位置继续。双方仍须在棋盘确认死子及终局。恢复进程后的自动局默认暂停。

围棋服务沿用应用的前台服务和电源锁权限。通知可见时才允许启动，后台不播放对局声音。应用支持 API 24 起的全局通知开关，并在 API 26 起检查独立通知渠道。[Android 前台服务限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) [Android 电源锁说明](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/best-practices)

## 验收

- 正式候选 `gobg1` 通过 58 项定向 JVM、NDK 30 双 ABI、Debug APK、仪器 APK、Release Manifest 和 Lint，Lint 0 错误、25 警告。
- Pixel 7 Pro 在 100% 和 150% 字号下各通过一次专项。使用正式导航、真实 KataGo 和独立 Room 数据库。测试验证熄屏继续保存到原局、降频、通知返回、再次熄屏并从通知停止，以及停止后不再落子。字号和通知权限已还原。核看四张棋盘启用与暂停截图。系统通知标题及操作已验证，系统通知栏画面未验收。
- 设备收据位于 `.build/gobg1/device-09234903efc545638cfac8985a842123/`。实体设备耗电、OEM 省电策略和棋钟仍待专项验收。
