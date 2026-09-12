# Master of Chess Strategy 工程底座实施计划

> **执行约束（2026-09-12）：** 按用户要求在 main 分支直接实施，不使用 worktree 或 Superpowers。步骤清单保留为验收依据，测试合并为受影响模块检查和里程碑门禁。

**Goal:** 建立可构建、可测试、纯离线的 Kotlin/Compose + C++17/JNI + Python 工具工程底座。

**Architecture:** 保留 Android Gradle 工程在仓库根目录，应用、Kotlin 引擎契约和原生引擎分别作为 app、engine-api、engine-native 模块。Python 只承担离线数据生产；界面通过类型化 Kotlin 接口和受控 JNI Facade 访问 C++。

**Tech Stack:** AGP 9.4.0、Gradle 9.6.0、JDK 17、Kotlin 2.3.21、Compose BOM 2026.08.00、Material 3、C++17、NDK 30.0.16138531 LLVM、CMake 4.1.2、JUnit 4、Python 3.13.15。

**Spec:** docs/superpowers/specs/2026-09-08-project-foundation-design.md

## Global Constraints

- 项目、namespace 与 applicationId 分别固定为 MasterofChessStrategy 和 com.masterofchessstrategy。
- 引擎 Kotlin/JNI 包固定为 com.masterofchessstrategy.engine。
- 应用固定横屏，不声明 INTERNET 权限，不加入账号、广告、统计或在线服务。
- JVM 目标为 17；C++ 标准为 C++17；NDK 与 CMake 版本显式锁定。
- Python 使用 Conda 环境 MasterofChessStrategy，只在出现实际功能时安装并记录依赖。
- 注释只说明公共契约、所有权、线程、安全或不直观的设计原因，不复述代码。
- 文档使用短句、明确标题和可复制命令；原始需求保持原样。
- 每项行为只执行一次 Red → Green；开发中运行相关测试，节点末尾统一全量验证。
- 每个任务本地提交；任务 2、4、6 完成后检查远程并推送。没有 origin 时保留本地提交并请求远程 URL。

---

### Task 1: 仓库边界与文档归档

**Files:**
- Modify: .gitignore
- Move: 项目文档.md → docs/requirements/项目文档.md
- Create: docs/README.md
- Create: docs/requirements/README.md
- Create: docs/architecture/overview.md
- Create: docs/interfaces/README.md
- Create: docs/development/environment.md
- Create: docs/testing/strategy.md
- Create: docs/prompts/README.md
- Create: docs/delivery/README.md
- Create: config/README.md
- Create: content/README.md
- Create: third_party/README.md
- Create: tools/python/README.md

**Interfaces:**
- Consumes: 已批准规格与原始项目文档。
- Produces: 稳定目录职责、环境基线和需求来源路径。

- [ ] **Step 1: 扩充忽略规则**

加入 Gradle/Android、CMake、Python、IDE 私有状态、签名文件、环境文件和生成内容规则。忽略 local.properties、.gradle、build、.cxx、.externalNativeBuild、__pycache__、.pytest_cache、.venv、*.jks、*.keystore、content/raw 与 content/generated。

- [ ] **Step 2: 归档原始需求并创建目录说明**

原始文档只移动不改写。各 README 明确“存放什么、不得存放什么、何时更新”。environment.md 记录 JDK 17、SDK 37、Build Tools 36.0.0、NDK 30.0.16138531 LLVM、CMake 4.1.2、Conda 环境，以及当前无 Android 设备/AVD；C++ 不调用 MinGW。

- [ ] **Step 3: 建立提示词使用索引**

为规则引擎、AI 移植、Compose、Python 数据、测试和排错提示词建立到原文的链接。规定先引用正式需求与接口、替换模板占位、审核许可证和版本、验证所有输出。

- [ ] **Step 4: 验证归档**

Run:

~~~powershell
Test-Path docs/requirements/项目文档.md
rg -n "^## 一、业务需求文档|^## 二、产品需求文档|^### 2.2 页面规划与交互规则|^## 三、任务分级规划" docs/requirements/项目文档.md
git status --short
~~~

Expected: Test-Path 为 True，四个标题均命中，local.properties 与构建缓存不在状态中。

- [ ] **Step 5: Commit**

