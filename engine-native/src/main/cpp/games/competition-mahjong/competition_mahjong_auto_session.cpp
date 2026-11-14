#include "competition_mahjong_auto_session.hpp"

#include "competition_mahjong_bot.hpp"

#include <algorithm>
#include <stdexcept>
#include <utility>

namespace mocs::engine {
namespace {
constexpr std::size_t max_record_bytes = 1024 * 1024;
constexpr std::size_t envelope_size = 28;

bool terminal(const CompetitionMahjongRoundPhase phase) {
    return phase == CompetitionMahjongRoundPhase::won ||
           phase == CompetitionMahjongRoundPhase::exhaustive_draw;
}

std::uint32_t crc32(const std::vector<std::uint8_t>& bytes, const std::size_t count) {
    std::uint32_t crc = 0xffffffffU;
    for (std::size_t i = 0; i < count; ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ ((crc & 1) ? 0xedb88320U : 0U);
    }
    return ~crc;
}

void put(std::vector<std::uint8_t>& bytes, const std::uint64_t value, const int width) {
    for (int i = 0; i < width; ++i) bytes.push_back(static_cast<std::uint8_t>(value >> (8 * i)));
}

std::uint64_t get(const std::vector<std::uint8_t>& bytes, const std::size_t offset,
                  const int width) {
    std::uint64_t value = 0;
    for (int i = 0; i < width; ++i)
        value |= std::uint64_t(bytes[offset + static_cast<std::size_t>(i)]) << (8 * i);
    return value;
}

// Stable public-frame hashing makes batching, restore and undo independent of
// call timing. Neither opponents' hands nor the private wall influence the seed.
std::uint64_t decision_seed(const CompetitionMahjongPublicView& view,
                            const std::uint64_t base) {
    std::uint64_t hash = 14695981039346656037ULL;
    const auto feed = [&](const std::uint64_t value) {
        for (int i = 0; i < 8; ++i) {
            hash ^= static_cast<std::uint8_t>(value >> (8 * i));
            hash *= 1099511628211ULL;
        }
    };
    const auto tiles = [&](const std::vector<CompetitionMahjongTile>& values) {
        feed(values.size());
        for (const auto tile : values) feed(tile);
    };
    feed(base);
    feed(static_cast<std::uint8_t>(view.phase));
    feed(view.viewer);
    feed(view.dealer);
    feed(view.round_wind);
    feed(view.current_player);
    feed(view.wall_remaining);
    feed(view.pending_tile ? *view.pending_tile : 255);
    feed(view.pending_player ? *view.pending_player : 255);
    feed(view.drawn_tile ? *view.drawn_tile : 255);
    feed(view.can_declare_win);
    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        const auto& public_seat = view.seats[seat];
        feed(public_seat.concealed_count);
        tiles(public_seat.local_concealed);
        tiles(public_seat.flowers);
        tiles(public_seat.discards);
        feed(public_seat.win_disabled);
        feed(static_cast<std::uint32_t>(view.score_delta[seat]));
        feed(public_seat.melds.size());
        for (const auto& meld : public_seat.melds) {
            feed(static_cast<std::uint8_t>(meld.kind));
            feed(meld.tile ? *meld.tile : 255);
            feed(meld.from_player);
            feed(meld.claimed_position);
            feed(meld.added_kong);
        }
    }
    return hash;
}
} // namespace

CompetitionMahjongAutoSession::CompetitionMahjongAutoSession(
    const std::uint64_t wall_seed, const std::uint8_t dealer, const std::uint8_t wind,
    const std::uint8_t viewer, const std::array<std::uint8_t, 4> levels,
    const std::uint64_t seed)
    : CompetitionMahjongAutoSession(competition_mahjong_shuffled_wall(wall_seed),
        dealer, wind, viewer, levels, seed) {}

