# 游戏内容发布来源门禁

当前机器清单：`content/manifests/release-content-v1.tsv`。每行固定为
`相对路径|SHA-256|许可证|来源类别|来源说明`。当前仅收录经项目自行
编排的象棋残局 v4 与定式种子 v3，许可证均为 GPL-3.0-or-later。

Android `preBuild` 执行 `verifyReleasedGameContent`：检查清单路径只在已
定义发布范围内、无重复或路径穿越、SHA-256 与当前文件相符、来源说明存在，
并要求 `app/src/main/assets/endgames/` 下每个发布文件都有清单项。定式
源码也固定在清单中。门禁与 Gradle 配置缓存兼容。

该检查不能证明一个“项目原创”声明属实，也不能把第三方素材自动转成 GPL。
准备引入外部内容时，先固定原始提交或版本，逐批审核原作者、上游来源、
许可证及 APK 再分发权限，并在应用中提供必要署名和许可文本；审核与格式
扩展完成前，构建门禁故意拒绝外部内容。未审数据只保存在本机
`content/raw/`，不进入 Git 或 APK。