~~~powershell
git add .gitignore docs config content third_party tools
git commit -m "chore: organize project foundation"
~~~

### Task 2: Gradle 多模块与 Compose 构建骨架

**Files:**
- Modify: settings.gradle.kts
- Modify: build.gradle.kts
- Modify: gradle.properties
- Modify: gradle/wrapper/gradle-wrapper.properties
- Modify: gradle/gradle-daemon-jvm.properties
- Modify: gradle/libs.versions.toml
- Modify: app/build.gradle.kts
- Modify: app/src/main/AndroidManifest.xml
- Delete: app/src/test/java/com/example/masterofchessstrategy/ExampleUnitTest.java
- Delete: app/src/androidTest/java/com/example/masterofchessstrategy/ExampleInstrumentedTest.java
- Create: engine-api/build.gradle.kts
- Create: engine-api/src/main/AndroidManifest.xml
- Create: engine-native/build.gradle.kts
- Create: engine-native/src/main/AndroidManifest.xml
- Create: engine-native/src/main/cpp/CMakeLists.txt

**Interfaces:**
- Consumes: Task 1 的目录边界与环境基线。
- Produces: :app、:engine-api、:engine-native 三个 Android 模块及锁定工具链。

- [ ] **Step 1: 配置版本目录**

固定 agp=9.4.0、kotlin=2.3.21、composeBom=2026.08.00、activityCompose=1.13.0、lifecycle=2.10.0、junit=4.13.2；增加 com.android.library 与 org.jetbrains.kotlin.plugin.compose 插件别名。Android 模块使用 AGP 9 内建 Kotlin，不应用 org.jetbrains.kotlin.android。

- [ ] **Step 2: 配置根工程与模块**

settings.gradle.kts 加入 :engine-api 和 :engine-native。根构建声明 application、library 与 Compose Compiler 插件。Compose Compiler 使用 Kotlin 2.3.21。

- [ ] **Step 3: 配置应用与原生模块**

app 使用 namespace/applicationId com.masterofchessstrategy、compileSdk/targetSdk 37、minSdk 24、Java 17、Compose 和 Material 3，并依赖两个引擎模块。Manifest 增加 exported=true 的 MainActivity、screenOrientation=landscape，不增加 uses-permission。

engine-api 使用 namespace com.masterofchessstrategy.engine.api。engine-native 使用 com.masterofchessstrategy.engine.nativebridge，锁定 NDK 30.0.16138531、CMake 4.1.2 和 cppFlags=-std=c++17。

- [ ] **Step 4: 修正工具链配置**

Wrapper networkTimeout 设为 120000；Gradle daemon toolchainVersion 设为 17。不得提交 sdk.dir 或 JDK 绝对路径。

- [ ] **Step 5: 验证模块解析**

Run:

~~~powershell
$env:GRADLE_USER_HOME = (Resolve-Path ".").Path + "\.gradle"
.\gradlew.bat projects
~~~

Expected: BUILD SUCCESSFUL，并列出 app、engine-api、engine-native。

- [ ] **Step 6: Commit and milestone push**

~~~powershell
git add settings.gradle.kts build.gradle.kts gradle.properties gradle app engine-api engine-native
git commit -m "build: establish android module structure"
git remote -v
git push -u origin main
~~~

Expected: 有 origin 时推送 main；没有 origin 时停止 push 并记录需要用户提供 URL。

### Task 3: Kotlin 引擎公共契约

**Files:**
- Create: engine-api/src/test/kotlin/com/masterofchessstrategy/engine/EngineContractTest.kt
- Create: engine-api/src/main/kotlin/com/masterofchessstrategy/engine/EngineTypes.kt
- Create: engine-api/src/main/kotlin/com/masterofchessstrategy/engine/RuleEngine.kt

**Interfaces:**
- Consumes: :engine-api Android Library 和 JUnit。
- Produces: GameType、Difficulty、PlayerId、GameResult、GameAction、BoardPosition、BoardMove、ActionResult、RestoreResult、RuleEngine。

- [ ] **Step 1: 写稳定编码与输入约束的失败测试**

~~~kotlin
class EngineContractTest {
    @Test fun difficultyCodesRemainStableAcrossJni() {
        assertEquals(listOf(0, 1, 2, 3), Difficulty.entries.map { it.code })
    }

