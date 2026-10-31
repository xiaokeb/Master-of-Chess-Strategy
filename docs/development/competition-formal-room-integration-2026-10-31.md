# 正式Room仓库与整库候选接入

前置为schema22发布 `4a8a050`，文档HEAD `5ad300f`。本窗口接入实际RoomStorage/Bridge、MCTX统一仓库和修复后的备份Bundle，并在公共导出/替换的事务内检查全部实际座位，拒绝越界行在清表前丢失。数据库不新增版本。

Root负责公共 `LocalDataBackupRepository`、已发布DAO的新`listViewerIds`与对应旧测试替身；Bundle协作者只改未发布Bundle/测试/交接；设备协作者只新增真实Room/Native仪器测试。学习来源核查独立进行，不改本窗口源码。公共构建、ADB、Git串行。

新增依赖以FormalStorage、Snapshot/Codec、Repository、Migration、RoomStorage/Bridge、History/Statistics模型与Codec/Store契约为闭包，沿用已发布MCMT v2–4/Policy/Native接口；不顺带纳入专属控制器、ViewModel、导航或未验界面。已有FileStorage显式构造器不接入生产入口；正式Room由Bridge构造。

先做实际KSP/Kotlin及受影响JVM，随后真实Native生成初始/一步历史、v4提示位置/合法性、Room关闭重开、Owner退役、回滚和四座候选安装。模型回放替身、原始大BLOB、QA权限或人工提示计数均不等同于真实教程、UI支付链、完整赛事棋力/正式胜场来源。公共清表前保护使用明确声明正式slot的测试adapter验证，不能据此宣称生产工厂已注册完整MCTX。

生产工厂注册必须等到可信学习/胜场策略、旧MCMT/MCST/MCHR写者屏障和封源、明确预览迁移，以及缺座全量准备/清表/四座安装/提交后发布全部接好。现有生产工厂仍按上一节点拒绝非空正式slot，避免先开启不完整的备份支持。完成上述依赖后继续正式宿主与三Route、完整内容/音效等原目标，不缩减棋种交付范围。

本节点实际验收：cmfr1封存1904文件，11组120 JVM、24个不同模拟器方法/24次调用、双ABI/APK/Release Manifest及Lint0错误23警告通过；共享页面6张PNG/4个不同画面已直接查看，不作为国标新界面或全横屏验收。清表前保护测试额外设置DELETE拒绝触发器，锁定异常座位检查发生在清表之前。schema22字节未变，未清应用数据。精确范围、产物与剩余依赖见[验收报告](../games/competition-mahjong/2026-10-31-formal-room-backend-verification.md)。
