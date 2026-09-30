# 中国象棋残局候选批量生产边界

日期：2026-09-30。此节点只增强内容生产与暂存审核，**未新增发布关卡**；当前仍为 16 关、镜像等价后 15 个独立局面。

## 变化

- NDK 离线候选生成器新增可选 32 位随机种子，输出保留 `seed`、尝试序号和证明标记；同批次按左右镜像去重。默认种子仍为 20260925，旧单步/两步参数保持兼容。
- Conda 暂存工具 `tools/python/stage_xiangqi_candidates.py` 校验候选流序号、种子、尝试顺序、FEN/摆子一致性、v5 内容结构、候选内镜像重复及与已发布包的重复；输出来源哈希与 `staged-not-published` JSON，不写入 APK。
- 暂存工具只核实生成记录的形状，不重新证明胜法，也不证明棋谱来源、人工趣味性或授权；正式入包仍需原生规则求解、人工筛选、来源清单和发布门禁。缺少设备端测试时，候选不能因此标记为已验收内容。

## 验证

- 指定 NDK 30 LLVM 的 `mocs_generate_xiangqi_mates` 与 `mocs_xiangqi_forced_win_test` 目标编译链接通过；按当前要求未运行 Android 设备端原生可执行文件。
- 项目 Conda 全部 Python 离线单测 44/44，通过合成候选与真实发布包的重复/镜像回归；额外防止合成候选序号误借既有演示关的镜像豁免；`git diff --check` 通过。

复现候选暂存：`python tools/python/stage_xiangqi_candidates.py <native-stdout.txt>`。此命令仅读取候选流和已发布包，不自动创建发布资源。
