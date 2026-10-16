# Locked Go CPU dependencies

Imported original subset: KataGo v1.18.1 commit
`92ee95c0a4b25fec214da00951ab69e97e207729`, Eigen 3.4.0 commit
`3147391d946bb4b6c68edd901f2add6ac1f31f8c`. No upstream modifications.

Root import/verify: `tools/android/prepare-go-native-dependencies.ps1`.
`selection.json` records the reviewed two-ABI dependency closure; `probe-lock.json`
pins archives, model and license snapshots. `manifest.json` is the **full staged
distribution** manifest: only its `source/`, `eigen/` entries and these three
provenance files are imported here, not its `assets/` entries.

The CPU build requires `USE_EIGEN_BACKEND`, `EIGEN_MPL2_ONLY` and
`EIGEN_DONT_PARALLELIZE`. Preserve KataGo LICENSE/CONTRIBUTORS, filesystem
LICENSE, the notices embedded in JSON/SHA2 and all Eigen COPYING files. Their
presence does not enable GPL/LGPL Eigen components. Runtime wrapper is project
GPL-3.0-or-later code, not an upstream modification.

No model is imported here or implicitly bundled. App notices, accessible covered
source, complete corresponding project source and release verification remain
separate delivery gates. This subset is not the entire application's source offer.
