# 围棋搜索公共契约 v1

2026-10-09，统筹负责 `engine-api/GoSearchRuntime.kt`。本节点定义公共类型和所有权要求，**尚无公共 JNI 实现，不开放人机入口**。私有真实 NN 搜索和运行时的结果见 `../testing/2026-10-09-go-ai-replay-integration.md`；不能用接口测试代替实现验收。

## 输入与输出

- `GoSearchBudget`：1–512 visits、1–20000 ms。单搜索线程；时间是软截止，包含局面转换/初始化，正在执行的 CPU 推理可能延后返回。加载模型不占每着预算。
- `prepare(snapshot, budget)`：输入完整 MOCG，不是可变活动引擎或删掉历史的棋盘。返回前深拷贝；不接受空、超过 1,000,000 字节、损坏、未知规则或非 PLAYING 存档。格式/阶段检查允许在 `run` 中执行，但须在搜索前完成。上限 16 个尚未释放请求，超限抛异常，不悄悄丢请求。
- `GoSearchResult.Move`：仅 `GoAction.Place` / `Pass` 携带 `GoSearchMetrics`；已完成 visits 1–512，并须不超过请求预算。坐标/合法性须按该快照规格验证，不能仅靠 Kotlin 类型判断。
- `Cancelled` / `Busy` / `BudgetExhausted`：不携带候选；不足以形成有效搜索结果时不得制造虚着。运行时一次只允许一项搜索，冲突立即 Busy、不排队。
- 耗时非负且有限；白方胜率 0–1；白减黑点数有限。这些是搜索诊断，不是数子终局或已标定棋力。不自动认输、标死子、确认结算或恢复行棋。

双虚着仍进入本项目双方死子协商。搜索前恢复完整历史，并用本项目规则独立验证最终候选，不改活动局。异常应明确失败，不伪装成 Busy、Cancelled 或成功；值对象使用 `IllegalArgumentException`，关闭后的调用/重复 run 使用 `IllegalStateException`。格式或原生引擎失败的映射及原因保留须在 JNI 节点落实，本节点不新增异常类型。

## 生命周期与 JNI 实现要求

1. `GoSearchRuntime` 拥有已加载网络；`GoSearchRequest` 独立、一次同步 `run`。新请求分配新取消标志。Runtime 关闭后既有未关闭请求的首次 run 返回 Cancelled，不进入 NN；Request 自身已 close 或重复 run 则拒绝。
2. `cancel()` 幂等、非阻塞，仅设置请求原子标志，不等搜索互斥锁。`cancelAll()` 仅影响当时已登记请求，不污染后续 prepare。
3. Request `close()`：取消、等待自己的 run 返回，再释放登记。Runtime `close()`：关闭准入、取消全部请求、等待所有 run 返回，再销毁搜索器/网络线程；重复或并发 close 幂等。close/加载/run 都在 worker 执行。
4. JNI 注册表对每次调用先取得 Runtime/Request **强持有引用**。不能在另一线程仍使用外壳时删除裸指针。原生已验证的共享 state 不能代替 JNI 外壳生命周期保护。
5. App 用会话 ID、单调局面版本和请求 identity 拒绝迟到候选；取消回调在同步 run 前挂接，返回后先 detach 再释放 request。取消回调不可阻塞/抛异常。消费候选一次；保存成功后才推进界面，失败不能丢掉旧局。
6. JNI 返回格式、稳定状态编码与严格解码须另写实现及测试：拒绝非法状态、越界坐标、超预算 visits 和非有限诊断。不要把本页当作已经存在的 wire 协议。

## 集成边界与验证

本次不改围棋专属 App/规则、公共 JNI/CMake 或导航，不添加模型到 APK。固定版本、SHA、内部网络身份沿用 `config/games/go/katago-probe-lock.json`；依赖/模型许可、源码及版权分发门禁仍单独验收，不宣称全项目商用许可。

公共值对象测试覆盖预算上下界/溢出值、诊断值、软截止超时、只准落子/虚着与无候选分支、资源限额。接口声明本身不能证明深拷贝、取消/关闭或句柄无竞态；这些须由后续真实 JNI 测试验证。下一依赖为资产/许可分发门禁、共享构建目标、强持有 JNI 注册/解码、模型加载状态与专属 App 适配，随后才接正式入口。
