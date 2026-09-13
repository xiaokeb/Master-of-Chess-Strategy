# 第三方组件

本目录保存正式构建所需的第三方源码、原始许可证与供应链记录。

已审核组件：

- pikafish/：中国象棋大师 AI，GPL-3.0-or-later，固定到
  Pikafish-2026-09-06。
- pikafish-network/：NNUE 权重来源、哈希和独立使用条款记录；正式
  权重位于 app/src/main/assets/pikafish/pikafish.nnue。

每个组件必须记录精确版本、来源、许可证、修改内容、目标 ABI 和体积
影响。正式构建不得运行网络下载脚本。
