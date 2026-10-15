# 虎羊动作记录异常安全

日期2026-10-12。Root授权解冻原生头/实现及专属测试处理此必要缺陷；App16保持冻结无变化。[新9份原生冻结清单](native-freeze-20261012-oom.tsv)取代旧8份清单，旧清单和12组运行收据仅保留历史，不能用于当前二进制验收。

## 原因与修复

旧apply各分支先append撤销帧再append动作。若第二次容器分配抛bad_alloc，JNI虽转为Java OOM，但撤销帧已增加、动作列表未增加；首次动作后调用undo可能对空动作列表pop，其他回合会撤销不一致历史。

全部7种合法动作（含虎/羊普通步）现统一调用私有 `record_action`。第一次push失败直接保留原状态；第二次push失败先pop回撤销帧再重抛。实际棋盘/阶段/链/终局的修改仍在两个记录均完成之后。规则、动作0–6、MOCT v1/v2、线协议/棋盘拓扑均未改。

新增 `tiger_goat_exception_test.cpp` 使用仅测试可执行内的global operator new分配探针；普通生产库没有探针。对7种动作及单独羊步分别让第一次或第二次记录分配失败，共16次。断言异常确实发生于指定分配、保存字节/动作数不变、撤销行为与从原档恢复的对照一致，然后验证重试动作成功、再撤销回原字节。连跳结束及摆羊使用已有1步历史，覆盖非空历史扩容失败；其他场景也覆盖空历史。

## 私有证据与交接

执行 `.build/tiger-goat-repair-20261012/build.ps1`，仍使用NDK30 LLVM。game/game_test/exception_test/topology/旧step均在ARM64和x86_64以C++20、Wall/Wextra/Wpedantic/Werror严格语法退出0；随后两个x86_64测试以O1、static-libstdc++独立链接退出0。本专属任务未执行Gradle/AVD或公共CMake。

| 私有二进制 | SHA-256 | 待统筹运行的预期输出 |
| --- | --- | --- |
| `.build/tiger-goat-repair-20261012/tiger-goat-game-test` | `d70285b6c31ed3789dc556bcf834d647383f4c7a7482885439acb372c770b848` | `tiger-goat game: 12 scenario groups passed` |
| `.build/tiger-goat-repair-20261012/tiger-goat-exception-test` | `c1bc243cb0e4c811975101dc0205c2acee5abbf614b8f840a177d729e3390919` | `tiger-goat exception safety: 16 injected allocation failures passed` |

当前证据仅编译/链接，两项实际运行仍待Root串行安排并核退出0。JNI/共享CMake/App行为同样不能由本检查替代；新增异常测试目标需Root单独登记进共享测试CMake，现有拓扑static_assert应使用OBJECT目标而非无main的可执行。App16、公共文件、原有12组规则断言均未改，无Git操作。
