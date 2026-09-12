# 中国象棋 JNI 业务链路计划

目标：让 Android 业务层通过类型化 Kotlin 接口安全使用 ChineseChessEngine，不暴露原生地址或 JNI 细节。

## 边界

- engine-api 定义中国象棋阵营、棋子和棋盘查询接口。
- engine-native 提供 NativeChineseChessEngine，实现 RuleEngine<BoardMove>。
- NativeBindings 保持 internal，仅声明基本类型 JNI 方法。
- C++ 使用受互斥锁保护的句柄表；句柄是不可推断标识，不是指针地址。
- close 幂等；关闭后的调用返回固定 Kotlin 异常，不访问已释放对象。
- JNI 捕获所有 C++ 异常，错误消息不得包含堆栈或本机路径。

## 协议

- 阵营：红方 0、黑方 1。
- 棋子：将帅 1、士 2、象 3、马 4、车 5、炮 6、兵卒 7。
- 空位为 0；黑方棋子增加 0x80 标记。
- EngineError：成功 0、非法动作 1、无效状态 2、损坏数据 3、不支持 4。
- GameResult：进行中 0、先手胜 1、后手胜 2、和棋 3。
- 合法着以四个整数一组返回，顺序为起点 x/y、终点 x/y。

## 验证

1. JVM 测试使用内存 Fake bridge 验证编码映射、句柄传递、幂等释放和关闭后拒绝调用。
2. C++ 测试目标验证句柄创建、查找、释放和无效句柄。
3. Android 集成测试调用真实 JNI，验证初始棋盘、走子、悔棋和序列化。
4. 无设备时只执行 JVM 测试、NDK 编译链接和测试 APK 编译，不宣称集成测试运行通过。