CompetitionMahjongAutoSession::CompetitionMahjongAutoSession(
    std::vector<CompetitionMahjongTile> wall, const std::uint8_t dealer,
    const std::uint8_t wind, const std::uint8_t viewer,
    const std::array<std::uint8_t, 4> levels, const std::uint64_t seed)
    : viewer_(viewer), levels_(levels), decision_seed_(seed), round_(std::move(wall), dealer, wind) {
    if (viewer >= 4 || std::any_of(levels.begin(), levels.end(),
        [](const auto level) { return level > 3; }))
        throw std::invalid_argument("Invalid automatic Mahjong configuration");
}

CompetitionMahjongPublicView CompetitionMahjongAutoSession::snapshot() const {
    std::lock_guard lock(mutex_);
    return competition_mahjong_public_view(round_, viewer_);
}

CompetitionMahjongAutoAdvance CompetitionMahjongAutoSession::advance(
    const std::uint32_t max_actions, const CompetitionMahjongBotPolicy policy) {
    if (max_actions == 0 || max_actions > 32)
        throw std::invalid_argument("Invalid automatic Mahjong budget");
    std::lock_guard lock(mutex_);
    if (terminal(round_.view().phase)) return {0, true};
    auto candidate = round_;
    CompetitionMahjongAutoAdvance result{};
    while (result.accepted_actions < max_actions && !terminal(candidate.view().phase)) {
        bool moved = false;
        for (std::uint8_t seat = 0; seat < 4; ++seat) {
            if (candidate.legal_actions(seat).empty()) continue;
            const auto public_view = competition_mahjong_public_view(candidate, seat);
            const auto action = competition_mahjong_choose_bot_action(candidate, seat,
                levels_[seat], decision_seed(public_view, decision_seed_), policy);
            if (!action) throw std::logic_error("Automatic Mahjong has no legal decision");
            const auto applied = candidate.apply(*action);
            if (!applied.accepted || applied.false_hu)
                throw std::logic_error("Automatic Mahjong rejected a baseline decision");
            ++result.accepted_actions;
            moved = true;
            break; // Re-evaluate claims and priority after every accepted event.
        }
        if (!moved) throw std::logic_error("Automatic Mahjong stalled before the terminal phase");
    }
    result.terminal = terminal(candidate.view().phase);
    round_ = std::move(candidate);
    return result;
}

bool CompetitionMahjongAutoSession::undo() {
    std::lock_guard lock(mutex_);
    return round_.undo();
}

std::vector<std::uint8_t> CompetitionMahjongAutoSession::serialize() const {
    std::lock_guard lock(mutex_);
    const auto payload = round_.serialize();
    if (payload.size() > max_record_bytes - envelope_size)
        throw std::length_error("Automatic Mahjong record too large");
    std::vector<std::uint8_t> bytes{'M', 'C', 'M', 'A', 1, viewer_};
    bytes.insert(bytes.end(), levels_.begin(), levels_.end());
    bytes.insert(bytes.end(), {0, 0});
    put(bytes, decision_seed_, 8);
    put(bytes, payload.size(), 4);
    bytes.insert(bytes.end(), payload.begin(), payload.end());
    put(bytes, crc32(bytes, bytes.size()), 4);
    return bytes;
}

bool CompetitionMahjongAutoSession::restore(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() < envelope_size || bytes.size() > max_record_bytes ||
        bytes[0] != 'M' || bytes[1] != 'C' || bytes[2] != 'M' || bytes[3] != 'A' ||
        bytes[4] != 1 || bytes[5] != viewer_ || bytes[10] != 0 || bytes[11] != 0 ||
        !std::equal(levels_.begin(), levels_.end(), bytes.begin() + 6) ||
        get(bytes, 12, 8) != decision_seed_ ||
        get(bytes, 20, 4) != bytes.size() - envelope_size ||
        get(bytes, bytes.size() - 4, 4) != crc32(bytes, bytes.size() - 4)) return false;
    const std::vector<std::uint8_t> payload(bytes.begin() + 24, bytes.end() - 4);
    std::lock_guard lock(mutex_);
    return round_.restore(payload);
}

} // namespace mocs::engine
