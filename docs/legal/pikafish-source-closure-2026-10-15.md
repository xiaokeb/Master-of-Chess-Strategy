# Pikafish 干净源码与外部许可核验

固定引擎提交`4c17cee11f888ae1d48a9494f2e2239f019f0a1f`，本地只读缓存`.build/pikafish-upstream`的HEAD、官方origin及干净状态已核实。上游132份文件在主目录全部存在；归档修正前Git仅漏一份汇编源，其他差异为已记录network.cpp失败边界及本地ignore例外/UPSTREAM记录。

## 唯一构建源遗漏

`src/external/decompress/huf_decompress_amd64.S`的固定上游Git blob为`dc1f3d9211353446e5d76ee665ead9922ad63655`：LF原始文本19676字节、SHA256 `9c430fa95e7ff8bda568efd2bdadb16d17cc02e476bc8daa7cc0d87c28504279`。本地与只读上游缓存的Windows检出文件均为CRLF：20442字节、SHA256 `50b8a19c8220bc09f61570e516894102c3894d2f137b7d40df10e445a66f6615`。二者只差766个CR，不应把检出文件SHA与Git blob混称。最终候选采用原始LF文本，与Git保存及导出字节一致。

Windows的ignore-case使原`**/*.s`也排除了大写`.S`；只加该文件窄例外，不改算法、不删x86_64、不覆盖旧tag。最初两个短候选使用Windows检出字节，构建/设备结果保留但不冒充最终LF候选结果；最终候选须按自身源码补验。

短路径首候选已经双ABI构建、App111/Native门面6测试和Lint0错误1提醒通过；它此前尚缺以下许可正文，不据此发布。新的许可候选须重新验证。

## Zstandard：选择已提供的BSD选项

external内Zstd、FSE/HUF和适配xxHash头均保留原BSD/GPLv2双选声明；本集成选其BSD选项，额外保留xxHash的Yann Collet归属，不用顶层Pikafish GPLv3正文代替外部许可。原头所指LICENSE与COPYING已补入external根目录。

许可正文取证固定到官方[facebook/zstd](https://github.com/facebook/zstd)提交`49cf51799ea051457f57243527aa2058f999f005`：

- [LICENSE](https://raw.githubusercontent.com/facebook/zstd/49cf51799ea051457f57243527aa2058f999f005/LICENSE)，1549字节，原/本地SHA256 `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8`。
- [COPYING](https://raw.githubusercontent.com/facebook/zstd/49cf51799ea051457f57243527aa2058f999f005/COPYING)，原18091字节，SHA256 `f9c375a1be4a41f7b70301dd83c91cb89e41567478859b77eef375a52d782505`；本地18092字节/SHA `8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643`，逐字符比较只多文件最后一个LF，许可文字无改动。

固定Pikafish的zstd.h标1.6.0，但未记录独立原始Zstd提交；上述commit只证明许可文本，不冒充源代码版本。实际全部解压源仍由Pikafish固定提交追溯。

完整选定BSD正文在根NOTICE及APK通知页，构建检查两份正文hash、通知包含完整BSD文本、根NOTICE与随包文档逐字节一致。首次新任务因doLast捕获rootProject而不满足configuration-cache，保留失败后改配置阶段捕获File，任务通过；未关闭configuration-cache或跳过校验。

这不代表所有棋种、所有库或完整产品的法律验收完成，不扩大NNUE商用权，不声称上游背书。完整混合版本的新增desugar与围棋模型分别登记；只含已发布象棋基线＋虎羊的候选不带这些未发布运行模块。
