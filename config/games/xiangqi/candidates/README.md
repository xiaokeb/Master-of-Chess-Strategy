# 象棋残局待审流

这些文件由本项目 NDK 30 LLVM 编译的 `mocs_generate_xiangqi_mates` 在 Pixel_7_Pro x86_64 模拟器上按所列种子生成；没有导入第三方棋谱或媒体。原始流保留为来源证据，不能单凭生成结果计入正式关卡。文件 SHA-256 便于复现与防止无意替换。

| 文件 | 题型/步数 | 种子 | SHA-256 |
| --- | --- | ---: | --- |
| `xiangqi-seed-20261001.txt` | classic/一步 3 题 | 20261001 | `efaba8dacbcdad4a487d4b3a6183429979b810608070dfb5a22b785ea88dc60c` |
| `xiangqi-two-move-seed-20261001.txt` | classic/两步 2 题 | 20261001 | `496ae73e21290c0ee0893c8a44d935846d75e26d7849d2118a2c6623a7225d50` |
| `xiangqi-rook-cannon-seed-20261002.txt` | 车炮/一步 | 20261002 | `d8a9a8d91c36359225d57c7bfdb9cebe12b29fd7b1b40d01183b5a3d2d47a2e6` |
| `xiangqi-horse-cannon-seed-20261002.txt` | 马炮/一步 | 20261002 | `c98b77894803387a6e0bbca8aeb272006af60e9f5f19969469ca52c2916d2934` |
| `xiangqi-double-rook-seed-20261002.txt` | 双车/一步 | 20261002 | `36e245a7e58108dfa6a53fd741bee4b5da27e46b798f52004ab197f7bb8bd282` |
| `xiangqi-rook-cannon-two-move-seed-20261003.txt` | 车炮/两步 | 20261003 | `b9479813fc1043a56087976e1d077204001d168aa6ca9a135073d52959a05a47` |
| `xiangqi-horse-cannon-two-move-seed-20261003.txt` | 马炮/两步 | 20261003 | `bb0df855e9e3f571f5cc7e3e8ba8ea13dabf1774dce413e3b4322caf2bccb93b` |
| `xiangqi-double-rook-two-move-seed-20261003.txt` | 双车/两步 | 20261003 | `b988183ae1a92c389a1c828bc7ae25480bc2fd16d3445de271ed40336c07d57d` |
| `xiangqi-rook-horse-seed-20261004.txt` | 车马/一步 2 题 | 20261004 | `65f8e437ae10dbc362c2f7e9f7353e6cc34f0563d282c11f0e7c2db5f71623bf` |
| `xiangqi-horse-cannon-two-move-seed-20261005.txt` | 马炮/两步 2 题 | 20261005 | `2d9e1a35affce1a42b18c29d7763ec44e1a9f519e3e0991108832d9b7321aa1c` |
| `xiangqi-rook-cannon-seed-20261007.txt` | 车炮/一步 100 个待审局面 | 20261007 | `72bf3897d650f7f88f03f6b207f33ac9bd59e6f5d5a7127d3d2395416034f882` |
| `xiangqi-horse-cannon-two-move-seed-20261009.txt` | 马炮/两步 100 个待审局面 | 20261009 | `6410a99e4693f85bc45c6f2555917d50be8afa6f628e4fe1b1b7ee405b5340a9` |

审核命令：在 Conda 环境 `MasterofChessStrategy` 运行 `python tools/python/stage_xiangqi_candidates.py <文件> [更多文件...]`；多文件模式额外检查跨批左右镜像重复。2026-10-01 **入包前**曾将全部 8 个 `.txt` 文件一起审核，返回 8 批、11 个不同候选、`staged-not-published`，来源哈希与本表一致。六个混合子力候选与五个 `classic` 候选均已进入本地 27 关 Debug 包，后五关的 Android/JNI 与模拟器目录/棋盘专项也已验收；详见 `../../../../docs/games/xiangqi/2026-10-01-mixed-piece-endgames.md`、`../../../../docs/games/xiangqi/2026-10-01-classic-five-endgames.md`。已入工作树的八份流都会与当前包重复，不能再对当前包运行同一暂存命令并期待通过。当前包与全部 11 题的原始流逐字段对应由 `tools/python/test_audit_chinese_chess_endgames.py` 回归检查。统一 Git 提交与远程发布仍待六棋种闸口。

2026-10-02 新增的后两批在 Pixel_7_Pro 模拟器上分别以参数 `1 2 50000 20261004 rook-horse` 和 `2 2 100000 20261005 horse-cannon` 生成。入包前与 27 关交叉审核得 4 个不同局面；现已进入工作树及本地 31 关 Debug APK，但 APK 内规则/棋盘仪器测试尚未完成，暂不计完整验收量。`tools/python/test_stage_xiangqi_candidates.py` 固定来源哈希，验证相对于旧 27 关可暂存、相对于现行 31 关会被识别为已发布重复题。来源与验收范围见 `../../../../docs/games/xiangqi/2026-10-02-rook-horse-endgames.md`。

2026-10-03 的 100 局面流由修正后的原生生成器以 `1 100 1000000 20261007 rook-cannon` 在 Pixel_7_Pro 模拟器生成，设备/本地字节哈希一致，独立主机暂存核验 100/100。逐子移除证明显示仅序号 41、43、97 的原解法确需红炮；其中只有序号 43 的红兵也确需参与。其余 97 题不能仅凭“车炮”标签当作战术多样性内容。后续工作树及新装 Debug APK 将序号 43、41 编为未完整验收的简单第 17、18 关；目前只完成目录视觉，不能据此计棋盘/原生设备验收。先前未修正生成器的 10 题原始流已移至本地 `.build` 留证，不作为新候选提交；误把有其他困毙胜着的局面称为“唯一一步杀”的原因与修复见 `../../../../docs/games/xiangqi/2026-10-03-material-quality.md`。

新两步马炮流以 `2 100 100000 20261009 horse-cannon` 在同一 AVD 生成，设备/本机/本目录三处 SHA-256 相同；相对于当前 31 关，100/100 完成独立全应手证明及镜像去重。逐子移除后 48 题原解法依赖炮，序号 24、73 的具体取舍进入 `../reviews/2026-10-03-horse-cannon-two-move.json`，仍未发布。该流已作为原始来源保留，不将其余 98 题自动计入内容量。
