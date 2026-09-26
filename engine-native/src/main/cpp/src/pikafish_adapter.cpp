#include "mocs/engine/pikafish_adapter.hpp"

#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/pikafish_difficulty.hpp"

#include "attacks.h"
#include "engine.h"
#include "misc.h"
#include "position.h"
#include "search.h"
#include "uci.h"

#include <algorithm>
#include <deque>
#include <filesystem>
#include <memory>
#include <map>
#include <mutex>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>

namespace mocs::engine {
namespace {

constexpr std::int32_t board_last_rank = 9;
std::once_flag pikafish_initialization_flag;

void initialize_pikafish() {
    std::call_once(pikafish_initialization_flag, [] {
        Stockfish::Attacks::init();
        Stockfish::Position::init();
    });
}

std::string board_and_side(const std::string& fen) {
    std::istringstream input(fen);
    std::string board, side;
    if (!(input >> board >> side) || (side != "w" && side != "b")) {
        throw std::invalid_argument("Invalid search position identity");
    }
    return board + ' ' + side;
}

class PikafishRuntime final {
public:
    [[nodiscard]] std::optional<EngineAction> choose(
        const ChineseChessSearchPosition& position,
        const std::vector<EngineAction>& legal_actions,
        const std::string& network_path,
        const Difficulty difficulty,
        const std::optional<std::uint64_t> selection_seed
    ) {
        std::lock_guard lock(mutex_);
        const auto profile = pikafish_profile(difficulty);
        ensure_engine(network_path);
        if (last_difficulty_ != difficulty) {
            // Do not let a preceding master search strengthen a beginner turn
            // through its transposition table and learned move ordering.
            engine_->search_clear();
            last_difficulty_ = difficulty;
        }
        std::istringstream option("name MultiPV value " + std::to_string(profile.multi_pv));
        engine_->get_options().setoption(option);

        if (const auto error = engine_->set_position(position.initial_fen, position.moves); error) {
            throw std::invalid_argument("Pikafish rejected the position history");
        }
        // Replaying must reach the authoritative board and side. Do not compare
        // rule60 clocks: upstream discounts checks beyond ten per side, while
        // the local rules currently retain a raw no-capture ply counter.
        if (board_and_side(engine_->fen()) != board_and_side(position.current_fen)) {
            throw std::invalid_argument("Pikafish history does not reach the current position");
        }

        {
            std::lock_guard callback_lock(callback_mutex_);
            best_move_.clear();
            candidates_.clear();
            candidate_count_ = std::min(profile.multi_pv, legal_actions.size());
        }

        Stockfish::Search::LimitsType limits;
        // Direct Engine::go callers must set the clock origin themselves;
        // UCI normally does this, but LimitsType leaves startTime unset.
        limits.startTime = Stockfish::now();
        limits.movetime = profile.move_time_millis;
        limits.depth = profile.depth;
        limits.nodes = profile.nodes;
        // Search only authoritative legal roots, not merely validate the final
        // move after the upstream engine has searched a different root set.
        for (const auto& legal : legal_actions) {
            const auto& a = legal.arguments;
            limits.searchmoves.push_back(std::string{
                static_cast<char>('a' + a[0]), static_cast<char>('9' - a[1]),
                static_cast<char>('a' + a[2]), static_cast<char>('9' - a[3])});
        }
        engine_->go(limits);
        engine_->wait_for_search_finished();

        std::string best_move;
        {
            std::lock_guard callback_lock(callback_mutex_);
            best_move = best_move_;
            if (profile.deviation_percent != 0) {
                for (auto it = candidates_.rbegin(); it != candidates_.rend(); ++it) {
                    const auto& entries = it->second;
                    if (entries.size() != candidate_count_ || std::any_of(
                        entries.begin(), entries.end(), [](const auto& entry) { return !entry; }
                    )) continue;
                    auto ordered = entries;
                    std::stable_sort(ordered.begin(), ordered.end(), [](const auto& a, const auto& b) {
                        return a->score > b->score;
                    });
                    std::vector<int> scores;
                    for (const auto& entry : ordered) scores.push_back(entry->score);
                    const auto index = pikafish_candidate_index(
                        scores, profile, selection_seed ? *selection_seed : random_());
                    best_move = ordered[index]->move;
                    break;
                }
            }
        }
        return decode_pikafish_move(best_move, legal_actions);
    }