    @Test fun gameTypeCodesAreUnique() {
        assertEquals(6, GameType.entries.map { it.code }.toSet().size)
    }

    @Test fun boardPositionsRejectNegativeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            BoardPosition(x = -1, y = 0)
        }
    }
}
~~~

- [ ] **Step 2: 运行测试并确认 RED**

Run: .\gradlew.bat :engine-api:testDebugUnitTest --tests "*.EngineContractTest"

Expected: FAIL，原因是 Difficulty、GameType 与 BoardPosition 尚不存在。

- [ ] **Step 3: 实现最小公共模型**

EngineTypes.kt 使用以下完整模型：

~~~kotlin
enum class GameType(val code: Int) {
    CHINESE_CHESS(0), GO(1), STANDARD_MAHJONG(2),
    LANZHOU_MAHJONG(3), LANZHOU_SQUARE_CHESS(4), TIGER_AND_GOAT(5)
}

enum class Difficulty(val code: Int) {
    EASY(0), MEDIUM(1), HARD(2), MASTER(3)
}

@JvmInline
value class PlayerId(val value: Int) {
    init { require(value >= 0) { "Player id must not be negative" } }
}

sealed interface GameAction

data class BoardPosition(val x: Int, val y: Int) {
    init {
        require(x >= 0 && y >= 0) { "Board coordinates must not be negative" }
    }
}

data class BoardMove(
    val from: BoardPosition,
    val to: BoardPosition,
) : GameAction

enum class GameResult { ONGOING, FIRST_PLAYER_WIN, SECOND_PLAYER_WIN, DRAW }

enum class EngineError {
    ILLEGAL_ACTION, INVALID_STATE, CORRUPTED_DATA, UNSUPPORTED
}

sealed interface ActionResult {
    data object Accepted : ActionResult
    data class Rejected(val error: EngineError) : ActionResult
}

sealed interface RestoreResult {
    data object Restored : RestoreResult
    data class Rejected(val error: EngineError) : RestoreResult
}
~~~

- [ ] **Step 4: 定义规则接口**

~~~kotlin
interface RuleEngine<A : GameAction> : AutoCloseable {
    val gameType: GameType
    val currentPlayer: PlayerId
    fun reset()
    fun apply(action: A): ActionResult
    fun undo(): Boolean
    fun legalActions(): List<A>
    fun gameResult(): GameResult
    fun serialize(): ByteArray
    fun restore(data: ByteArray): RestoreResult
}
~~~

公共类型写 KDoc，解释稳定编码和所有权，不给自明 getter 添加注释。

- [ ] **Step 5: 运行模块测试并确认 GREEN**

Run: .\gradlew.bat :engine-api:testDebugUnitTest

Expected: BUILD SUCCESSFUL，所有 engine-api 测试通过。

- [ ] **Step 6: Commit**

~~~powershell
git add engine-api
git commit -m "feat: define engine contracts"
~~~

### Task 4: C++17 核心与 NDK 目标测试

**Files:**
- Create: engine-native/src/main/cpp/include/mocs/engine/engine_types.hpp
- Create: engine-native/src/main/cpp/include/mocs/engine/rule_engine.hpp
- Create: engine-native/src/main/cpp/include/mocs/engine/health_check.hpp
- Create: engine-native/src/main/cpp/src/health_check.cpp
- Create: engine-native/src/main/cpp/tests/health_check_test.cpp
- Create: engine-native/src/main/cpp/tests/CMakeLists.txt
- Modify: engine-native/src/main/cpp/CMakeLists.txt

**Interfaces:**
- Consumes: C++17 和 Task 3 的稳定枚举编码。
- Produces: mocs::engine::RuleEngine 抽象接口与 std::string health_check()。

- [ ] **Step 1: 写健康检查失败测试**

~~~cpp
#include "mocs/engine/health_check.hpp"
#include <cassert>

int main() {
    assert(mocs::engine::health_check() ==
           "MasterofChessStrategy Engine/1");
}
~~~

- [ ] **Step 2: 使用 NDK 工具链构建并确认 RED**

Run:

