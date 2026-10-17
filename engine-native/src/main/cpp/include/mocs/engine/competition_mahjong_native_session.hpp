#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace mocs::engine {

class CompetitionMahjongRound;
struct CompetitionMahjongPublicView;
struct CompetitionMahjongRoundAction;
struct CompetitionMahjongRoundActionResult;
enum class CompetitionMahjongRoundActionKind : std::uint8_t;

/** Shared boundary: fixed local seat, atomic public queries and private records. */
class CompetitionMahjongNativeSession final {
public:
    CompetitionMahjongNativeSession(std::uint64_t seed, std::uint8_t dealer,
                                   std::uint8_t round_wind, std::uint8_t viewer);
    CompetitionMahjongNativeSession(std::vector<std::uint8_t> wall, std::uint8_t dealer,
                                   std::uint8_t round_wind, std::uint8_t viewer);
    ~CompetitionMahjongNativeSession();
    [[nodiscard]] std::uint8_t viewer_seat() const noexcept { return viewer_; }
    [[nodiscard]] CompetitionMahjongPublicView snapshot() const;
    [[nodiscard]] std::vector<CompetitionMahjongRoundAction> legal_actions() const;
    [[nodiscard]] CompetitionMahjongRoundActionResult apply(
        CompetitionMahjongRoundActionKind kind, std::uint8_t tile, std::uint8_t sequence_start);
    [[nodiscard]] std::uint32_t advance_bots(std::uint8_t level, std::uint64_t seed,
                                            std::uint32_t max_actions);
    [[nodiscard]] bool undo();
    [[nodiscard]] std::vector<std::uint8_t> serialize() const;
    [[nodiscard]] bool restore(const std::vector<std::uint8_t>& data);

private:
    const std::uint8_t viewer_;
    std::unique_ptr<CompetitionMahjongRound> round_;
    mutable std::mutex mutex_;
};

} // namespace mocs::engine