    void reset_search() {
        std::lock_guard lock(mutex_);
        if (engine_) engine_->search_clear();
        last_difficulty_.reset();
    }

private:
    void ensure_engine(const std::string& network_path) {
        if (network_path.empty()) {
            throw std::invalid_argument("Pikafish network path is empty");
        }

        std::error_code error;
        const auto size = std::filesystem::file_size(network_path, error);
        if (error || size != pikafish_network_size) {
            throw std::invalid_argument("Pikafish network file is invalid");
        }
        if (engine_ && loaded_network_path_ == network_path) {
            return;
        }

        initialize_pikafish();

        auto engine = std::make_unique<Stockfish::Engine>();
        engine->set_on_update_no_moves(
            [](const Stockfish::Engine::InfoShort&) {}
        );
        engine->set_on_update_full(
            [this](const Stockfish::Engine::InfoFull& info) {
                std::lock_guard callback_lock(callback_mutex_);
                if (!info.bound.empty() || info.multiPV == 0 || info.multiPV > candidate_count_ ||
                    info.pv.size() < 4) return;
                const auto score = info.score.is<Stockfish::Score::Mate>()
                    ? (info.score.get<Stockfish::Score::Mate>().plies >= 0
                        ? 1'000'000 - info.score.get<Stockfish::Score::Mate>().plies
                        : -1'000'000 - info.score.get<Stockfish::Score::Mate>().plies)
                    : info.score.get<Stockfish::Score::InternalUnits>().value;
                auto& depth = candidates_[info.depth];
                depth.resize(candidate_count_);
                depth[info.multiPV - 1] = Candidate{std::string(info.pv.substr(0, 4)), score};
            }
        );
        engine->set_on_iter(
            [](const Stockfish::Engine::InfoIter&) {}
        );
        engine->set_on_start([] {});
        engine->set_on_bestmove(
            [this](const std::string_view move, std::string_view) {
                std::lock_guard callback_lock(callback_mutex_);
                best_move_.assign(move);
            }
        );
        engine->set_on_verify_network([](std::string_view) {});

        std::istringstream option(
            "name EvalFile value " + network_path
        );
        engine->get_options().setoption(option);
        engine->verify_network();

        engine_ = std::move(engine);
        loaded_network_path_ = network_path;
        last_difficulty_.reset();
    }

    std::mutex mutex_;
    std::mutex callback_mutex_;
    std::unique_ptr<Stockfish::Engine> engine_;
    std::string loaded_network_path_;
    std::string best_move_;
    struct Candidate { std::string move; int score; };
    std::map<int, std::vector<std::optional<Candidate>>> candidates_;
    std::size_t candidate_count_{0};
    std::optional<Difficulty> last_difficulty_;
    std::mt19937_64 random_{std::random_device{}()};
};

[[nodiscard]] PikafishRuntime& runtime() {
    static PikafishRuntime instance;
    return instance;
}

}  // namespace

std::optional<EngineAction> decode_pikafish_move(
    const std::string_view move,
    const std::vector<EngineAction>& legal_actions
) noexcept {
    if (
        move.size() != 4 ||
        move[0] < 'a' || move[0] > 'i' ||
        move[1] < '0' || move[1] > '9' ||
        move[2] < 'a' || move[2] > 'i' ||
        move[3] < '0' || move[3] > '9'
    ) {
        return std::nullopt;
    }

    const auto candidate = EngineAction{
        board_move_action_kind,
        {
            move[0] - 'a',
            board_last_rank - (move[1] - '0'),
            move[2] - 'a',
            board_last_rank - (move[3] - '0'),
        },
    };
    const auto match = std::find_if(
        legal_actions.begin(),
        legal_actions.end(),
        [&candidate](const EngineAction& legal) {
            return legal.kind == candidate.kind &&
                legal.arguments == candidate.arguments;
        }
    );
    return match == legal_actions.end()
        ? std::nullopt
        : std::make_optional(*match);
}

std::optional<EngineAction> choose_pikafish_move(
    const ChineseChessSearchPosition& position,
    const std::vector<EngineAction>& legal_actions,
    const std::string& network_path,
    const Difficulty difficulty,
    const std::optional<std::uint64_t> selection_seed
) {
    if (position.result != GameResult::ongoing || legal_actions.empty()) {
        return std::nullopt;
    }
    return runtime().choose(position, legal_actions, network_path, difficulty, selection_seed);
}

void reset_pikafish_search() { runtime().reset_search(); }

std::optional<GameResult> adjudicate_pikafish_repetition(
    const std::string& initial_fen,
    const std::vector<std::string>& moves
) noexcept {
    try {
        initialize_pikafish();
        auto states = Stockfish::StateListPtr(
            new std::deque<Stockfish::StateInfo>(1)
        );
        Stockfish::Position position;
        if (position.set(initial_fen, &states->back())) {
            return std::nullopt;
        }
        for (const auto& encoded_move : moves) {
            const auto move = Stockfish::UCIEngine::to_move(
                position,
                encoded_move
            );
            if (move == Stockfish::Move::none()) {
                return std::nullopt;
            }
            states->emplace_back();
            position.do_move(move, states->back(), nullptr);
        }

        Stockfish::Value value = Stockfish::VALUE_DRAW;
        if (!position.rule_judge(value)) {
            return std::nullopt;
        }
        if (value == Stockfish::VALUE_DRAW) {
            return GameResult::draw;
        }
        const auto side_to_move_is_red =
            position.side_to_move() == Stockfish::WHITE;
        const auto side_to_move_wins = value > Stockfish::VALUE_DRAW;
        const auto red_wins = side_to_move_is_red == side_to_move_wins;
        return red_wins
            ? GameResult::first_player_win
            : GameResult::second_player_win;
    } catch (...) {
        return std::nullopt;
    }
}

}  // namespace mocs::engine
