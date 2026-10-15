# 虎羊强制停着与存档App切片

日期2026-10-12。16份专属App源及测试冻结于 [机器清单](app-freeze-20261012.tsv)。本切片只进行私有Kotlin/Compose编译及JVM验证；正式Gradle/APK、JNI设备、页面/视觉和导航验收仍由统筹串行安排。

## 行为

Controller只在当前羊方、已无待摆羊、PLAY且未终局、原生合法动作恰为一个 `PASS_GOAT(-1,-1)` 时开放停着。按钮调用直接使用返回的真实合法动作，未自行猜测无步或构造自由弃步；当前方有步、摆羊、虎步、连跳或终局不能停着。页面说明无步原因并提供“羊方停着”，保存/错误期间禁用。旧Native适配器按名称映射动作6，81字段及229字段拓扑不改。

ViewModel将停着作为普通规则动作即时保存，保存失败保留不可变待保存快照、锁住新操作、只重试同一快照；成功后再解锁，悔棋同样保存。操作发生异常时恢复动作前Native存档和会话ID，然后要求重新载入。RoomStore只替换原有棋种5单行，元数据格式版本必须与MOCT字节版本一致，可读v1/v2；保存写Native当前v2。浅层长度/魔数/步数校验后通过独立真实Native会话restore准入并关闭，不把格式头检查当规则验证。写入前复制数组，拒绝准入不覆盖旧行；没有修改Room公共schema。

新增专属Route消费ViewModel的载入、错误、保存重试及显式确认重开状态，向Screen绑定全部真实回调。未落盘动作离页时提示可能丢失并要求明确选择；重开仅在新开局保存成功后替换原行。公共导航/ViewModel工厂仍由统筹接入，本Route存在不表示首页已经开放。

## 私有证据

```powershell
& .build/tiger-goat-app-20261012/check.ps1
& .build/tiger-goat-app-20261012/check.ps1 -Instrument
```

既有JDK17、Kotlin/Compose编译器2.3.21和缓存依赖；Java限制 `ActiveProcessorCount=2`、编译内存1024MB，输出只进入私有目录。生产专属源/Route编译成功，五类JVM共16/16：几何2、旧Controller5、强制停着2、ViewModel4、Store3。测试覆盖停着/撤销、禁止自由停着、首次检查点未保存禁操作、停着保存失败原行保留及原样重试、动作异常回退、坏档禁操作、v1/v2元数据与所有权、Native准入拒绝/未知格式不触达写入。

旧Controller测试首次失败：点击另一己方虎会改变选择，且测试会话将普通步误转成连跳。仅修测试目标和假会话真实阶段历史，保留原断言；两次失败收据曾分别显示空动作列表及非空活动虎，修后16项通过。Store单测使用注入的准入器，不能据此称真实JNI已验。

三项仪器方法源码私有编译成功，复用现有公共schema13已编译数据库符号，仅有Compose v1测试API弃用提醒；并未运行。私有脚本首次引用旧Gradle目录导致找不到数据库类，按实际 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` 修正后编译通过，未改产品代码或启动Gradle。

## 统筹设备交接

- `TigerGoatForcedPassScreenTest` 2方法：真实Native合法分析初态→明确停着入口→换虎手→悔棋重现；正式开局无主动停着。可在100%/150%各运行，停着前后截图在测试包files的 `tiger-goat-pass-visual/blocked.png`、`passed.png`，应每次运行后先取图再改变字号，以免文件名覆盖。截图必须逐张检查。
- `TigerGoatPassRoomInstrumentedTest` 1方法：真实Native从v1局面重放→动作6写v2→真实独立RoomStore准入及读取→新Native恢复并悔棋→损坏包拒绝且原行不变。使用内存Room，只证明SQL/规则往返，不能称杀进程或磁盘重启恢复。
- 上述仪器需新公共JNI/CMake进入正式APK之后定向验；当前不清数据、不操作AVD。接公共导航后再补真实宿主/Room活动离页恢复及100%/150%视觉，不能用孤立Screen测试代替正式导航。

原生8份冻结本切片只读复核无变化；公共Native Kotlin尾追加动作6及默认真实桥由Root负责。未改公共导航/构建/数据库或其他棋种，未暂存、提交或推送。最新用户允许单棋种已验节点由统筹组织提交；本App切片仍需上述设备与集成证据后才能纳入该节点。
