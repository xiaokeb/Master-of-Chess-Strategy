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
process. No search or evaluation logic is changed.

Application-specific FEN conversion, UCI move conversion, time limits, and
legal-move validation live under engine-native/src/main/cpp.
