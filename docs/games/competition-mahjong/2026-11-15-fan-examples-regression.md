# 国标麻将逐番回归

归档已冻结的项目自编逐番测试，并注册到 `MOCS_BUILD_TESTS`。各番牌例按世界麻将组织《麻将竞赛规则》附件一的番种定义设计。程序验证公开番种 ID、名称、分值和所列排斥，还检查番种明细顺序及总分一致性。[世界麻将组织规则版本镜像](https://mahjongclublausanne.ch/wp-content/uploads/2024/03/Greenbook2021_Chinese-EN.pdf) · [番种算法来源](../../../engine-native/src/main/cpp/games/competition-mahjong/fan/vendor/README.md)

NDK 30 的 x86_64 与 arm64-v8a 均完成编译及链接。Pixel 7 Pro 模拟器核对了 x86_64 可执行散列后实跑，通过 81 个逐番正例、21 个反例及 18 个七分／八分和花牌门槛场景，进程退出码为 0。

以现有 Android 工具链启用 `MOCS_BUILD_TESTS`，构建目标 `mocs_competition_mahjong_fan_examples_test`。匹配 ABI 的设备可直接运行该目标的可执行文件。完整排斥矩阵和多种组合中的最高分判定仍需扩展。[验收清单](../../releases/manifests/v0.1.0-competition-mahjong-fan-examples.1.json)
