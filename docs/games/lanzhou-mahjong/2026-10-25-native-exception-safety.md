# 兰州麻将：单步与悔棋强异常保证

Root同行审查发现真实P1：旧 `LanzhouMahjongGame::apply` 先perform改变牌面，再分别push历史和动作；任一次分配失败都可能留下变化后的牌面及缺失的动作/悔棋记录。旧undo复制历史State时也可能在中途分配失败，原异常遗留的不等长栈还可能空pop。此前lzsp1/lzsp2及独立设备结果不能证明该边界安全，本节点尚未发布。

本次只解冻 `games/lanzhou-mahjong/lanzhou_mahjong_game.cpp`，新增独立 `exception_test.cpp` 和本说明；不修改Admission/Store/Adapter、10个仪器方法、公共接口/CMake/JNI或旧候选源。原game.cpp精确字节另存 `.build/lanzhou-mahjong/exception-20261025/original/`，旧证据保留。

## 生产修复

- valid_action只读检查后，先复制**当前一个State**作为before；该复制若失败，游戏尚未变更。没有复制整个Game或其既有历史来事务化。
- perform返回false或抛出时，使用无抛move将before恢复；已成功追加的本步history/actions在catch中撤回，然后原异常继续抛出。
- perform成功后先追加POD动作，再将before move进history。history扩容失败发生在消耗before之前；State无抛move成功后没有后续可抛操作。既有history扩容移动State，不逐层复制所有历史的向量内容。
- 用 `static_assert` 固定State move构造/赋值及动作复制的无抛性质。undo在空栈或两栈大小不符时安全拒绝，否则move历史尾State再pop两栈，不复制可能分配的State。

回滚仅覆盖本步State及本步栈增量。bot选择不改变Game，`advance_other_seats` 依次通过相同apply边界提交；整段调用异常时允许保留此前成功步骤，失败那步必须没有半成品。不能把这项保证写成整段bot调用原子性；App异常后如何记录已提交前缀及选择种子仍需其宿主单独验收。

## 独立故障测试

`exception_test.cpp` 仅供独立可执行目标，替换全局new/new[]，**不得链接JNI或应用**。它先测量一次真实动作的实际分配次数，再从第0处分配起逐点抛bad_alloc，核对每个预期点确实抛出。输入覆盖：初始弃牌、非空历史待响应、最后一个pass解决响应、报听弃牌、暗杠、新规则炸胡、合法旧v1炸胡、已发生暗杠后的炸胡。夹具完整牌墙/库存合法；旧v1从零动作合法初态恢复后才真正执行旧规则动作。

每个失败后比较精确serialize、四家手牌/副露/弃牌/响应/等张/合法着、计分/终局及完整undo轨迹；再实际restore原记录，并重试同一合法动作到无故障参考状态。单独在零可用分配预算下执行undo，要求实际分配次数0。bot用实际逐步执行的前缀作对照，失败状态须精确匹配某个已提交前缀，随后以正确已提交步数偏移种子继续到相同参考状态。

perform失败返回路径的move回滚已逐行核对；本测试不伪造私有State来强迫整数越界，也不宣称已运行每一种付款返回false路径。原生现有底分范围、溢出及新旧计罚测试仍须与新候选共同回归。

## 当前证据：只编译，未执行

指定NDK30 LLVM/Clang SHA256 `46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be`；x86_64-linux-android24、C++17/O2、严格告警及 `-UNDEBUG`、静态libc++、16KiB对齐。r2使用工作区内显式对象路径，6次编译及2次链接均退出0；10份源码/头前后身份相同，State静态性质检查实际编译通过。

| 独立产物 | SHA256 | 待Root验证 |
| --- | --- | --- |
| fixed.elf | `ef63b4b50e5a399db74fa6f623e4ce1f1d43ab475928498bb093fc01abb11418` | 预期退出0及所有实际故障点检查通过 |
| original-negative.elf | `6de360315ee7aa15d4a1719d2f669da79655c1d074aef3c2e07ba8724ecb719d` | 同一测试链接保留的原game.cpp，预期退出1揭示缺陷 |

这两个ELF尚未运行，不能写故障点数或动态通过数。首轮r1驱动因沙箱外TEMP的.o.tmp重命名权限被拒而失败，原日志保留；改为r2显式-c/-o对象路径后才编译/链接成功，不把工具失败算作测试失败或通过。只编一次的4个公共对象由正反两产物共用，其余分别链接fixed/原game对象。

证据根 `.build/lanzhou-mahjong/exception-20261025/`：original源与摘要、r1冻结10输入/失败日志、r2完整8命令/退出/产物摘要/编译汇总及交接清单。没有ADB、Gradle、Git、搜索、数据清空或应用运行。Root须另建完整候选，实际运行正反例、当前原生回合/计分/bot及真实存储/备份方法后，才可评估发布。
