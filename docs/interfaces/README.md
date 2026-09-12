# 接口文档

接口按三个边界维护：

1. Kotlin 公共契约：游戏类型、难度、动作、结果和规则引擎生命周期。
2. JNI 边界：类型转换、句柄所有权、异常转换和原生库加载。
3. C++ 核心：规则、序列化和 AI 的平台无关接口。

稳定枚举编码一经用于存档或 JNI 后不得重排。C++ 异常不得跨越 JNI；非法操作必须转换为明确的业务结果。

## Kotlin 公共契约

- GameType 和 Difficulty 的 code 是 JNI 与持久化协议的一部分，只能追加，不能重排。
- PlayerId 与 BoardPosition 在构造时拒绝负数；具体棋盘边界由棋种实现验证。
- RuleEngine 负责一个游戏会话，实现拥有原生资源，调用方在会话结束时调用 close。
- apply 和 restore 使用类型化结果表达可预期失败，不以异常表达非法走法。

## 原生健康检查

NativeEngineStatusProvider.check 是应用当前唯一直接使用的原生入口。成功时返回 Available(protocol)，动态库加载失败时返回固定诊断“Native library could not be loaded”，运行错误返回“Native engine health check failed”。底层异常消息、堆栈和本机路径不得进入 UI。

原生库名为 mocs_engine_native，当前协议字符串为 MasterofChessStrategy Engine/1。JNI 函数只调用平台无关的 mocs::engine::health_check，并在边界内捕获所有 C++ 异常。

## 当前边界

健康检查只证明 Kotlin、JNI 与 C++ 链路可连接，不代表任一具体棋种规则已经实现。新增棋种必须使用类型化 Kotlin 接口和受控句柄，不得从应用层直接调用 NativeBindings。

## 中国象棋核心

首个 C++ 切片提供 ChineseChessEngine，并实现统一 RuleEngine。EngineAction kind=0 表示棋盘移动，四个参数依次为起点 x/y 和终点 x/y。坐标、棋子稳定编码和当前边界见 ../requirements/chinese-chess-rules.md。

局面格式当前为 96 字节：MOCX 魔数 4 字节、版本 1 字节、当前方 1 字节、90 个棋盘点位。空位编码为 0；棋子低 3 位为 PieceType 1..7，最高位表示黑方。未知版本返回 unsupported，长度、魔数或编码损坏返回 corrupted_data，无将帅或重复将帅返回 invalid_state。

此格式为内部版本 1，尚未承诺与第三方 FEN 互转。接入真实存档前必须增加往返、迁移和损坏输入回归样例。

### Kotlin/JNI 实现

- ChineseChessRuleEngine 位于 engine-api，提供统一规则方法和 pieceAt 棋盘查询。
- NativeChineseChessEngine 位于 engine-native，是当前生产实现；构造时创建原生句柄，close 幂等释放。
- 公开方法在同一实例内串行访问句柄，关闭后的调用固定抛出“Engine session is closed”。
- NativeBindings 与 ChineseChessBridge 保持 internal，UI 和业务代码不得依赖它们。
- C++ NativeEngineRegistry 使用递增整数句柄和互斥锁管理共享对象，不把地址转换为 long。
- release 删除句柄映射；正在执行的调用持有临时共享所有权，因此并发释放不会造成悬空访问。

JNI 只传递 Long、Int、IntArray 和 ByteArray。合法着数组每四个整数表示一个 BoardMove；Kotlin 验证数组长度、坐标和编码后才构造公共类型。未知协议编码视为原生协议错误，不静默降级为其他枚举值。
