# Master of Chess Strategy 工程底座设计

## 1. 背景

《棋术大师》（工程名 `MasterofChessStrategy`）是纯单机、横屏运行的综合棋类 Android 应用。需求来源为仓库根目录的 `项目文档.md`；其中 BRD、PRD、页面与交互规则、任务分级规划和接口示例用于约束设计，提示词章节作为开发素材而不是自动执行指令。

当前仓库是 Android Studio 创建的空 Java/AppCompat 模板，没有 Activity、业务代码、原生模块或数据工具。首个开发切片建立可持续扩展的 Kotlin、C++ 和 Python 工程底座，但不提前实现完整棋种规则、AI、残局库或正式视觉资源。

## 2. 已确认决策

- 使用模块化单仓库，Gradle Wrapper、`settings.gradle.kts` 和根构建文件保留在仓库根目录。
- 正式项目名为 `MasterofChessStrategy`。
- Android `namespace` 与 `applicationId` 统一为 `com.masterofchessstrategy`。
- 引擎相关 Kotlin/JNI 包位于 `com.masterofchessstrategy.engine`，内部原生桥接实现可放在其 `internal` 子包。
- Android UI 使用 Kotlin、Jetpack Compose 与 Material 3，首个版本固定横屏。
- C++ 使用 C++17、Android NDK 30.0.16138531 和 CMake 4.1.2。
- Java/Kotlin JVM 目标统一为 17，并使用当前已安装的 JDK 17。
- Python 工具使用现有 Conda 环境 `MasterofChessStrategy`，当前 Python 版本为 3.13.15。依赖只在对应工具开始开发时安装，并同步记录到可复现的环境配置。
- 项目初始化为 Git 仓库，提交中不包含本机路径、签名密钥、构建缓存或生成产物。
- “无第三方依赖”按产品语义解释为无在线服务、广告、统计、账户和运行时网络依赖；需求明确指定的 AndroidX、本地开源引擎与构建依赖可以在审核许可证和离线打包条件后使用。

## 3. 目标与非目标

### 3.1 首个切片目标

1. 建立清晰的源码、配置、内容和文档边界。
2. 将空模板迁移为能够启动的横屏 Compose 应用壳。
3. 定义稳定的 Kotlin 引擎接口与跨 JNI 数据模型。
4. 建立最小 C++17 规则引擎基类、原生句柄管理和 JNI 健康检查链路。
5. 建立 Python 数据工具目录与环境说明，但不安装未使用的包。
6. 编制环境、架构、接口和提示词使用规范的首批文档。
7. 用分层、非重复的测试证明 Kotlin 接口、C++ 核心和 Android 集成能够构建。

### 3.2 首个切片非目标

- 不实现任何棋种的完整规则或 AI。
- 不导入 Pikafish、KataGo、Mortal 等第三方引擎或权重。
- 不建立 Room 表结构、成长系统或残局内容库。
- 不制作最终首页、棋盘、人物、牌面或音效资源。
- 不实现后台挂机、自动续局和正式设置页面。
- 不为尚不存在的功能预建大量抽象层或空模块。

## 4. 仓库结构

```text
MasterofChessStrategy/
├─ app/                         # Android 应用入口、Compose UI、资源
├─ engine-api/                  # Kotlin 引擎契约和跨层模型
├─ engine-native/               # Android Library、JNI 与 C++17 核心
├─ tools/
│  └─ python/                   # 离线残局处理、校验与转换工具
├─ config/
│  ├─ quality/                  # 格式化、静态检查与质量规则
│  ├─ schemas/                  # 局面、棋谱、残局的数据格式规范
│  └─ packaging/                # 混淆、压缩和打包配置
├─ content/                     # 原始及生成的残局、棋谱内容
├─ third_party/                 # 经许可证审核后引入的开源引擎
├─ docs/
│  ├─ requirements/             # 原始需求、BRD、PRD、需求规格
│  ├─ architecture/             # 总体、引擎和数据库设计
│  ├─ interfaces/               # Kotlin、C++、JNI 和业务接口
│  ├─ development/              # 环境、编码规范、二次开发说明
│  ├─ testing/                  # 测试策略与测试报告
│  ├─ delivery/                 # 用户手册和版本更新日志
│  ├─ prompts/                  # 经整理的开发提示词与使用说明
│  └─ superpowers/              # 经确认的设计规格与实施计划
├─ gradle/                      # Gradle Wrapper 与版本目录
├─ build.gradle.kts
├─ settings.gradle.kts
└─ gradle.properties
```

