# 象棋预算 v2：原生准入节点验收

本节点完成无搜索原生入口、冻结输入桥、实际 NDK 构建及模拟器身份拒绝验证。候选 `xbva2` 从提交 `4efad87c260c07ec20d00ac51018ca1ef2036660` 干净导出，增加15份源码/配置/测试，冻结1575份文件；发布另附本报告、许可说明、发布说明和机器清单四份元数据。节点 tag 为 `v0.1.0-xiangqi-budget-v2-admission.1`。这不代表 AI 梯度或完整象棋完成。

## 输入与复现

入口只接受 `validate-plan <protocol> <rule> <search> <spec-sha> <exposure-sha>`，校验编译时五项绑定、实际 MOCX 4/1 序列化和四档正式参数结构。没有训练、比赛或搜索命令，不加载 NNUE。错误输入退出2且不输出成功 JSON。

- 当前规格：`config/games/xiangqi/ai-evaluations/calibration-budget-v2-20261024-portable-admission.json`，SHA256 `138f3b0bafdbf0a254e1c3e79da488745dc722be4cfc9701ec4f52cfb3e627a9`。
- 输入清单：同目录 `budget-v2-native-entry-portable-inputs-20261024.json`，SHA256 `bbc9b709ef3f6f9f055f6233131e8ea446e97cebfd82481374ec1c3538ef4453`。
- 规则身份 `46c3ddbe25eae7c23351d603b055bd76fd0bea8aa647c4ad86a79cf1365bb3f6`：6份规则源/头，包含真实 `checking_mate_proof.cpp`，不能只绑定公开声明。
- 搜索源码身份 `f4c06e469d7bcd64e18ddde8110eb334653cb0d193139961e77f2914c3f66a92`：4份适配/档位文件与完整106份 Pikafish 文件。预算修复锚点另行固定。
- 旧曝光清单 SHA256 `2b3e65b22c430816f35dbde189cc160b3258c27dac400659adca812e0f5206d2`；由既有66+48条来源重建114个左右镜像等价类。没有生成新保留集、注册槽位或重新利用已曝光局面。

此前工作区构建 `native-r1-20261024` 与12项设备自检已经通过，但发布预检发现32份 Pikafish 文件在主工作区为CRLF、Git干净归档为LF。逐文件比较证明仅换行不同。没有修改旧五源规格、原始结果或主工作区第三方文件；另行审核上述 portable 规格/清单，并在新候选实际重编、重新验证。旧搜索身份 `8defd221…` 与首轮 ELF `00ea8386…` 不冒充本节点最终输入/产物。失败候选 `xbva1` 和首轮证据均保留。

复现时从 tag 以 `git -c core.autocrlf=false -c core.eol=lf archive` 导出，保留归档字节；在导出根目录运行工具。普通Windows checkout的自动换行转换可能改变JSON/PowerShell原始字节，此节点只验收上述干净归档流程。不要通过归一化已冻结文件、重签旧规格或复用旧库使检查通过。历史五源规格随标签保留，旧准入CLI默认读取它而安全拒绝；当前构建使用显式新规格与其独立SHA，不要求旧CLI变成成功。

构建工具 `tools/games/xiangqi/build_budget_v2_entry.ps1` 必须传入规格、曝光清单、输入清单的相对路径与已审SHA，NNUE路径 `app/src/main/assets/pikafish/pikafish.nnue`、NDK绝对路径和新的独占输出目录。输出目录须为导出根下 `.build/xiangqi-calibration-budget-v2-20261022/` 的一个新子目录；当前实际子目录为 `native-portable-r1-20261024`。只读桥可执行 `check` / `verify-after`，后验须额外提供实际收据路径与事先保存的原始SHA。

## 实际环境与构建

使用现有 `E:/Backend_Env/SDK/ndk/30.0.16138531`，实际包版本为 `30.0.16138531-beta3` / `r30-beta3`，Clang SHA256 `46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be`。校验器原先仅接受无后缀版本，已修为四项包属性精确匹配并补负例；不是安装或更换NDK。无MinGW、额外Python依赖或SDK修改。

Root真实执行41次编译（4核心、36 Pikafish、1入口）、2次新归档、1次链接、1次只读readelf，共45条命令。x86_64/API24、C++17/O2、whole-archive，独立新库，不引用旧 `.a`、JNI或围棋库。固定5件LLVM工具、5313份sysroot和482份Clang资源；清空隐式工具环境，显式指定资源、sysroot与链接器。

原始输入及冻结副本在读取锁下逐字节绑定；从副本编译，收据记录实际argv、cwd、开始/结束、退出码及输入/输出摘要。独立只读工具重新核对41份 `.d` 依赖闭包、源/头、工具、45条命令、对象、归档、ELF及日志；真实后验退出0。哈希收据用于复核一致性，不宣称能抵御恶意宿主或单凭JSON证明执行。

最终 ELF 为6,671,032字节，SHA256 `40cc4f58968d5e847bb6e62f4f07469c5379f068f663fa8590baa8ae6324791e`；构建收据 SHA256 `4b90632d942013b64a067752819e36bd6060ffb0763cf71934afb6ac24e57481`。构建工具本身始终不执行ELF，其收据的 `native_executed=false` 只描述构建阶段。

## 测试与证据边界

- 干净候选使用 Conda `MasterofChessStrategy` 执行 `python -B -m unittest discover -s tools/games/xiangqi -p 'test_budget_v2_*.py' -v`，71/71通过，52.697秒，1575份源码前后不变。覆盖曝光、真实规则闭包、历史规格、完整输入身份、实际读取边界、路径/重解析、精确beta属性、拒绝时不建目录、读取锁、命令/依赖后验等；夹具不冒充Native运行。
- 同最终ELF在 Pixel_7_Pro / API37 / x86_64 / 16KiB页模拟器执行12项：1项完整身份成功；错误协议、规则、搜索、旧spec、曝光、大写摘要、短摘要、`run`、`go`、缺参和多参共11项均退出2，无成功JSON。
- 设备与本地ELF摘要一致。独立设备收据 `plan_entry_executed=true`，同时 `search_performed=false`、`strength_accepted=false`、`network_loaded=false`；未清应用数据、未启新比赛、未附APK/ELF。
- 本节点无App/JNI/CMake/资源/生产档位修改，公共运行代码与基线逐文件相同，沿用基线已有集成证据；没有重复Gradle全量、UI视觉或真人音效测试。无新界面，设备专项也不计作App可玩、实体设备或棋力验收。

本地证据根 `.build/xbva2/`：`source-identity.tsv`、`tests-r1/{unittest.log,execution.json}`、`device-r1/{commands.json,complete.json}`；实际构建证据位于 `source/.build/xiangqi-calibration-budget-v2-20261022/native-portable-r1-20261024/`。完整干净索引/导出证明在 `index-proof/receipt.json`。发布机器清单记录各证据SHA；大体积本地日志不是Release附件。

## 后续

下一节点须先完成有限预算行为诊断、事前新协议、候选/训练曝光和独立保留集生命周期，再单独判断是否允许新比赛；当前 `search_authorized=false`、`strength_accepted=false`。原medium-hard异常槽、旧结果及旧库保留，不停止或补跑旧作业、不据局前缀推断梯度。完整自然限着/专业棋例、内容质量与数量、AI梯度、音效体验/性能及实体设备仍分别验收。
