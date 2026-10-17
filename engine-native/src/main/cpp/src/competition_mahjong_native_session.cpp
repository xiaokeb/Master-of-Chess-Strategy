#include "mocs/engine/competition_mahjong_native_session.hpp"

#include "../games/competition-mahjong/competition_mahjong_bot.hpp"
#include "../games/competition-mahjong/competition_mahjong_public_view.hpp"

#include <stdexcept>
#include <utility>

namespace mocs::engine {
namespace {
constexpr std::size_t max_record_bytes = 1024 * 1024;

std::uint32_t crc32(const std::vector<std::uint8_t>& bytes, std::size_t count) {
    std::uint32_t crc = 0xffffffffU;
    for (std::size_t i = 0; i < count; ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ ((crc & 1) ? 0xedb88320U : 0U);
    }
    return ~crc;
}

void put32(std::vector<std::uint8_t>& bytes, std::uint32_t value) {
    for (int shift = 0; shift < 32; shift += 8) bytes.push_back(value >> shift);
}

std::uint32_t get32(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
    std::uint32_t result = 0;
    for (int i = 0; i < 4; ++i) result |= std::uint32_t(bytes[offset + i]) << (8 * i);
    return result;
}
} // namespace

CompetitionMahjongNativeSession::CompetitionMahjongNativeSession(
    std::uint64_t seed, std::uint8_t dealer, std::uint8_t wind, std::uint8_t viewer)
    : CompetitionMahjongNativeSession(competition_mahjong_shuffled_wall(seed), dealer, wind, viewer) {}

CompetitionMahjongNativeSession::CompetitionMahjongNativeSession(
    std::vector<std::uint8_t> wall, std::uint8_t dealer, std::uint8_t wind, std::uint8_t viewer)
    : viewer_(viewer) {
    if (viewer >= 4 || dealer >= 4 || wind >= 4) throw std::invalid_argument("Invalid Mahjong setup");
    round_ = std::make_unique<CompetitionMahjongRound>(std::move(wall), dealer, wind);
}

CompetitionMahjongNativeSession::~CompetitionMahjongNativeSession() = default;

CompetitionMahjongPublicView CompetitionMahjongNativeSession::snapshot() const {
    std::lock_guard lock(mutex_);
    return competition_mahjong_public_view(*round_, viewer_);
}

std::vector<CompetitionMahjongRoundAction> CompetitionMahjongNativeSession::legal_actions() const {
    std::lock_guard lock(mutex_);
    return round_->legal_actions(viewer_);
}

CompetitionMahjongRoundActionResult CompetitionMahjongNativeSession::apply(
    CompetitionMahjongRoundActionKind kind, std::uint8_t tile, std::uint8_t sequence_start) {
    std::lock_guard lock(mutex_);
    return round_->apply({kind, viewer_, tile, sequence_start});
}

std::uint32_t CompetitionMahjongNativeSession::advance_bots(
    std::uint8_t level, std::uint64_t seed, std::uint32_t max_actions) {
    if (level > 3 || max_actions == 0 || max_actions > 256)
        throw std::invalid_argument("Invalid Mahjong bot budget");
    std::lock_guard lock(mutex_);
    std::uint32_t advanced = 0;
    while (advanced < max_actions && round_->legal_actions(viewer_).empty()) {
        bool moved = false;
        for (std::uint8_t seat = 0; seat < 4; ++seat) {
            if (seat == viewer_ || round_->legal_actions(seat).empty()) continue;
            const auto action = competition_mahjong_choose_bot_action(*round_, seat, level, seed + advanced);
            if (!action || !round_->apply(*action).accepted)
                throw std::logic_error("Mahjong bot failed to choose a legal action");
            ++advanced;
            moved = true;
            break; // Re-evaluate the phase and local choices after each accepted event.
        }
        if (!moved) break;
    }
    return advanced;
}

bool CompetitionMahjongNativeSession::undo() {
    std::lock_guard lock(mutex_);
    return round_->undo();
}

std::vector<std::uint8_t> CompetitionMahjongNativeSession::serialize() const {
    std::lock_guard lock(mutex_);
    const auto payload = round_->serialize();
    if (payload.size() > max_record_bytes - 16) throw std::length_error("Mahjong record too large");
    std::vector<std::uint8_t> bytes{'M','C','M','S',1,viewer_,0,0};
    put32(bytes, static_cast<std::uint32_t>(payload.size()));
    bytes.insert(bytes.end(), payload.begin(), payload.end());
    put32(bytes, crc32(bytes, bytes.size()));
    return bytes;
}

bool CompetitionMahjongNativeSession::restore(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() < 16 || bytes.size() > max_record_bytes ||
        bytes[0] != 'M' || bytes[1] != 'C' || bytes[2] != 'M' || bytes[3] != 'S' ||
        bytes[4] != 1 || bytes[5] != viewer_ || bytes[6] != 0 || bytes[7] != 0 ||
        get32(bytes, 8) != bytes.size() - 16 ||
        get32(bytes, bytes.size() - 4) != crc32(bytes, bytes.size() - 4)) return false;
    const std::vector<std::uint8_t> payload(bytes.begin() + 12, bytes.end() - 4);
    std::lock_guard lock(mutex_);
    return round_->restore(payload); // Round restores by validated candidate replay, atomically.
}

} // namespace mocs::engine
