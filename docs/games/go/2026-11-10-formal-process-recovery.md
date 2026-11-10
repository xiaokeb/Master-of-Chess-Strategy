# 围棋正式对局跨进程恢复

本次设备准备复用正式牌桌路由、Room 检查点仓库、KataGo 加载器和 Native 棋局。Debug 专用 `:go_formal_qa` 进程只打开由测试预先建立的 UUID 数据库，旧文件读取另用隔离目录。共享接点仅为 Debug Manifest 的 Activity 声明。现有围棋教程和残局开发文件不进入本节点。

| 场景 | 初始证据 | 重启后的核对 |
| --- | --- | --- |
| 标准人机 | 正式创建来源和两手 Native 记录 | 新 PID，同局 ID、检查点散列和剩余额度。继续落子后保留同一正式来源。 |
| 指定开局 | 两手不可变开局、再走两手 | 当前记录、开局散列和事件下界一致。重开后回到指定开局。 |
| 自动续局 | 指定开局、系列第 2 局、上限 3，双虚后黑方已确认 | 恢复协商确认和系列配置。双方确认后归档原局并进入第 3 局，重复恢复不再生成同一归档。 |

设备驱动先取得活进程 PID 和牌桌就绪收据，再只终止已核对的专用进程。重启需取得不同 PID 和同一检查点。既有 Activity 重建及关闭 Room 再打开的结果继续单列。

## 首轮设备结果

候选 `goformalproc1` 基于 `688b2a2`，隔离叠加专用 Activity、测试数据准备器和一段 Debug Manifest。Debug 双架构及测试 APK 已构建，Release Manifest 已核对排除该 Activity。

- 标准人机 PID `4574` → `4645`，两手棋、赛事 ID、检查点及辅助额度一致。
- 指定开局 PID `4764` → `4833`，当前四手及初始两手的摘要、事件下界一致。
- 自动续局 PID `4933` → `4992`，恢复第 2／3 局和五条事件，黑方确认仍保留，白方待确认。

三个场景的原始记录见 `.build/goformalproc1/device-3554312bd43c4ca29d5acb6a20d5bc8c/`。

- 标准人机恢复后实际落子，KataGo 应手。Room 检查点从两手增至四手，保留同一对局和唯一正式创建记录。
- 指定开局恢复后点击「重新开始」「确认重开」，取得新对局 ID，当前四手回到原开局两手，开局散列不变。
- 自动续局恢复后点击「白方确认」，进入新 ID 的第 3／3 局。重复启动后，原局归档、棋谱和投影各一份，归档时间与记录 ID 一致，成长结果为零，外键检查无异常。

三项后续操作的截图与收据分别位于 `.build/goformalproc1/finish-d869486d301d48139953df7d37fd6b7f/`、`.build/goformalproc1/finish-0a9780dda7124bba987d7dafd0007e9a/` 和 `.build/goformalproc1/interaction-cdacf042b03f4255b912b9c8265f600f/`。设备使用实际生产牌桌与隔离 QA 数据库。

## 发布候选复验

候选 `goformalproc2` 基于 `adc41d4`，沿用相同的三个围棋覆盖文件，包含已发布的国标麻将难度节点。Debug 双 ABI 与测试 APK 构建通过，Lint 0 错误、24 警告，Release Manifest 排除了 QA Activity。Pixel 7 Pro 复验三个场景，PID 依次为 `6840` → `6944`、`7047` → `7110`、`7212` → `7272`，除进程身份外的收据字段一致。[发布清单](../../releases/manifests/v0.1.0-go-formal-process-recovery.1.json)

发布提交为 `fb559505ff71466e5b4de5577879cbd636d8ac52`。[源码预发布 v0.1.0-go-formal-process-recovery.1](https://github.com/xiaokeb/Master-of-Chess-Strategy/releases/tag/v0.1.0-go-formal-process-recovery.1)

## 九路真实模型长局

使用 `goformalproc2` 的实际安装包，在指定两手开局的连续第 3 局启动简单档 KataGo 自动演局。94.8 秒后进入双方复核，已存 89 条事件。实际点击双方确认后为 91 条事件，前后两局分别有唯一归档、棋谱和投影，成长结果为零。进程四次 PSS 采样介于 301529 至 310019 KiB。该记录用于九路自动演局稳定性检查，19 路长局、棋力和实体设备待验收。

初次终局检查因页面状态的 `completion_stored` 仍为 `false` 超时。只读检查原 Room 数据库确认写入成功，问题集中在自动系列末局的状态更新。证据位于 `.build/goformalproc2/long-98f7bc7b266a4bc79354bef90d774de7/`，包含计分截图、事件日志、内存样本和原终局归档记录。

修复后，两条回归在旧代码上复现失败，修复候选通过 26 项相关 JVM 测试和 Debug Lint。Pixel 7 Pro 使用同一终局存档，确认 91 条事件、双方计分确认和棋谱完成状态。再次终止专用进程并重启后，两个归档及其时间、棋谱、投影均与原数据库一致。复验截图及收据见 `.build/gofinalarchive1/device-2509e958b7ce4e67bdc7c46d722636af/`。

## 复现

1. 安装同源 Debug 应用和测试 APK。以 `GoFormalProcessCheckpointInstrumentedTest#seed` 运行 AndroidJUnitRunner，传入唯一 UUID 参数 `go_process_token`，以及 `go_process_case` 的 `standard`、`opening` 或 `continuation`。
2. 用 ADB 启动 `.ui.GoFormalProcessCheckActivity`，传入 `owned_database=go-formal-process-qa-<UUID>.db` 和 `match_id`。`match_id` 是 `UUID.nameUUIDFromBytes` 对 UTF-8 文本 `<UUID>:<go_process_case>` 的结果。
3. 等待 `files/go-formal-process-qa/go-formal-process-qa-<UUID>.json` 更新，并核对其 PID 等于 `:go_formal_qa` 的进程 PID。只终止已核对的专用进程，使用同一参数重启，比较除 PID 以外的字段。可再执行上述牌桌操作并检查收据和归档表。
