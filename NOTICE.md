# Master of Chess Strategy notices

Project source code authored for Master of Chess Strategy is licensed under
GNU General Public License version 3 or any later version. The full license is
in LICENSE.

## Pikafish engine

This project links a modified source snapshot of Pikafish:

- Upstream: https://github.com/official-pikafish/Pikafish
- Release: Pikafish-2026-09-06
- Commit: 4c17cee11f888ae1d48a9494f2e2239f019f0a1f
- License: GNU General Public License version 3 or any later version

The complete integrated source, original notices, authors list, build files,
and local modification record are retained in third_party/pikafish.

## Pikafish NNUE network

The bundled pikafish.nnue is data distributed by the separate
official-pikafish/Networks project. It is not relicensed as GPL by this
project. Its upstream terms require legal use and prohibit commercial use
without permission. The exact terms are retained in
app/src/main/assets/pikafish/NETWORK-LICENSE.md.

Anyone planning commercial distribution must obtain the network
rightsholder's permission or replace the network with a compatible asset
whose license permits that use.

## Pikafish bundled Zstandard decompressor

The Zstandard, FSE/HUF and locally adapted xxHash sources bundled by the
fixed Pikafish commit retain their original BSD/GPLv2 dual-license headers.
This integration selects the offered BSD license option, without modifying
those headers or relicensing the engine or NNUE network. The xxHash header
also credits Yann Collet - Meta Platforms, Inc.

The upstream-referenced LICENSE and COPYING texts are restored under
third_party/pikafish/src/external/. License evidence was retrieved from
facebook/zstd commit 49cf51799ea051457f57243527aa2058f999f005; that commit
identifies the license texts only, not the unknown original Zstandard
source revision bundled by Pikafish (its header reports version 1.6.0).
Upstream: https://github.com/facebook/zstd
The full selected BSD license follows.

BSD License

For Zstandard software

Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

 * Neither the name Facebook, nor Meta, nor the names of its contributors may
   be used to endorse or promote products derived from this software without
   specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## KataGo engine and Go neural network

The Go AI links a reviewed CPU subset of KataGo v1.18.1, commit
92ee95c0a4b25fec214da00951ab69e97e207729. KataGo's main license is MIT;
its LICENSE also identifies external files with separate notices. The
bundled notices retain KataGo contributors, filesystem-1.5.8, nlohmann JSON,
SHA-2, and Eigen terms. Eigen 3.4.0 is compiled with EIGEN_MPL2_ONLY.

The bundled network is the official kata1 model
kata1-b15c192-s1672170752-d466197061 (48,038,914 bytes, SHA-256
09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967).
It is distributed under the KataGo Neural Network License retained at
games/go/legal/KataGo-network-license.html. It is not project-authored, GPL,
CC0, a g170 network, or an externally contributed network.

The application includes games/go/legal/dependency-source.zip containing the
392 locked KataGo/Eigen source files used by this CPU build. The in-app legal
page can save that archive. It is a dependency-source convenience copy, not
the complete corresponding source for the Android application; the complete
source and build files for an APK must remain available at the repository
address shown in the application. KataGo and its contributors do not endorse
this project.
