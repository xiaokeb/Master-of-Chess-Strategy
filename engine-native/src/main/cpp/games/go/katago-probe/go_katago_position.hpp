#pragma once

#include "mocs/engine/go.hpp"
#include "game/board.h"
#include "game/boardhistory.h"

namespace mocs::engine::go_ai {

// Requires KataGo Board::initHash() at process initialization; no model or shared JNI dependency.
class KataGoPosition final {
public:
    explicit KataGoPosition(const GoEngine& source);

    Board board;
    BoardHistory history;
    Player player;

    // Both board contents and every root move must agree before NN/search can consume this state.
    void require_native_agreement(const GoEngine& source) const;

private:
    void resume_without_erasing_ko(Player first_player);
};

}  // namespace mocs::engine::go_ai