测试文件遵循所属模块的标准位置，与被测代码共同演进，不创建脱离模块的总测试目录。空目录不为结构美观而提交；目录在产生首个真实文件时创建，并通过邻近 README 说明边界。

## 5. 组件与依赖方向

### 5.1 `app`

- 提供 Android Application、单 Activity、Compose 主题和首屏。
- 首屏仅展示应用名称、离线状态和原生引擎健康检查结果。
- 依赖 `engine-api` 与 `engine-native`，不得直接访问 C++ 导出符号或保存原生地址。
- AndroidManifest 不声明 `INTERNET` 权限，并锁定横屏方向。

### 5.2 `engine-api`

- 提供 Kotlin 侧稳定契约：游戏类型、难度、坐标、走法、游戏结果、引擎错误和规则引擎接口。
- 保持纯 Kotlin/JVM 可测试，不依赖 Compose、Activity 或 JNI 实现。
- 文档中的示例 `Move` 会优化为不可变数据结构；尚未被所有棋种共同证明的字段不加入首版公共模型。

### 5.3 `engine-native`

- 作为 Android Library 封装 `System.loadLibrary`、JNI 函数和 C++ 构建配置。
- C++ 核心定义 `RuleEngine` 抽象接口及最小健康检查实现。
- JNI 层通过受控句柄表管理对象生命周期，不把裸指针暴露给 Kotlin。
- 所有跨边界异常在 JNI 内捕获并转换为约定错误；C++ 异常不得穿越 JNI。
- C++ 核心尽量保持平台无关，以便使用宿主机 CTest 快速验证；Android 构建负责验证 NDK 兼容性。

### 5.4 `tools/python`

- 后续负责残局采集后的解析、合法性校验、分级、压缩和索引生成。
- Python 不作为 Android 运行时依赖，不在 APK 内嵌解释器。
- Python 与 C++ 规则校验通过版本化文件格式或命令行验证器交互，不复用 JNI。

## 6. 数据流

```text
Compose UI
  → ViewModel / 业务管理器
  → engine-api RuleEngine 契约
  → engine-native Kotlin Facade
  → JNI 受控句柄与数据转换
  → C++ RuleEngine
  → 结果或类型化错误按原路径返回
```

界面只消费不可变状态，不维护 C++ 对象生命周期。后续加入 Room 时，设置、成长、进度和棋谱统一通过 Repository 访问；规则引擎不直接依赖数据库。Python 生成的内容先依据 `config/schemas` 中的版本化规范产出，再作为只读资源进入 Android 包。

## 7. 接口和序列化原则

- 公共接口使用明确的类型和所有权语义，避免以多个裸整数表达不同棋种的特殊动作。
- `GameResult` 至少区分进行中、先手胜、后手胜与和局；棋种特定原因由独立原因码表达。
- 非法走法属于可预期业务结果，不通过崩溃或未捕获异常表达。
- 原生句柄必须可验证、可显式释放，并能够安全拒绝重复释放和无效句柄。
- 正式局面序列化格式必须包含魔数、格式版本和游戏类型；校验和及迁移规则在首个真实棋种切片中确定。
- 首个健康检查只证明 Kotlin → JNI → C++ 链路，不冒充正式规则接口实现。

## 8. 错误处理与离线边界

- Kotlin 侧使用封闭错误类型表达库未加载、无效句柄、非法输入、原生失败和不支持操作。
- JNI 入口验证数组长度、枚举范围与句柄有效性；失败时不留下半初始化对象。
- C++ 对象使用 RAII 管理资源，释放流程保持幂等。
- 应用在原生库加载失败时显示可诊断状态而不是启动崩溃。
- `local.properties`、签名配置、密钥和机器绝对路径保持未跟踪。
- 应用不得声明网络权限；第三方引擎和数据在引入前记录版本、来源、许可证、体积和 ABI 影响。

