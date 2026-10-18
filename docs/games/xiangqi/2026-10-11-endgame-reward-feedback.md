# 残局首次与重复通关目录反馈验收

日期：2026-10-11。Pixel_7_Pro x86_64 16K 模拟器、NDK 30 LLVM、JDK 17、离线 Gradle。本节点补齐35关正式目录的首次/重复奖励可见性证据；生产奖励事务、文案、资源和存档格式均未修改。

## 范围

在 `XiangqiEndgameGameFlowInstrumentedTest` 新增一个真实流程用例：以生产 ViewModel、JNI、已核 NNUE 的 Pikafish、Room 事务和目录 Screen 完成 `xq-easy-001`，再恢复同一已提交终局触发生产幂等回调。用例验证：

- 首次完成显示“星级和积分已发放”，目录变为1/35、1星/10分，下一主线关可挑战；
- 第二次完成显示“不重复发放奖励”，Room仍只有一条残局进度，汇总仍为1星/10分；
- 两次都经 `CompletedGameSessionRepository` 终局提交和 `refreshAfterCommit` 返回目录，不以直接构造UI状态冒充业务流程。

## 结果

- AndroidTest与双ABI Debug APK构建成功。
- 普通字体方法1/1，收据 `.build/instrumented-class/2c897c549e594c7db2669ed175ddea9b/instrumentation.log`，SHA-256 `90a88bbfb2f59d405882c522bffbc27e04755aa9832fa4053a5fe7dde3b20739`。
- 系统150%字体方法1/1，收据 `.build/instrumented-class/2ef4af6243954ee6914f29ea356f2cfb/instrumentation.log`，SHA-256 `79cedce52d2eab5b7307c8e4d7c6fe339359d3cfae0e8971f990b16fd2a0facd`。
- 普通与150%各保存首次、重复两张图至 `.build/xiangqi-endgame-reward-20261011/`，四图已逐张查看。提示、汇总、完成状态及下一关状态可读，无文字重叠。150%横屏左栏按既有设计纵向滚动；滚动到反馈后标题不与反馈同时处于视口，不把这一正常滚动状态称为同时可见。
- 最终Lint为0错误、16提醒；没有本节点新增错误。

测试源码SHA-256为 `64c8941d6929ccce8bdee2943d097e2c9325093e31da5718135ec9c076a82b32`。主APK 268,728,825字节，SHA-256 `ae8e822aff5694530c54032bf380375721b7dbfd6b2c01d1c08c8b62f2a49762`；测试APK 2,342,531字节，SHA-256 `7c7fa3f962c20aaccffa26c706883043270485e2e65a43b7b76a77dd9d65d278`。

## 边界与收尾

本节点证明完整35关目录能正确显示首次和重复通关反馈，不增加35关中已完成实际棋力/趣味性评审的关数。象棋四档AI梯度、真人音效试听、职业长循环样例及最终内容规模仍独立跟踪。

未清空或卸载应用数据。两包保留安装；系统字体恢复1.0、自动旋转1、用户旋转0，模拟器回启动器。本轮两个已拉取的UUID临时截图目录已从模拟器精确删除，本地构建证据保留。
