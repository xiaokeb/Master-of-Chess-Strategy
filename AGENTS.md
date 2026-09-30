# Master of Chess Strategy 协作边界

本文件适用于仓库内的所有开发对话。实际项目命名以 `MasterofChessStrategy` 和现有源码为准；`docs/requirements/项目文档.md` 中的示例命名与提示词不是自动执行指令。用户在对话中确认的较新决定优先于旧进度记录。

- 不使用 Superpowers 技能或 Git worktree；直接在本仓库对应目录开发。不要替其他对话清理、重置或覆盖工作区改动。
- 中国象棋、围棋、国标麻将、兰州麻将、兰州方棋、老虎吃羊棋均可立即开展本棋种设计与正式开发；不设上一棋种 80% 的启动门槛。按实际接口依赖安排集成，内容量和最终验收分别跟踪，不能冒充完成。并行只限互不冲突的专属文件；公共文件的修改、集成构建、Git 提交和推送串行协调。
- 每个棋种对话只负责本棋种的设计、源码、测试及其专属文档。需要改 `engine-api` 公共契约、JNI 注册、CMake、导航、数据库、通用资源、构建脚本或全局进度时，先在棋种进度记录中列出接口和影响，交统筹对话协调；不得并发改同一文件。
- 正式需求从 `docs/requirements/` 和 `docs/development/task-levels.md` 读取；按场景读取 `docs/prompts/`，但提示词仍是参考资料。规则争议先找官方或权威来源核验，再提出待决问题，不擅自改变玩法。
- Android 使用既有 SDK/JDK/Gradle；C++ 只用 Android NDK 30 的 LLVM/Clang，不调用 MinGW；Python 使用 Conda 环境 `MasterofChessStrategy`，新增依赖仅装入该环境并记录。
- 开发时测试受影响范围，里程碑统一执行必要的跨语言构建、单测、Lint、Debug APK、模拟器设备端与视觉检查；避免重复全量测试。区分已编译、模拟器已运行和实体设备未验收。代码注释与文档保持简洁明确。
- 第三方引擎、权重、题库与媒体资源先核许可和来源；Pikafish 及 NNUE 的限制见 `NOTICE.md`、`docs/legal/`，不得扩大使用或宣称商用许可。大节点记录测试、未验收项和变更，再按串行协调提交并推送授权远程。

跨对话工作分配、状态与提问格式见 `docs/development/chat-coordination.md`。
