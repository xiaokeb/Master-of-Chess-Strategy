# 虎羊完整回合增量与冻结

日期：2026-10-12。原生源码切片冻结，独立AVD行为已通过；整棋种节点未冻结。

后续异常安全修订已由Root授权解冻，新9份清单 `native-freeze-20261012-oom.tsv` 取代本页旧8份冻结。下面原生12组及旧二进制SHA是历史已跑证据，不是当前源码运行结果；当前节点见 [异常安全交接](2026-10-12-native-exception-safety.md)。

## 实际基线与修改

接手时游戏源码SHA为 `030e070b3ec0e5d58581a32416692b99d7bc8f4803e082afe1e12a4ab6ef5d28`，测试为 `5c1d036f77a1d979da3a98bd005fb530a0aee358740a26b7cf79904d8411e775`。较旧进度中的连跳夹具已经被修为12→14→24→22，并另有无后续跳吃自动结束测试，本轮保留现状，不再据10月3日快照称它仍在失败。

新增有来源的羊方强制停着：待摆数0、当前羊回合且无合法一步才提供 `pass_goat=6`，换虎手；不另加胜因。完整依据及取舍见 [正式规则](rules-implementation.md)。MOCT写出v2，接收v1旧0–5动作，v1装新动作及未知版本拒绝，旧明确终局保留。棋盘版本/快照81字段/几何格式不变。

新增测试覆盖合法虎步5→10使双山12羊无步、唯一停着、非法停着不增历史、停着后虎走步恢复羊的活动空间、停着前后保存和多次悔棋、最后待摆羊与有步禁止停着、新回合重新跳回旧落点、v1升级重放与正确校验和的未知动作/版本拒绝。已有连跳中间保存、回退、转向和自动结束组继续运行。12组场景均保留断言，未关闭assert。

## 私有构建证据

使用 `E:/Backend_Env/SDK/ndk/30.0.16138531/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe`（r30-beta3），私有输出 `.build/tiger-goat-repair-20261012/`；不调用MinGW、Gradle或ADB。执行：

```powershell
& .build/tiger-goat-repair-20261012/build.ps1
```

脚本对game.cpp、game_test.cpp、topology_test.cpp和旧step_test.cpp执行两ABI `--target=aarch64-linux-android26` / `x86_64-linux-android26`、同NDK sysroot、`-std=c++20 -Wall -Wextra -Wpedantic -Werror -fsyntax-only`；均退出0。随后以x86_64 `-static-libstdc++ -O1`链接game+test，退出0。初次脚本因PowerShell参数数组括号缺失漏掉include路径，修正脚本后完成；不是规则断言运行结果。

二进制 `.build/tiger-goat-repair-20261012/tiger-goat-game-test`，SHA `b5f086f639976a38b2e2acbde1239d0aeaded79474dbc11a5005a2c0f1af7f0d`。八份源码/头/测试精确路径和SHA见 [冻结清单](native-freeze-20261012.tsv)，专属私有复核8/8无变化。

统筹初轮AVD运行旧二进制 `d8c74f97…` 退出134，收据 `.build/tiger-goat-repair-20261012/root-avd.log`。测试把满山29羊点作为10→29跳吃落点，正确被规则拒绝；已仅将该后续动作改为合法普通步10→11，不放宽占位规则。修改后再次双ABI语法/链接退出0。重传后二进制执行位被ADB重置，首次执行退出126、未运行断言；统筹补chmod后在Pixel_7_Pro `emulator-5554` x86_64实际运行输出 `tiger-goat game: 12 scenario groups passed`、退出0，设备二进制SHA与上述新值一致。通过收据 `.build/tiger-goat-repair-20261012/root-avd-fixed-executable.log` 已只读核对，旧失败记录保留。

## 公共接口交接

动作0移羊、1摆羊、2走步、3跳吃、4结束链、5认输保持；尾追加6强制羊停着，from/to均-1。共享CMake加入正式源码及行为目标，Registry/JNI按虎羊类型隔离；Kotlin解码/专属屏幕需识别6、不能把无步当正在等待不可执行点击。存储写v2且允许v1重放，不能沿用仅v1验证器拒绝新包。上述公共层本轮未改，交统筹串行协调；既有虎羊App草稿也未据此称兼容。

未验收：共享CMake构建/JNI/Kotlin/导航/Room、实际可玩与视觉、AI梯度、教程/残局/成长/自动演局、最终内容量及实体设备。NDK ARM64只有严格语法证据，独立实际行为在x86_64 AVD；不将它宣称为App或实体设备可玩。无Git暂存、提交或推送。