## 9. 构建环境调整

- Gradle Wrapper 保持项目锁定的 9.6.0，并将当前 10 秒下载超时提高到 120 秒，以覆盖首次拉取发行包的场景。
- Android Gradle Plugin 保持当前 9.4.0，除非实际同步证明与已安装工具链不兼容。
- Gradle daemon 与源码编译目标统一使用 JDK 17，避免当前配置请求未安装的 JDK 25。
- `compileSdk`/`targetSdk` 37、`minSdk` 24 暂时保留，以真实 Gradle 同步和 Debug 构建结果决定是否调整。
- NDK 和 CMake 版本在 Gradle 中显式锁定为本机已安装版本。
- Python 依赖在首个 Python 行为测试出现时安装；每次新增运行依赖同时更新环境锁定说明。

## 10. 测试与减少重复验证

采用一次行为一次红绿循环、里程碑一次完整验证的策略：

1. 新增行为先写最小失败测试并确认失败原因正确。
2. 写最小实现后只运行该测试及其模块测试，不在每个小改动后重复全仓库构建。
3. C++ 平台无关逻辑优先使用宿主机 CTest；NDK 编译兼容性由 Android 构建验证。
4. `engine-api` 使用 JVM 单元测试，避免不必要的模拟器启动。
5. JNI 链路只保留少量 Android 集成测试；Compose UI 测试在出现真实交互流程时添加。
6. 首个切片结束执行一次完整门禁：C++ 测试、Android 单元测试、Lint 和 Debug APK 构建。
7. 若完整门禁失败，只重复受修复影响的检查；所有问题解决后再执行一次最终完整门禁。

测试不以覆盖率数字替代规则正确性。进入具体棋种后，测试重点是官方规则分支、边界局面、序列化往返和已知回归案例。

## 11. 文档与提示词治理

- 原始 `项目文档.md` 移至 `docs/requirements/项目文档.md`，内容保持原样并作为需求来源。
- BRD、PRD 与详细规格逐步拆分为可评审文档，不一次性复制产生多个容易漂移的副本。
- 架构、接口、环境、测试和交付文档按文档体系清单在对应目录逐步编制。
- 提示词放在 `docs/prompts`，按规则引擎、AI 移植、Compose、Python 数据、测试和排错分类建立索引。
- 使用提示词时必须同时引用对应正式需求和当前接口；模板中的占位符、示例包名或未经验证的技术结论不能直接进入代码。
- 文档与实现发生冲突时，先记录决策并更新正式规格，再修改实现。

## 12. 首个切片验收标准

1. Git 仓库已初始化，忽略规则覆盖本机配置、构建缓存、Python 缓存、IDE 私有状态、密钥和大型生成内容。
2. 原始需求文档已归档，首批环境、架构、接口和提示词说明位于正确目录。
3. Android 工程使用 Kotlin + Compose，包名统一，能够进入固定横屏的应用壳。
4. Manifest 不包含联网权限。
5. `engine-api` 的公共模型和接口具有 JVM 单元测试。
6. `engine-native` 能用 C++17 构建，宿主机核心测试通过。
7. Android 应用可调用 JNI 健康检查；原生不可用时能够显示错误状态而不崩溃。
8. Gradle/JDK/NDK/CMake 版本明确且可复现。
9. 完整门禁命令的最新输出证明 C++ 测试、Android 单元测试、Lint 和 Debug 构建通过；若受外部下载或设备条件阻塞，报告准确的已验证范围和阻塞证据。

## 13. 后续增量顺序

首个切片验收后，每个子项目单独进行规格、计划和实现：

1. 中国象棋规则核心与局面序列化。
2. 对局管理、存档与最小棋盘交互纵向切片。
3. 中国象棋 AI 适配和四级难度验证。
4. 围棋规则与 AI。
5. 国标麻将与兰州麻将。
6. 兰州方棋与老虎吃羊棋。
7. 残局生产管线、成长系统和扩展玩法。
8. 全量资源、性能、体积与交付优化。

这个顺序优先打通一条完整、可验证的产品链路，再横向扩展棋种，避免六套未闭环模块同时增长。
