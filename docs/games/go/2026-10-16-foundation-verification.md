# 围棋基础节点精确候选验收

## 身份及范围

基线：`c3cc0dfc96dcaace045788476b38f5ff68173279`。最终候选：`.build/rc-go3/source`，普通源码快照，无Git worktree。1140份源文件，491个显式overlay；清单SHA `eca45c55753663d986a742489a3ed9891cfc7d296c7982f01be9ede872eb9dd9`，源码身份表SHA `7f81821c89bc7e63934a4dd77aaed8d30db504962240d2263c39c00f3d266c18`。

最终验证收据 `.build/rc-go3/verification-final.json`，SHA `308fc73c14820ba24b980a819eea0743c7ddb0a42ed47a23f5b084105b8f2e84`。阶段标签规划为 `v0.1.0-go-foundation.1`；远程提交状态另由统筹进度记录，不在未推送时宣告发布成功。

包含规则/SGF、历史适配、CPU搜索与JNI、模型完整性、许可页及准确源码/模型资产。排除高层Loader/Owner/Coordinator、AI v5/v6页面、自动续局、残局和其他棋种的新改动。完整围棋继续开发，不用此底层节点替代产品需求。

## 正式构建

在最终候选目录使用既有JDK17、SDK、NDK30 LLVM与离线Gradle：

```powershell
.\gradlew.bat --offline --max-workers=4 :engine-api:testDebugUnitTest --tests '*GoSearchRuntimeContractTest' :engine-native:testDebugUnitTest --tests '*Go*' :app:testDebugUnitTest --tests '*GoAiModelIntegrityTest' --tests '*OpenSourceLicensesAssetTest' :app:assembleDebug :app:assembleDebugAndroidTest :engine-native:assembleDebugAndroidTest :app:lintDebug
```

结果：212任务成功；JVM43/43（API12、Native22含虎羊既有6、App9），无跳过/失败；主及两测试APK含所需双ABI；Lint0错1提醒（虎羊既有EmptySuperCall）。日志 `.build/rc-go3/candidate-gradle.log` 与各模块JUnit XML，不以仅编译代替设备测试。

原生9测试目标复用rc-go2已完成的Gradle归档，仅增量编译测试，x86_64与arm64-v8a成功；x86_64在AVD全部退出0。覆盖规则、SGF、结算争议、让子、历史95组、真实搜索38组、运行时11组、共享Registry及虎羊回归。rc-go2到rc-go3的1140文件逐一对比，仅App许可仪器测试的滚动helper变化；全部原生源码、上游和构建配置完全相同，因此不重复原生搜索。原生收据SHA `292b76ce45302d2615397c308f197183622401e3fe0ae35e74b507881ba42cc0`。

## 最终包设备及视觉

Pixel_7_Pro / emulator-5554，Android37.1，x86_64 / 16KiB。经SHA核验安装最终包，未清应用数据：

- NativeGoEngineInstrumentedTest：3/3；双确认、让子等实际JNI。
- NativeGoRecordsInstrumentedTest：4/4；实际SGF与事件记录。
- NativeGoSearchRuntimeInstrumentedTest：8/8；真实模型、三路/让子、历史副本、请求上限、取消/并发关闭、错误模型拒绝。
- GoAiModelFilesInstrumentedTest：4/4；真实AtomicFile、小型自有夹具，不冒充NN。
- GoFoundationDistributionInstrumentedTest：2/2；实际48MB资产→AtomicFile→真实NN合法走法，以及实际依赖源码ZIP/模型许可字节。
- OpenSourceLicensesKataGoTest：100%/150%真实横屏各2/2；实际许可读取、源码保存按钮回调。两张3120×1440图已逐张查看，选项/保存按钮可达、文字无重叠；上部因正常滚动离屏不算截断。

最终APK资产13/13长度及SHA吻合，应用与Native仪器包的实际已安装base.apk散列均与本地一致，两个ABI库存在；aapt2确认无INTERNET。字体1.0、自动旋转1、方向0已恢复并回Launcher。

主APK SHA `cc0793c6695d5a968d2545c0adc33f55bc725da21d4b67282cb24064f2fb0d97`；Native仪器APK SHA `d54b6b05e15de6e65e04301df13890a51fe2db843dfe71c1d01b155772fd3535`。本次不上传APK附件。

## 修正及证据边界

rc-go1失败于摘取字符串漏xmlns:tools，仅私有片段补声明。rc-go2的普通/150%最初均为竖屏，补横屏时遇启动旋转竞态，稳定横屏后仍复现标签点击无效：旧测试只滚动最近的横向Row，没有先将Row从纵向页面滚入视口。修正为先滚父层、再滚标签，rc-go3真实横屏红项转绿；未为测试改产品行为。旧失败日志/图全部保留，不覆盖、不计通过。

原生测试首驱动正确编译双ABI但误在.cxx/tests查找可执行文件；改为实际AGP输出路径，仅补设备阶段。外部截图读取改用shell而非run-as的不可访问命名空间。以上均为验证驱动问题，不重跑已完成训练或业务以掩盖失败。

系统SAF目的地真实保存、实体/ARM64/API24设备、完整围棋玩法链、四档棋力、最终内容/美术音效尚未验收。静态审查/复制函数不冒充这些结果。项目总目标保持全部六棋种和正式规划功能，未完成。
