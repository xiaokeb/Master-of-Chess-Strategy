# 兰州麻将真实宿主页面测试交接（2026-10-26）

新增源码冻结：是。仅新增 `app/src/androidTest/kotlin/com/masterofchessstrategy/games/lanzhou_mahjong/LanzhouMahjongProductionHostVisualTest.kt`，未修改已冻结生产源码、旧测试、统筹新仪器测试或共享文件。当前仅私有编译通过，未运行设备或查看图片；没有重新执行此前 88 项 JVM。

## 设备方法与证据路径

测试类：`com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongProductionHostVisualTest`。

| 方法 | 行为 | 3 个 PNG |
| --- | --- | --- |
| `realTableDiscardSavesNativeOpponentHistoryAndReturnsAfterRelease` | UI 线程构造真实 ViewModel，生产同 DB 工厂、真实 Native 与 Route；选择真实合法手牌并实际出牌，等待对手推进及完整 Room 保存，再由真实 Route 等待 leave 后返回 | `table-01-initial.png`、`table-02-saved.png`、`table-03-returned.png` |
| `corruptStoredRowShowsOnlyStableRecoveryErrorAndCanSafelyReturn` | 真实合法存档后故意损坏专属 UUID 行；恢复只显示稳定中文错误、无重开入口，重开调用不覆盖坏行，安全返回前后原行保持一致 | `corrupt-01-error.png`、`corrupt-02-preserved.png`、`corrupt-03-returned.png` |

设备截图位置为 `targetContext.getExternalFilesDir(null)/mocs-visual/<token>/`；token 必须从 instrumentation 参数 `mocsVisualToken` 获取并完整匹配 `qa-[a-f0-9]{32}`。不提供 token 或格式不符会在建立数据库之前失败，不允许回退任意目录。已有同名 PNG 不覆盖，重跑须使用新 token。

统筹设备脚本分别在普通/150% 字号运行相同测试类或两个方法；每个字号使用独立新 token，合计每字号 6 PNG。测试不更改字体、方向或系统设置，也不连接菜单。每目录另有 `table-metadata.json`、`corrupt-metadata.json`，记录实际资源 fontScale、动作计数及退休确认；图片仍须由统筹 pull、核对 SHA 并逐张查看，不把文件存在当视觉验收。

真实牌桌方法在初态验证手牌与底部打牌按钮经过滚动后可见；打牌后再次滚动检查手牌和实际当前动作区域（含回应/终局），避免假定 AI 后必然仍是出牌阶段。完整历史逐项验证本机真实出牌及至少一项他席 Native action，Room 的 acceptedMoveCount 和选择种子与实际新增动作数一致。对局图来自真实受控视图，没有 fake table 或旧 Controller。

`onBack` 仅在真实 Route 的 `VM.leave()` 成功后出现“已安全返回兰州麻将”确认页，并检查 retirement 回执成功。两个方法都在 finally 中于 UI 线程调用 `beginRetirement()` 和清理 ViewModelStore，后台等待该回执后才关闭/删除 UUID OwnedDatabaseFixture；关闭失败时不会强行清理数据库。没有访问或清空默认数据库的代码。

## 私有编译证据

```powershell
& .build/lanzhou-mahjong/check-host-visual-20261026.ps1 -CompileUi -OutputDirectory .build/lanzhou-mahjong/host-visual-20261026-r3
```

最终目录 `.build/lanzhou-mahjong/host-visual-20261026-r3/`：Kotlin/Compose 2.3.21、JDK 17.0.12、单 CPU/512 MiB 私有编译退出 0。源码仅两份：本代理新 VisualTest（2 方法）及只读统筹 `data/LanzhouMahjongProductionHostInstrumentedTest.kt`（2 方法）。未发现签名或类型错误；4 方法仅编译，未执行。

编译读取已经验证的 r5 宿主 jar，及 `.build/lzsp4/source/app/build/intermediates/built_in_kotlinc/` 下真实 `debug/compileDebugKotlin/classes`、`debugAndroidTest/compileDebugAndroidTestKotlin/classes` 作友元，不使用 fixture/database stub。当前与发布快照的 OwnedDatabaseFixture 源 SHA 均为 `af8ed4c3f6c0a83b46253de00eda4e7577f24e117ae25fdf13f89d38d7bafe98`。

- 新 VisualTest SHA：`bcb6f38651614f94bc7a768c69043d8da348c434b2f282d231b047de3889babb`。
- 统筹只读 InstrumentedTest SHA：`5dba70f52647664da361317b2d66e596eb03ffa2e8333ed2b889de7ce5bc0b6f`。
- 输出 `instrumented-compile-only.jar` SHA：`4d6732ca5c5c48a2622ef713915f930b4bf241e9b8c087c0fea5ad1a53123459`。
- 3037 份直接源/依赖/友元 class 输入前后清单相同，`inputs-before.json`、`inputs-after.json` SHA 均为 `45b54981820e4616f85691a53c89c05af675304b4c0d97233a8198a759ddcfbb`。

`compile.log`、`compile-receipt.json`、输入清单均在最终私有目录；[冻结元数据](freezes/host-visual-20261026.json)记录精确源码和状态。私有脚本首次拼接误截 helper 的 `$files`，在 Kotlin 启动前报 PowerShell ParserError；原失败脚本及 r1/invoke.log 已保留，修正后 r2 实际编译成功。统筹指出合法弃牌可能被 AI 碰/杠领取，r3 删除“弃牌仍在弃牌区”的脆弱断言，保留完整原生动作/Room 保存证明，再私有编译 4 方法通过。没有掩盖失败，也没有为此修改生产或旧测试。

后续由统筹将本文件和其工厂仪器测试纳入独立正式候选，运行 Gradle、实际 Room/JNI 与两档字号的设备/视觉验收。当前没有截图产物、正式 APK 或真机结果，不宣称这些验收完成。
