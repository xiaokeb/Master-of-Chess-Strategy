#pragma once

#include "mocs/engine/engine_types.hpp"

#include <cstdint>
#include <vector>

namespace mocs::engine {

// Platform-neutral rule contract. JNI types belong only in the bridge layer.
class RuleEngine {
public:
    virtual ~RuleEngine() = default;

    [[nodiscard]] virtual GameType game_type() const noexcept = 0;
    [[nodiscard]] virtual std::uint8_t current_player() const noexcept = 0;
    virtual void reset() = 0;
    [[nodiscard]] virtual ActionResult apply(const EngineAction& action) = 0;
    [[nodiscard]] virtual bool undo() = 0;
    [[nodiscard]] virtual std::vector<EngineAction> legal_actions() const = 0;
    [[nodiscard]] virtual GameResult game_result() const noexcept = 0;
    [[nodiscard]] virtual std::vector<std::uint8_t> serialize() const = 0;
    [[nodiscard]] virtual RestoreResult restore(
        const std::vector<std::uint8_t>& data
    ) = 0;
};

}  // namespace mocs::engine
