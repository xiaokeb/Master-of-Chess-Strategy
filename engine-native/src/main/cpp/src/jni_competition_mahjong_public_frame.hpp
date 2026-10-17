#pragma once

#include "../games/competition-mahjong/competition_mahjong_public_view.hpp"
#include <jni.h>

namespace mocs::engine::jni {
// Both human and automatic sessions use the same privacy-preserving v2 encoder.
[[nodiscard]] jintArray competition_mahjong_public_frame(
    JNIEnv* env, const CompetitionMahjongPublicView& view);
} // namespace mocs::engine::jni
