# 虎羊本地双人节点：正式集成与模拟器验收

本报告更新 10 月 12 日“已编译、待运行”的状态，不改写历史收据。范围为 B 型棋盘规则、真实 JNI、本地双人入口、Room 即时存档、离页/重建恢复、备份准入与事务回滚；不是整棋种完成，也不是标签源码发布验证。

## 环境和源码

NDK 30 LLVM/Clang、既有 CMake 4.1.2、JDK 17、离线 Gradle；未用 MinGW，未新增依赖。Pixel_7_Pro / emulator-5554，Android 37.1 Google Play x86_64、16 KiB 页；ARM64 只编译，未在实体设备执行。

结束时 `native-freeze-20261012-oom.tsv` 9 份、`app-freeze-20261012.tsv` 16 份、`shared-backup-navigation-freeze-20261012.tsv` 16 份逐项 SHA 匹配，0 漂移。这 41 份是核验身份，不是发布依赖闭包；公共 Registry/JNI/CMake 等仍须精确审查。

## 正式测试

未写完整目录的日志位于 `.build/tiger-goat-integration-20261013/`。以下均为实际执行结果。

| 范围 | 结果 | 收据 |
| --- | --- | --- |
| App 定向 JVM | 75/75，0 失败/错误/跳过 | `app/build/test-results/testDebugUnitTest/TEST-*.xml`；`gradle.log` |
| Native Kotlin 门面 JVM | 6/6 | `engine-native/build/test-results/testDebugUnitTest/TEST-com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSessionTest.xml` |
| 双 ABI 主/测试 APK | ARM64、x86_64 构建成功 | `gradle.log`、`engine-instrumented-build.log` |
| 最终测试 APK / Lint | 构建成功；0 错误、16 既有提醒 | `gradle-landscape-controls-fixed.log`；`app/build/reports/lint-results-debug.xml` |
| 原生状态机、异常、Registry | 12 场景、16 分配失败注入通过，三个程序退出 0 | `native-tests-run.log` |
| 真实 JNI | 5/5 | `engine-jni.log` |
| 磁盘 Room / 备份 | 5/5 | `.build/instrumented-class/fa9a3dffe6fa4507b1dd002de88fdc85/instrumentation.log` |
| 停着 / Room | 1/1 | `.build/instrumented-class/da6ce6cb8e204f89ad047c4e8620fe22/instrumentation.log` |
| 横屏停着页面，100% | 2/2 | `.build/instrumented-class/39f1e5f6a7e946dfabaf3f99d34b5c5e/instrumentation.log` |
| 真实导航，100% | 2/2 | `.build/instrumented-class/e50a4583eac5479bb99bc85b33131b66/instrumentation.log` |
| 横屏停着页面，150% | 2/2 | `.build/instrumented-class/bf38c7bf094441cc8a64c4ed71dbd4e3/instrumentation.log` |
| 真实导航，150% | 2/2 | `.build/instrumented-class/4b682636499a44769a09aad10717522d/instrumentation.log` |

App 75 项由备份 26、专属几何/停着/控制器/VM/Store 16、导航 29、恢复屏障 1、写入注册表 3 组成，没有重复其他棋种全量测试。JNI 包括坏档、句柄、并发释放和旧格式兼容；备份包括混合数据重开、Native 拒绝保留旧库、坏元数据导出、旧 v4 替换和 SQL 失败回滚；导航包含真实棋盘触控移羊、落盘、离页重入/悔棋、同数据库 Activity 重建及控制区滚动可达。

最终主 APK SHA-256：`38b7df5a0e75df4f4f8836fd266edf9a8a06ff62afdebef636bdc43d9f8a43c4`；最终 App 测试 APK：`938625e19f87fcb7848d485a223cdcd1f04708b9470f6a880c9c6f2b01d4cf72`。它们来自混合工作区，只是本次运行身份，不可附到单节点 Release。

## 视觉证据

实际 PNG 位于 `.build/tiger-goat-integration-20261013/visual-100/` 与 `visual-150/`，各 9 张，均为 3120×1440。页面组为 `pass-{blocked,controls,passed}.png`；导航组为 `navigation-{opening,removed,restored,recreated,controls-restored,controls-recreated}.png`。

不同图像逐一核验棋盘/棋子完整、状态文字换行、合法落点环、停着前后状态、可用/禁用按钮和恢复画面。恢复、重建图与同状态移羊图 SHA 相同，不将相同画面当作额外功能证据。150% 初始右栏下部按钮在视口外，滚动后认输完整显示且启用；停着及悔棋均可点击，无需缩小字号或删除文案。

尺寸及散列清单 `visual-evidence.json` SHA：`3a1ffb2528892d72995cae31db92721cf5225d1a464dbba49190cb9cd1799b4e`。截图从目标应用 `cache/tiger-goat-*-visual/` 经 `exec-out run-as ... cat` 二进制读取，不依赖外部存储权限。

## 失败修复和数据边界

首轮 Lint 的 composition 内 StateFlow.value 改为已有 collectAsState。Room 初轮四项在清理未创建的惰性数据库时失败，改为检查自建库存在才删、最终断言库及 sidecar 消失；业务断言未改，五项复测通过。另删除前次失败测试自建的三份 UUID 临时库，正式数据库未动。

截图先因测试包 filesDir 权限失败，改用目标包 cacheDir 并检查创建结果；随后功能虽通过但截图竖屏，不能验收。最终 Activity rule 明确请求横屏、等待配置且断言宽大于高；补充滚动可达断言后，两组各两项、两种字号均通过。原失败收据保留，没有据夹具问题更改生产规则。

没有 pm clear 或卸载。磁盘/导航测试只使用自建隔离 UUID 库。原设置已恢复并读回：font_scale=1.0、accelerometer_rotation=1、user_rotation=0；回启动器，保留 APK 和正式棋局。

## 发布与未验收

本节点混合工作区模拟器集成通过。只读发布审计发现传统菜单静态调用方棋 AI、备份静态依赖国标 Room/native validator；41 份冻结不是可独立编译闭包。必须完成共享前置节点准入，或正式解耦注册并验证准确候选源码，再显式提交、推送及建立附注 tag/prerelease。不删除备份/真实导航来换取更小的发布；不把上述绿色结果直接套到标签源码。

虎羊 AI/梯度、自动演局、教程、残局及正式内容量、成长/棋谱、完整美术音效、性能、真实杀进程恢复、实体设备仍未验收，来源与许可继续按本棋种规格及 NOTICE 执行。象棋 hard-master 原运行已停止，13 局前缀不构成最终结论，续测另行预注册。