~~~powershell
& "E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe" -S engine-native/src/main/cpp -B .build/native-android -G Ninja -DCMAKE_TOOLCHAIN_FILE=E:/Backend_Env/SDK/ndk/30.0.16138531/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DMOCS_BUILD_TESTS=ON -DCMAKE_MAKE_PROGRAM=E:/Backend_Env/SDK/cmake/4.1.2/bin/ninja.exe
& "E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe" --build .build/native-android
~~~

Expected: FAIL，因为 health_check 尚未实现或链接。

- [ ] **Step 3: 实现最小 C++ 契约**

engine_types.hpp 定义以下稳定线协议；具体棋种在内部把 EngineAction 转换为强类型动作：

~~~cpp
enum class GameType : std::uint8_t {
    chinese_chess = 0, go = 1, standard_mahjong = 2,
    lanzhou_mahjong = 3, lanzhou_square_chess = 4, tiger_and_goat = 5
};
enum class Difficulty : std::uint8_t { easy = 0, medium = 1, hard = 2, master = 3 };
enum class GameResult : std::uint8_t {
    ongoing = 0, first_player_win = 1, second_player_win = 2, draw = 3
};
enum class EngineError : std::uint8_t {
    none = 0, illegal_action = 1, invalid_state = 2,
    corrupted_data = 3, unsupported = 4
};
struct EngineAction {
    std::uint16_t kind{};
    std::array<std::int32_t, 4> arguments{};
};
struct ActionResult { bool accepted{}; EngineError error{EngineError::none}; };
struct RestoreResult { bool restored{}; EngineError error{EngineError::none}; };
~~~

rule_engine.hpp 使用以下签名，且不得包含 JNI 类型：

~~~cpp
class RuleEngine {
public:
    virtual ~RuleEngine() = default;
    virtual GameType game_type() const noexcept = 0;
    virtual std::uint8_t current_player() const noexcept = 0;
    virtual void reset() = 0;
    virtual ActionResult apply(const EngineAction& action) = 0;
    virtual bool undo() = 0;
    virtual std::vector<EngineAction> legal_actions() const = 0;
    virtual GameResult game_result() const noexcept = 0;
    virtual std::vector<std::uint8_t> serialize() const = 0;
    virtual RestoreResult restore(const std::vector<std::uint8_t>& data) = 0;
};
~~~

- [ ] **Step 4: 实现健康检查并启用 Android 测试目标**

health_check.hpp 声明 std::string health_check()，实现返回固定协议字符串。顶层 CMake 创建 mocs_engine_core 静态库，并在 MOCS_BUILD_TESTS=ON 时 enable_testing/add_subdirectory(tests)。

- [ ] **Step 5: 使用 NDK 工具链构建并确认 GREEN**

Run:

~~~powershell
& "E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe" --build .build/native-android
~~~

Expected: 原生库和 Android 测试可执行目标均编译、链接成功。没有设备或 AVD 时不声明测试已运行。

- [ ] **Step 6: Commit and milestone push**

~~~powershell
git add engine-native
git commit -m "feat: add native engine foundation"
git remote get-url origin
git push
~~~

### Task 5: JNI Facade 与故障降级

**Files:**
- Create: engine-native/src/test/kotlin/com/masterofchessstrategy/engine/NativeEngineStatusProviderTest.kt
- Create: engine-native/src/main/kotlin/com/masterofchessstrategy/engine/NativeEngineStatus.kt
- Create: engine-native/src/main/kotlin/com/masterofchessstrategy/engine/internal/NativeBindings.kt
- Create: engine-native/src/main/cpp/src/jni_bridge.cpp
- Modify: engine-native/src/main/cpp/CMakeLists.txt
- Create: engine-native/src/androidTest/kotlin/com/masterofchessstrategy/engine/NativeBridgeInstrumentedTest.kt

**Interfaces:**
- Consumes: mocs::engine::health_check()。
- Produces: NativeEngineStatusProvider.check(): NativeEngineStatus。

- [ ] **Step 1: 写状态映射失败测试**

测试 checkNativeEngineStatus lambda 返回协议字符串时得到 Available(version)，抛出 UnsatisfiedLinkError 时得到 Unavailable(message)，并确认异常不会传播到调用方。

- [ ] **Step 2: 运行测试并确认 RED**

