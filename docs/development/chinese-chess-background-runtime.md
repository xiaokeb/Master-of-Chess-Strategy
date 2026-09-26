# 中国象棋后台运行实施计划

状态：需求与现状核查完成，尚未实现前台服务和熄屏保活。

## 依据与差距

正式需求 2.2.3 要求后台挂机、熄屏不暂停、返回同步；性能章节要求后台降低资源消耗。
当前对局由导航项 ViewModel 持有，协程在进程仍获调度时继续运行；持久化时间戳
可以补算棋钟，但不能保证设备休眠时 AI 持续计算。现有说明见
`chinese-chess-time-draw-auto-play-slice.md`，不得将这两种能力混为一谈。

## 分步实施

1. 抽离单一会话运行所有权。界面与后台服务共享同一引擎、存档队列和终局回调，
   不另外创建会竞争活动存档的后台 ViewModel；切换模式、返回退出、恢复备份时明确释放。
2. 将用户发起的持续对局接入可见前台服务，通知提供返回与停止；启动在可见界面中进行。
   采用与用途匹配的 `specialUse` 声明并描述离线棋局计算；不伪装媒体播放或定位。
3. 仅在实际需要持续 AI 工作时使用有超时兜底的部分唤醒锁，结束、暂停、失败、停止服务
   均释放。人类思考通过时间戳计算，不以高频空转保持 CPU；后台降低非搜索调度频率。
4. 保留系统最终控制权：不自动申请电池优化豁免，不静默重启被用户停止的服务，
   不承诺强制停止/系统终止后仍运行。再次打开从最后一致存档恢复，不重复奖励或棋谱。
5. 验证通知与权限拒绝、切后台/熄屏仍有新着、返回无重复 AI、暂停/退出无资源残留、
   进程恢复及备份切换；分别记录模拟器功能结果和真机长时功耗/性能，后者不能以 AVD 代替。

## 平台依据（2026-09-26 核对）

- [前台服务概述](https://developer.android.com/develop/background-work/services/fgs)：持续工作必须对用户可感知。
- [服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)：声明匹配类型及权限；specialUse 用途需要解释，商店上架另有审核，不代表已获准。
- [唤醒锁](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)：仅用于必要的持续工作，及时释放。
- [Doze 与待机](https://developer.android.com/training/monitoring-device-state/doze-standby)：系统休眠策略仍可能限制运行，不能承诺无限后台保活。

本节点不改 Pikafish 许可、NNUE 使用范围、自然限着口径或 AI 棋力参数。
