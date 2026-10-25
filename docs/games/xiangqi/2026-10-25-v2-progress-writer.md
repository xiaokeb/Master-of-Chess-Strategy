# 象棋预算v2：原生进度writer

本切片属于 [G0无搜索前置](2026-10-24-calibration-v2-next-plan.md)，实现 [Python进度契约](2026-10-25-v2-progress-contract.md) 的原生写端。仅新增三份C++源/头/测试和本文；没有接入任何入口、adapter、旧runner、CMake、注册或真实choose。本轮代理未运行编译、单测、NDK、ADB或搜索；Root须在精确源码冻结后完成实际构建和互操作验证。

## 接口与所有权

`ProgressWriter`构造时复制外部`ProgressContext`，并引用调用者独占、已打开的二进制`std::ostream`。writer不创建文件、不接管或关闭sink，不启动进程、搜索或槽位。调用者须保证sink和writer寿命，且只串行调用；禁止复制/移动writer以免复制序号和所有权。构造复制分配失败可抛出，尚未写sink；构造后的事件API为`bool noexcept`。

显式方法为`run_started`、`game_started`、`request_started`、`request_returned`、`game_finished`与`run_finished`。调用者提供非负int64单调时间；writer固定身份、序号和计数。诊断为1组、恰好cold/warm两请求、ply均0；训练为12局六个既定唯一作业身份，保留集为96局三个pair。训练/保留集request_index与ply同步，从1开始，每局上限600。无请求的终局声明可以写成侧写观测，但是否真的合法终局交正式结果审核。

身份严格匹配Python的ASCII名空间、长度、大小写SHA和pair集合，不接受引号、换行、非ASCII或任意附加JSON。整数使用`to_chars`，不受sink locale影响。每行是精确schema字段加原生LF，无CR、可选结果、授权、真实go/join或NNUE初始化字段。

## 失败与边界

每事件先校验状态和时间、复制下一状态，再拼完整单行，检查4096字节行上限及128MiB总上限（可收紧总上限），随后`write`、`flush`并检查流状态。只有全部成功才提交序号/状态/确认字节计数。外部context、状态、倒退时间、容量、拼行分配、短写、flush或stream异常均锁住第一个`ProgressWriteError`，以后方法返回false且不再写。修复sink不会隐式重试；析构不补回包、结束或其他记录。

`bytes_written`只计write和flush都确认成功的记录，不承诺失败sink实际零字节：短写可以留下断尾，flush失败也可能已经写出完整LF行。调用者必须保留原始输出和writer错误，不能回滚文件、重写、继续换writer补齐或只凭侧写语法宣布整槽成功。`sidecar_ended`只是已确认写出的侧写结束；它不授权搜索、接受棋力或开放下一槽。

本实现仅输出调用者给出的观测时间；不能认证时钟、运行闭包、进程归属或真实choose时点。未来producer必须在完整choose调用前后显式调用并检查bool；本切片没有producer运行证据，也没有终止进程的权限。

## 测试源码与互操作约定

默认测试源码有12组，覆盖完整诊断/逐行flush、外部context复制、身份/容量拒绝、析构保留未完、非法状态和首错锁定、在途请求配对、时钟/计数、短写/flush/抛异常、确定性bad_alloc、96×600最大流、零请求与跨局复位、locale无关整数。最大流使用计数sink，不积累全部JSON；全局分配失败注入只在专属测试可执行文件中，生产writer没有该钩子。这些是待执行测试，不能把源码数量计为通过。

测试程序支持单一参数输出确定性替身流供Root与Python观察器互操作：

| 参数 | 外部固定context | 事件规模 |
| --- | --- | --- |
| `--fixture-diagnostic` | purpose diagnostic，pair diagnostic，expected_games1，maximum_requests_per_game2 | cold/warm两次，共8记录 |
| `--fixture-training` | purpose training，pair M0-hard，expected_games12，maximum_requests_per_game600 | 每局3次，共98记录 |
| `--fixture-holdout` | purpose holdout，pair easy-medium，expected_games96，maximum_requests_per_game600 | 每局600次，共115394记录 |

三个fixture均固定`protocol_id=calibration-budget-v2-<purpose>-writer-fixture`、`protocol_sha256="a"×64`、`runtime_identity="b"×64`、`run_id=toy-run-1`。时钟从0逐事件加1。这些值只存在于测试夹具，不是注册规格、采样seed、参数卡或实际作业。fixture模式仅向stdout写JSONL，失败向stderr说明并非零退出；不混入默认测试汇总。Root应提供上述独立固定context读取完整原始二进制流，不能从输出自报身份反构context。

许可沿用项目GPL-3.0-or-later；没有新增第三方依赖、权重、媒体或数据。实际构建、运行、故障和精确SHA由Root后续验收记录补充；不改变原21项Python冻结源码或旧评测事实。
