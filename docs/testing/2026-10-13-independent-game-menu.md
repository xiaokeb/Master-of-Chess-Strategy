# 传统棋菜单独立注入：验证记录

按独立发布计划正式解耦，不删除现有模式。Root NavHost 仍按方棋本地、简单/中等/困难/大师 AI、虎羊的顺序注册；填充/描边样式、文案、原本地标签和路由不变。共享 Screen 不引用 Difficulty、方棋类或专属资源，只消费不可变条目快照与公共选择/返回文字。方棋菜单文案和虎羊标题分别归入专属资源文件，没有搬动其他对话源码。

TraditionalGameMenu 在构造时拒绝重复 id/tag、不合法身份和空文案，拥有不可变副本，不在渲染时执行回调。Compose 使用稳定 id key；Root 捕获各自 difficulty，不把全部 AI 按钮误接同一档。单棋种候选可只注册虎羊，未注册方棋不产生按钮；完整版本保留全部既有入口。

## 收据

- 红：`.build/traditional-menu-20261013/model-red.log`，新增接口缺失的编译失败。
- 绿：`integration.log`，菜单 JVM 7/7、现有导航 29/29，0 失败/错误/跳过；双 ABI Debug 主/测试 APK构建成功，Lint 0 错误、16 既有提醒。Root当轮逐类读取 JUnit XML核实，后续定向Gradle会替换默认报告，不能把最新单一测试报告当本轮全部结果。
- 普通字号菜单 2/2：`.build/instrumented-class/af859ff5a24c46b49439efa59fc9e9db/instrumentation.log`。
- 150% 菜单 2/2：`.build/instrumented-class/b79d2cae92f34f249e2b5c3716857fcb/instrumentation.log`。
- 虎羊真实导航普通及150%各2/2：分别 `.build/instrumented-class/9cea78d8e9a849778f4c21b130ecc28d/`、`e2fe28cdcf0f4f93b958ac7df2f47616/` 的 instrumentation.log。
- 既有方棋本地入口1/1：`.build/instrumented-class/226454fbee5942fda090fdca6233a758/instrumentation.log`；只证明首页到本地棋盘，不证明四档AI强度或完整方棋功能。

菜单测试使用无数据库/引擎的 ComponentActivity，明确请求横屏且截图断言宽大于高；虎羊导航沿用 UUID 隔离 Room 宿主，未清正式数据。普通/150%各两张 PNG位于 `.build/traditional-menu-20261013/visual-100/`、`visual-150/`，均3120×1440。only-tiger图只显示实际注册虎羊及返回；injected-pair图证明两种按钮独立显示/回调；逐图核验文字、按钮及横屏布局。它们是注入夹具图，不是把完整工作区其他棋种隐藏后的生产首页。

源码身份见 `docs/development/freezes/traditional-menu-20261013.tsv` 八份清单。Root共享16仅更新已解冻菜单/App两项，其余14及App16/native9不变；新源码变化不套用迁移前虎羊报告。菜单相关测试完成，未重复原生规则/OOM/JNI专项。

## 剩余边界

备份注册迁移仍需完成；当前Codec/Repository仍静态依赖国标/Tiger归档。公共菜单解耦不证明精确标签源码完整，也不使混合APK变成发布产物。后续准确tree验证、显式提交/推送/tag/prerelease仍由Root串行执行；完整六棋种目标、实体/性能/内容/梯度验收保持。
