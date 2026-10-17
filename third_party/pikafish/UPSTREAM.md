# Pikafish integration record

- Upstream repository: https://github.com/official-pikafish/Pikafish
- Upstream release: Pikafish-2026-09-06
- Exact commit: 4c17cee11f888ae1d48a9494f2e2239f019f0a1f
- Retrieved: 2026-09-13
- License: GPL-3.0-or-later; see Copying.txt
- Original authors: see AUTHORS

## Build scope

The Android library compiles the upstream engine, search, rule, NNUE, thread,
and bundled Zstandard decompression sources. The command-line main.cpp and
universal executable dispatch shims are excluded because the application uses
the in-process Stockfish::Engine API.

The build uses Android NDK 30 LLVM with C++17. ARM64 enables the upstream
NEON and popcount paths. The application targets arm64-v8a and x86_64 because
this upstream release requires a 128-bit integer-backed board and does not
compile for Android 32-bit targets.

## Local modifications

src/nnue/network.cpp throws a recoverable C++ exception when the required
network is unavailable instead of terminating the Android application
process. The independent search-budget changes below also apply; evaluation
weights and evaluation logic are unchanged.

Application-specific FEN conversion, UCI move conversion, time limits, and
legal-move validation live under engine-native/src/main/cpp.

## Source archive correction (2026-10-15)

The original vendored ignore rule `**/*.s` also matched the required upstream
`src/external/decompress/huf_decompress_amd64.S` on Windows. Local mixed builds
could read it, but the published Git baseline omitted it and a clean x86_64
source snapshot could not configure. A narrow ignore exception now retains
this assembly source; neither the assembly nor engine search logic is changed.
The single-game release candidate must include it and pass a clean dual-ABI
build before publication. Prior tags are not rewritten; this correction is
delivered by a new verified commit/tag.

Assembly identity note: upstream Git blob `dc1f3d9211353446e5d76ee665ead9922ad63655`
has 19,676 LF bytes, SHA-256 `9c430fa95e7ff8bda568efd2bdadb16d17cc02e476bc8daa7cc0d87c28504279`.
The Windows checkout/cache has 20,442 CRLF bytes, SHA-256
`50b8a19c8220bc09f61570e516894102c3894d2f137b7d40df10e445a66f6615`;
only the 766 CR bytes differ. The final source candidate uses the original
LF blob so its verified bytes match the Git archive, not just the checkout.

## Bundled Zstandard license restoration (2026-10-15)

The external Zstandard/FSE/HUF/xxHash headers offer BSD or GPLv2; this
integration selects their BSD option and retains the original dual-license
headers. The previously missing referenced `src/external/LICENSE` and
`COPYING` are now retained. The complete selected BSD text is also in the
application's NOTICE page, together with the xxHash author attribution.

License-text evidence is fixed to the official facebook/zstd commit
`49cf51799ea051457f57243527aa2058f999f005`:

- LICENSE upstream/local SHA-256: `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8`.
- COPYING upstream SHA-256: `f9c375a1be4a41f7b70301dd83c91cb89e41567478859b77eef375a52d782505`.
- COPYING local SHA-256: `8177f97513213526df2cf6184d8ff986c675afb514d4e68a404010521b880643`; the sole difference is one final LF, not license wording.

This commit identifies license text only. The bundled `zstd.h` reports
1.6.0, but its original independent Zstandard source commit is not recorded;
do not claim that it is the license evidence commit. The actual vendored
code remains traceable to the exact Pikafish commit above. Restoring these
notices does not grant commercial permission for the separate NNUE network.

## Search budget safety (2026-10-17; verification in progress)

MasterofChessStrategy modifies `src/search.cpp` to poll the existing time/node
manager during quiescence recursion and propagate stop after restoring each
move/null move or same-ply search state. Interrupted placeholder values must
not drive ProbCut, singular extensions, LMR re-search/history, PV or TT bounds.
Each main-thread search resets its polling countdown. Nodes remain counted by
the original `do_move`, and only the main thread calls the time manager.

`src/search.h` and `src/engine.h` grant a project-owned native regression probe
friend access without changing worker layout or the public production API.
The probe invokes the actual qsearch with real NNUE and checks restored board,
state pointer, node count, PV and root TT. Original-source baseline failures
and patched-source results are recorded separately. See
`docs/games/xiangqi/2026-10-17-search-budget-safety.md` in the project root.

This is a new source identity, not a rerun or replacement of any frozen AI
evaluation. The previous medium-hard job exited abnormally without a final
summary; these source risks do not establish its runtime cause. Existing GPL,
copyright, source-delivery and separate NNUE restrictions remain unchanged.