Run: .\gradlew.bat :engine-native:testDebugUnitTest --tests "*.NativeEngineStatusProviderTest"

Expected: FAIL，因为 Provider 与状态类型尚不存在。

- [ ] **Step 3: 实现 Kotlin Facade**

使用以下最小实现；固定错误文案防止堆栈或本机路径进入 UI：

~~~kotlin
sealed interface NativeEngineStatus {
    data class Available(val protocol: String) : NativeEngineStatus
    data class Unavailable(val message: String) : NativeEngineStatus
}

object NativeEngineStatusProvider {
    fun check(): NativeEngineStatus =
        checkNativeEngineStatus { NativeBindings.healthCheck() }
}

internal inline fun checkNativeEngineStatus(
    healthCheck: () -> String,
): NativeEngineStatus = try {
        NativeEngineStatus.Available(healthCheck())
    } catch (_: LinkageError) {
        NativeEngineStatus.Unavailable("Native library could not be loaded")
    } catch (_: RuntimeException) {
        NativeEngineStatus.Unavailable("Native engine health check failed")
    }

internal object NativeBindings {
    init { System.loadLibrary("mocs_engine_native") }
    external fun healthCheck(): String
}
~~~

- [ ] **Step 4: 实现 JNI 入口**

jni_bridge.cpp 使用以下入口；CMake 将该文件与 mocs_engine_core 链接为共享库：

~~~cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_healthCheck(
        JNIEnv* env, jobject) noexcept {
    try {
        const auto value = mocs::engine::health_check();
        return env->NewStringUTF(value.c_str());
    } catch (...) {
        const auto error = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(error, "Native engine health check failed");
        return nullptr;
    }
}
~~~

- [ ] **Step 5: 添加设备集成测试并验证可编译**

测试调用真实 NativeBindings，断言协议字符串。当前无设备/AVD，因此先运行 :engine-native:assembleDebugAndroidTest；获得设备后运行 :engine-native:connectedDebugAndroidTest，不把未执行的设备测试报告为已通过。

- [ ] **Step 6: 运行 Kotlin 测试与 NDK 构建**

Run:

~~~powershell
.\gradlew.bat :engine-native:testDebugUnitTest :engine-native:assembleDebug :engine-native:assembleDebugAndroidTest
~~~

Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: Commit**

~~~powershell
git add engine-native
git commit -m "feat: bridge android to native engine"
~~~

### Task 6: 横屏 Compose 应用壳

**Files:**
- Create: app/src/test/kotlin/com/masterofchessstrategy/HomeUiStateTest.kt
- Create: app/src/main/kotlin/com/masterofchessstrategy/HomeUiState.kt
- Create: app/src/main/kotlin/com/masterofchessstrategy/MainActivity.kt
- Create: app/src/main/kotlin/com/masterofchessstrategy/ui/MasterOfChessStrategyApp.kt
- Create: app/src/main/kotlin/com/masterofchessstrategy/ui/theme/Color.kt
- Create: app/src/main/kotlin/com/masterofchessstrategy/ui/theme/Theme.kt
- Create: app/src/main/kotlin/com/masterofchessstrategy/ui/theme/Type.kt
- Modify: app/src/main/res/values/strings.xml
- Modify: app/src/main/res/values/themes.xml
- Modify: app/src/main/res/values-night/themes.xml

**Interfaces:**
- Consumes: NativeEngineStatusProvider.check()。
- Produces: 可启动的单 Activity 横屏应用壳。

- [ ] **Step 1: 写 UI 状态失败测试**

测试 Available 映射为“原生引擎已就绪”和协议版本，Unavailable 映射为“原生引擎不可用”和简短诊断信息；UI 文案不包含堆栈或绝对路径。

- [ ] **Step 2: 运行测试并确认 RED**

Run: .\gradlew.bat :app:testDebugUnitTest --tests "*.HomeUiStateTest"

Expected: FAIL，因为 HomeUiState 尚不存在。

- [ ] **Step 3: 实现状态映射与 Activity**

HomeUiState.from(status) 是纯函数：

