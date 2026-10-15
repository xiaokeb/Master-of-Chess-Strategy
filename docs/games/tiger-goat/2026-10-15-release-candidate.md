# 虎羊独立源码节点验收（2026-10-15）

范围：发布基线`0adc314f5577676c574fd159d56f3ff33d9d8935`＋虎羊本地双人及必要公共依赖。候选`.build/rc-tg4/source`不是worktree，组装时主HEAD和索引不变；其他棋种运行代码及Go/desugar不纳入。发布形式为源码预发布`v0.1.0-tiger-goat-local.1`，不附APK。

## 源码闭包

658文件、119项显式输入，完整身份表SHA256 `50f0dc0635a821ceb98c7e7d4b23bf17b14b531edb2c1b689c1ca6b157a06e8d`；组装清单SHA `3a5f68d69d35c7525121049907014debe2f7e7f8d1ab78b2a22788d2e756a71a`。全部658文件构建后无漂移，119项与Git规范字节一致。公开[输入清单](../../releases/manifests/v0.1.0-tiger-goat-local.1.json)列出基线及各覆盖文件SHA；最终索引/导出必须逐份匹配，再允许提交。

菜单、默认backup registry仅注册实际包含的虎羊；NativeRegistry/JNI/CMake仅象棋/Pikafish/虎羊。schema13的国标Entity/DAO/12→13迁移保留是存储兼容，不等于发布国标功能；缺adapter库存时导出/恢复失败封闭，不清库。

首长路径候选漏汇编源、短候选法律通知大小写路径碰撞、初次最终构建未传SDK的失败日志均保留。Windows检出汇编CRLF与Git原始LF的差异已核明；最终使用上游LF blob，不把较早候选的通过冒充本候选证据。见[来源核验](../../legal/pikafish-source-closure-2026-10-15.md)。

干净导出曾暴露本机core.autocrlf依赖，未提交该失败候选；现固定导出参数core.autocrlf=false/core.eol=lf，并只为外部两份许可及汇编增加LF属性。导出工具14/14回归通过，不修改用户Git配置。旧候选/暂存证明的失败保留，不作最终通过证据。

## 定向验证

最终Gradle日志`.build/rc-tg4/candidate-gradle.log`：43秒、退出0。环境为既有JDK17/SDK、NDK30.0.16138531 LLVM、双ABI（arm64-v8a/x86_64），未用MinGW或新增Python依赖。

- App JVM127/127：备份编码/预算/registry、虎羊adapter与完整Controller/VM/Store/Geometry/强制停着、导航与菜单等15类；Native门面6/6。XML在候选各模块`build/test-results/testDebugUnitTest/`。
- 主/App仪器/Native仪器APK均建成；候选Lint0错误1提醒，仅EmptySuperCall，不压制规则或提高minSdk。
- 原生5目标各编译双ABI；x86_64规则12场景组、异常分配失败16次及Registry实际推送模拟器并执行退出0。回执`.build/rc-tg4/native-receipt.json`；拓扑与step是编译期断言，不冒充额外运行用例。
- Pixel_7_Pro / emulator-5554、Android37.1 x86_64、16KiB页。JNI5/5；公共事务5、虎羊磁盘/真实准入7、核心备份2、库存6、schema迁移1、真实停着Room1，共存储22/22。
- UI普通/150%各菜单2、真实Home/NavHost/Room/Native导航2、强制停着2，共12/12；22张3120×1440 PNG，14种SHA去重画面已实际逐张查看。棋盘、提示、恢复及停着/悔棋可用；150%右栏可滚动，不声称全部控件同屏。所有设置已finally恢复并读回，不清应用数据；测试只清各自UUID数据库。

设备仪器日志、APK身份及截图在`.build/rc-tg4/device/`；初始receipt的visualInspectionComplete=false记录自动测试刚结束时状态，人工目检另记最终验证，不回写历史收据。

## 验证包身份（SHA256）

- `app/build/outputs/apk/debug/app-debug.apk`：`35e71f840c19652391d7aa96ccb201e8d31f77e22eae98f7353000c12670b320`。
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`：`252aade1d188055981419f73ea6ef8c11a3de07e96e208637d6fdc4b433b0cf1`。
- `engine-native/build/outputs/apk/androidTest/debug/engine-native-debug-androidTest.apk`：`1a7e84adde31d1a7951faca3105ba10d394c54e838c6f8f1c6c71447e4c08d48`。

这些包只用于候选验收，不作为Release附件。原生实际执行文件：game `d5e21dfa8dc72f28cbd0be65259d99cf7868983e084dedc6b925f30b842dd0ce`，exception `944db83926366e684fb1b07beb33fbd76372544486e101bb9ac095cebe758663`，registry `111831403929899bc380b8a9c57e938d5ba1df5f1861e9805ff5ac3ae6dfef68`；设备SHA已与主机核对。

## 许可与未验收

根NOTICE/随包通知SHA均为`e3822cbfbf96af5c365a065e78476395b4657dcd0647c17b2acc4c97937383d9`。新任务验证Zstd LICENSE/COPYING固定SHA、完整BSD正文及所有通知副本一致；原版权和双选头保留，选BSD项，NNUE独立非商用边界不变，不声称完整产品法律验收。

本节点不包括虎羊AI/梯度、教程/残局内容量、成长/棋谱、完整美术音效、性能、真实杀进程恢复或实体设备验收；ARM64仅编译。Activity重建、磁盘reopen和杀进程恢复严格区分。已发布象棋基线保留，不发布工作区新规则/内容/AI评测；完整六棋种目标继续。
