# 围棋开局学习生产验收

围棋模式页提供开局学习。六条项目自编线路覆盖 9、13、19 路，每着有对应说明。未完成十课也可浏览和播放，自动续弈沿用生产难度资格。训练入口在规则引擎验证完整线路且游标到达末端后可用。

新训练局在导航前保存到 Room，保留不可变开局和已有事件。存在未完成活动时需确认替换，取消后保留原活动。入局后通过现有继续按钮启动 KataGo，使用自动训练来源。验证保留已有正式开局证明，训练局未增加成长结果。

## 测试与证据

- 候选 `goopening1` 从 `16bb059` 和 17 个冻结文件构建。NDK 30 双 ABI、Debug APK、仪器 APK 和 Release Manifest 均通过。36 项 JVM 通过。Debug Lint 为 0 错误、25 警告，含开局回放 ViewModel 的空父类回调提示。
- Pixel 7 Pro 模拟器普通字号 4 项、150% 字号 2 项通过。六条线路均经 Native 逐着校验和 SGF 往返。正式导航在两档字号都验证了课程门槛、取消替换、保留六手起点和真实 KataGo 落子。
- 核看六张截图，覆盖线路目录、锁定状态和续弈棋盘。150% 字号下侧栏可滚动查看后续控制。

本次只发布源码。自编线路用于开局方向教学，职业定式、棋力分级和实体设备另行验收。[发布清单](../../releases/manifests/v0.1.0-go-opening-study.1.json)

提交 `dd206003453a1fbee85229cf182de99454ee35da` 已推送到 `main`。[v0.1.0-go-opening-study.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-opening-study.1)