~~~kotlin
data class HomeUiState(
    val engineTitle: String,
    val engineDetail: String,
) {
    companion object {
        fun from(status: NativeEngineStatus): HomeUiState = when (status) {
            is NativeEngineStatus.Available ->
                HomeUiState("原生引擎已就绪", status.protocol)
            is NativeEngineStatus.Unavailable ->
                HomeUiState("原生引擎不可用", status.message)
        }
    }
}
~~~

MainActivity 只检查一次原生状态，不创建后台任务：

~~~kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = HomeUiState.from(NativeEngineStatusProvider.check())
        setContent { MasterOfChessStrategyApp(state) }
    }
}
~~~

- [ ] **Step 4: 实现首屏 Compose**

使用以下结构；主题文件提供 MocsTheme 包装 MaterialTheme，颜色和字型只定义首屏实际使用项：

~~~kotlin
@Composable
fun MasterOfChessStrategyApp(state: HomeUiState) {
    MocsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(0.4f)) {
                    Text("棋术大师", style = MaterialTheme.typography.displaySmall)
                    Text("纯单机离线")
                }
                Card(Modifier.weight(0.6f)) {
                    Column(Modifier.padding(24.dp)) {
                        Text("工程底座", style = MaterialTheme.typography.headlineMedium)
                        Text(state.engineTitle)
                        Text(state.engineDetail)
                    }
                }
            }
        }
    }
}
~~~

布局使用权重自适应，不写固定设备像素。注释只解释为何保持双栏和为何不自动重试原生库加载。

- [ ] **Step 5: 运行应用模块测试并确认 GREEN**

Run: .\gradlew.bat :app:testDebugUnitTest

Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit and milestone push**

~~~powershell
git add app
git commit -m "feat: add offline compose app shell"
git remote get-url origin
git push
~~~

### Task 7: 文档校准与首个切片门禁

**Files:**
- Modify: docs/architecture/overview.md
- Modify: docs/interfaces/README.md
- Modify: docs/development/environment.md
- Modify: docs/testing/strategy.md
- Modify: docs/delivery/README.md
- Create: docs/delivery/CHANGELOG.md

**Interfaces:**
- Consumes: Tasks 1–6 的实际实现和验证输出。
- Produces: 可复现构建说明、准确接口说明和首个节点记录。

- [ ] **Step 1: 用实际实现校准文档**

记录模块依赖、JNI 协议、错误降级、准确构建命令、当前设备限制和已安装工具。CHANGELOG 只记录 Foundation 节点，不填写未完成能力。

- [ ] **Step 2: 运行完整验证门禁**

Run:

~~~powershell
& "E:\Backend_Env\SDK\cmake\4.1.2\bin\cmake.exe" --build .build/native-android
.\gradlew.bat :engine-api:testDebugUnitTest :engine-native:testDebugUnitTest :app:testDebugUnitTest lintDebug assembleDebug
git diff --check
rg -n "<uses-permission[^>]*INTERNET" app/src/main/AndroidManifest.xml
~~~

Expected: NDK 原生库和测试目标编译、链接成功；Gradle BUILD SUCCESSFUL；git diff --check 无输出；最后一次 rg 无命中且退出码为 1。没有设备或 AVD 时不声明原生测试已运行。

- [ ] **Step 3: 检查 APK 权限**

Run:

~~~powershell
& "E:\Backend_Env\SDK\cmdline-tools\latest\bin\apkanalyzer.bat" manifest permissions app/build/outputs/apk/debug/app-debug.apk
~~~

Expected: 权限清单不包含 android.permission.INTERNET。若 apkanalyzer 路径不存在，使用 Android SDK 中已安装版本的同名工具并把实际路径写入环境文档。

- [ ] **Step 4: Commit**

~~~powershell
git add docs
git commit -m "docs: record foundation architecture and verification"
~~~

- [ ] **Step 5: 最终状态与远程同步**

Run:

~~~powershell
git status --short
git log --oneline --decorate -8
git remote -v
git push
~~~

Expected: 工作区无本任务遗留修改；有 origin 时推送成功。没有 origin 时明确报告缺少远程 URL，不猜测远程地址。

## Official References

- Android Gradle Plugin 9.4 compatibility: https://developer.android.com/build/releases/agp-9-4-0-release-notes
- Built-in Kotlin migration: https://developer.android.com/build/migrate-to-built-in-kotlin
- Compose compiler and BOM setup: https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
