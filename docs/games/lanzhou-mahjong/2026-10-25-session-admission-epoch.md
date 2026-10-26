# 兰州麻将：完整会话准入与存储代次门禁

本切片落实 [生产接入计划](2026-10-25-v2-production-integration-plan.md) 的两个独立前置。只新增专属Admission、EpochGate与同名测试；不改旧52项切片、Store、LZSR codec、VM、JNI、公共数据库/备份工厂或导航，尚未接生产。

Root审查后补充：`admitRow` 现先按 `LanzhouMahjongSessionRecordCodec.MAX_BYTES` 拒绝超限，随后才复制输入。原r1五件交接字节已保存在 `r1/frozen-source/`，旧收据保留；修订后的Admission与新严格Store在r2重新编译并执行27项定向测试通过，详见 [严格Store补验](2026-10-25-admitted-room-store.md)。下方15项为r1历史范围，不冒充修改后的独立证据。

## 严格会话准入

`games/lanzhou_mahjong/data/LanzhouMahjongSessionAdmission.kt` 提供：

- `admit(record)`：复制并重新执行已有LZSR结构校验，默认构造真实 `NativeLanzhouMahjongSession(seed,dealer,base,viewer)`，完整restore后serialize与原始LZMJ字节精确比较。Native会话由 `use` 在成功和异常时关闭。校验失败报错，不修复历史、不降级规则、不创建替代局；取消原样抛出。
- `admitRow(ActiveGameEntity)`：先复制外层字节，解析LZSR v1/v2，再检查game3、人机mode1、行envelope1、LZSR格式版本、难度、viewer、108张variant、原ID、实际动作数和非负保存时间。拒绝非默认其他棋种字段，随后才做实际Native准入。
- `createRow(record, updatedAtEpochMillis)`：先完整准入再生成新LZSR v2行；LZMJ原规则和完整原字节保持。它只是产生待写行，不写DAO或承诺事务成功。

ID完全保留旧Store算法：对LZMJ前122字节、viewer和难度做SHA256，加前缀 `lzmj-`，共69字符；动作数量及后续动作不进入摘要。原生规则字节进入摘要，新旧规则不会被静默合并。不能套方棋64字符上限或改为新UUID。

行的 `engineFormatVersion` 指向LZSR外层版本，不能与内层LZMJ计罚规则混淆。合法外层v1被解析后允许新写v2；不将新编码的v2外层与旧v1原行硬比较，因此不会误拒合法历史。原生v1/v2均须按各自规则重放和原字节重存，Native规范化成另一规则版本即拒绝。

既有行的 `undoUseCount/hintUseCount` 默认0，本切片严格保留该写法；真正消耗、决策点及选择种子始终来自LZSR，不用行默认0填补额度。若这两列出现非零值则报不兼容，不猜测采用哪份数据。计时、结果覆盖、和棋申请、自动演局字段必须保持既有默认值。Admitted record拥有独立数组与决策集合，输入行或注入校验器返回数组的后续修改不会改变结果。

## 数据库身份与退休

`LanzhouMahjongStorageEpochGate.forDatabase(database)` 按对象身份返回同一gate，使用弱引用键，不因两个数据库对象 `equals` 相等而共用所有权。Writer在创建时仅捕获一次 `Owner`；owner同时绑定gate身份和epoch，不能自行刷新身份绕过恢复。

`access(owner)` 在互斥锁内核验所有者后执行；`exclusive(replacing=false)` 供整段导出持锁，不改变epoch。`exclusive(replacing=true)` 先等待已进入的读写完成，再于替换块执行前递增epoch；替换失败或执行中取消仍保持新代次，旧内存写者退休，实际磁盘回滚由后续Room事务负责。尚在等待取锁时取消不会递增代次。达到Long上限会在替换前失败，不绕回旧值。

Gate不拥有Room事务、Native实例、备份注册或全局锁顺序。Root仍须按公共装配确定新兰州gate与现有方棋/围棋gate的固定顺序；Prepared备份对象不能重入同一gate或另开事务。这里的epoch不是持久化版本、存档授权或棋力证明。

## 定向验证

2026-10-25使用既有JDK17、Kotlin2.3.21、协程1.11.0与缓存依赖，单CPU/512MiB私有编译**13份当前真实源码**成功，随后首次执行新增**15/15 JVM**通过，耗时0.164秒、退出0；13份源前后SHA相同。未使用旧测试jar作为编译类型，不定义公共类型替身。

- Admission 9方法：新旧LZMJ及各viewer/旧ID算法，LZSR v1准入和只升级外层、辅助/决策种子保真、23种不符元数据/他棋字段在Native前拒绝、损坏/未来外层拒绝、结构合法但假Native拒绝、跨规则字节改写拒绝、输入/返回数组独占、取消及负时间。
- Gate 6方法：同数据库对象身份、foreign owner、失败恢复退休、导出不退休、已入写入排空后替换、未取锁取消不退休。

Native默认路径仅实际编译；Admission测试全部显式注入fake canonicalizer，结构夹具**不证明JNI或合法动作重放**。没有运行Gradle、NDK、ADB、真实Room、页面或Git，也不重复执行旧52项或累计成67项整体验收。

证据根 `.build/lanzhou-mahjong/admission-epoch-20261025/r1/`：`sources-before.json`、`dependencies.json`、实际编译/测试argv及退出收据、compile/jvm日志、tests.jar和handoff冻结清单。JVM日志SHA256 `fc6e6f20c05c882e5fc13e1028eb19b9d6a46871903e45a1a7954eaa67227f70`；Java SHA256 `630e8a854c2e854daca340e2c99ea6ce7ec8a9e848be4df746e2e22cdfab0f3a`。私有目录不是干净源码前置。

下一由Root审查后连接严格RoomStore、事务读回及取消回滚、同数据库gate与全局备份锁序，再用真实JNI/Room验证旧8倍/新24倍、辅助额度、损坏行保留、恢复退休与跨进程。当前仍不能开放尚无学习资格依据的四档入口、覆盖恢复失败行或宣称完整棋种/正式生产已完成。
