# 象棋音效运行时验收

2026-10-12接续10月11日收据。五类正式WAV由生产SoundPool完成解码，每类一次 `play` 均返回非零stream id；系统音频历史按本应用的唯一player id核对至少五次started，并在关闭后出现release，关闭后的play返回false。专项1/1，收据 `.build/instrumented-class/f54dcbc0d39d47c9aca6897a8d8f24c8/instrumentation.log`，SHA-256 `1f45ad2f41402fe10e85e20715fcdb2c984bd427cbc9385c3c57c488dcb169cb`。

源码保持一致：播放器SHA-256 `6261f6c59c97bbc31e6e6da0e37d6fe59784b759f4f7329c0c0dc0be95674a3e`，仪器测试SHA-256 `734584843bca2593965fd39a8657987759e89ea3271d0080ac04748e54edfcf7`。主APK SHA-256 `3cf0697f57146491e19d838ef89e3ea9fda9555867c541a94b7f8b1a746466c2`，测试APK SHA-256 `69001b7527c0a1108e7b10541954f04e39d2e4472f99b38bff075f9b2c3c11f1`。

10月11日走子/吃子/终局事件JVM类15/15；10月12日补执行上轮因审批额度中断的最终Lint，成功，0错误16提醒。当前模拟器已关闭，没有重复执行已经通过的设备专项。

边界：系统有内置扬声器，音乐流未静音且音量5/15；started/release证明Android接受和释放播放，不能证明宿主扬声器实际声音或人耳听感。首次解码待播、设置关闭中断和重新开启不补播仍需下一切片验证。第一次公开播放回调测试超时，Android服务及AudioFlinger显示播放已发生；改用系统只读收据后通过，保留失败日志。

## 音效开关修复与最终验收

同日检查发现：旧Compose绑定关闭开关只跳过新事件，已在SoundPool解码中的待播仍可随后响起。现由专属 `ChineseChessSoundPlayback` 管理加载、最新待播、开关和关闭；禁用时清除待播并调用SoundPool `autoPause`，启用时不调用 `autoResume`，下一条新事件才可播放。`ChineseChessSoundEffect` 在Compose提交设置变更时同步开关，离开界面释放资源；公共App仅委托原有音效绑定。五类WAV、业务事件、设置格式和规则未改变。

新文件位于 `app/src/{main,test,androidTest}/kotlin/com/masterofchessstrategy/games/xiangqi/`：main的Playback与SoundEffect、JVM PlaybackTest及设备LifecycleInstrumentedTest。原播放器仍在既有game目录，原五类设备测试保留原路径。

- JVM：新调度状态6/6，加既有走子/吃子/终局事件15/15，共21/21，失败、错误、跳过均0。覆盖首次解码待播、仅保留最新待播、禁用清空/停止、重新开启不补播、解码失败和关闭幂等。
- 生产Compose＋真实SoundPool开关/离页专项1/1：初始关闭和运行中关闭均不产生本player的started；重新开启不补播；两个新事件分别新增一次started；离页出现同player release并取消事件订阅。收据 `.build/instrumented-class/f024c2d692724f8abb618c672152a424/instrumentation.log`，SHA-256 `a6ba476c16912c00951563999176520e879723159f89b4f4a3b50fb40aa06166`。
- 修后五类SoundPool真实调度/释放回归1/1：收据 `.build/instrumented-class/3b7a03f481f4423088d3ddf28d13c1ae/instrumentation.log`，SHA-256 `e8c3642b3a109ade7a3cd08610e1532fecd5419360476b089db1b740d9d4c785`。
- 双ABI Debug APK、AndroidTest APK成功，最终Lint0错误16提醒。主APK SHA-256 `e6fe9c65a9cdfe18169d691ff6093d401f30600bccd8c14a5f69f83563458f0c`，测试APK SHA-256 `9263e6c033617e5ba3e3307363c22553a9ffcd1bc4db5e03d40a4dfe27c7be8f`。源码及两次设备收据均对应这一修后版本。

首轮生命周期专项在事件消费时超时，日志显示播放直到测试清理时才发生。原因是Compose v2的测试调度器没有在系统收据轮询期间推进；测试在发事件后调用 `waitForIdle` 同步，再按相同断言通过。保留失败收据 `.build/instrumented-class/5d5da6e217f3413587dbe7b44db4d628/`，未据此修改生产播放逻辑。

系统字体1.0、旋转1/0、模拟器回启动器，两包保留，未清空或卸载数据。`git diff --check`通过；国标自动目录冻结14份仍0差异；暂存为空。没有界面图形变化，沿用既有页面视觉记录。设备专项确认Android播放控制，真人音色/宿主扬声器试听和实体设备体验继续待验。

## 独立源码节点发布

用户同日取消六棋种共同到节点的提交门禁。本次音效节点使用显式路径及共享App的局部暂存，未包含AI评测、规则修订或其他棋种的工作区变更。精确暂存树导出的Player/Playback/SoundEffect及HEAD既有真实象棋契约/UI状态，使用本机已有Kotlin2.3.21、Compose编译器、API37和既有资源符号轻量编译成功，PlaybackTest再次6/6；收据 `.build/releases/audio-index-0fa5539baef04bbebf707f1512b30ffc/junit.log`。首轮检查仅因检查脚本错误指定未安装API36和遗漏象棋契约失败，修正检查路径/依赖后通过，生产源码未改。

该验证不是标签全App重建或设备重跑；以上双ABIAPK与设备证据来自混合工作区。发布 `v0.1.0-xiangqi-audio.1` 只提供源码，不附该工作区APK。发布说明见 `docs/releases/v0.1.0-xiangqi-audio.1.md`。
