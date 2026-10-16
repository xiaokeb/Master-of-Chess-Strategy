# KataGo CPU与模型分发边界

这是本项目的工程分发检查记录，不是司法辖区法律意见，不构成作者背书或商用许可。

## 固定来源

- KataGo v1.18.1：提交 `92ee95c0a4b25fec214da00951ab69e97e207729`，[原始LICENSE](https://raw.githubusercontent.com/lightvector/KataGo/92ee95c0a4b25fec214da00951ab69e97e207729/LICENSE)。主代码MIT，内含外部文件分别适用其原始声明，不把整个目录一概改成MIT或项目自有。
- Eigen 3.4.0：提交 `3147391d946bb4b6c68edd901f2add6ac1f31f8c`。只编译EIGEN_MPL2_ONLY边界，不启用GPL/LGPL组件；保留全部原始COPYING文件及准确头文件源码。
- 模型：官方kata1的 `kata1-b15c192-s1672170752-d466197061`，48,038,914字节，SHA `09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967`。依据[官方网络许可](https://katagotraining.org/network_license/)保留版权和许可，不与g170的CC0或外部用户贡献网络混用。

来源URL、下载长度和散列见 `config/games/go/katago-probe-lock.json`；本次核查不重新下载或更换模型。

## 精确交付

`third_party/go-katago-upstream/manifest.json` SHA `081f5e48bdd5979cfe9b342470b1c4ccf11570646d9a0949ab5933239958202a`。原始source/eigen条目392项全部逐字节核验；这是实际两ABI的CPU依赖闭包，不包含桌面GTP、训练、HTTP或GPU功能。未修改上游源码；NDK endian适配放在项目CMake编译参数中。

生产资产由 `config/games/go/katago-app-assets-v1.tsv`列出13项。保留KataGo LICENSE/CONTRIBUTORS、模型HTML许可、filesystem许可、JSON/SHA2内嵌声明与源码、Eigen声明及准确依赖源码ZIP；项目NOTICE与随包通知一致。中性`.gz.bin`后缀防止Android资产构建器解压并改变模型字节，私有模型缓存恢复`.txt.gz`名称。

`dependency-source.zip`为1,627,442字节，SHA `2135faa55673f757df0a556a832cdfe2dd5f1698b6a3f95dfdf2d3557bce1211`；含392份原始源码及selection/lock共394条目。许可页能读取真实声明并发起用户选择目的地保存该ZIP。复制函数、按钮回调及实际资产散列已验，系统SAF写入尚需另验。

依赖ZIP不是整个Android应用的完整对应源码。应用整体GPL来源还包括项目适配、构建脚本、Pikafish及本地修改；分发APK须提供对应标签的完整项目源码和构建文件。源码节点只发布源码，不附混合工作区APK。

## 保持不变的限制

KataGo/模型的条款不覆盖Pikafish NNUE。本项目现有NNUE独立合法用途、未经许可不得商用等限制继续有效，见根NOTICE及Pikafish网络条款。公开源码、阶段tag或Release均不扩大授权；未获相应权利人许可不得宣称商用通过。
