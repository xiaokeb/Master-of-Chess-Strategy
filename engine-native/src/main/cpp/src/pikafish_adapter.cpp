#include "mocs/engine/pikafish_adapter.hpp"

#include "mocs/engine/chinese_chess.hpp"

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
#include <mutex>
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

class PikafishRuntime final {
public:
    [[nodiscard]] std::optional<EngineAction> choose(
        const std::string& fen,
        const std::vector<EngineAction>& legal_actions,
        const std::string& network_path
    ) {
        std::lock_guard lock(mutex_);
        ensure_engine(network_path);

        if (const auto error = engine_->set_position(fen, {}); error) {
            throw std::invalid_argument("Pikafish rejected the position");
        }

        {
            std::lock_guard callback_lock(callback_mutex_);
            best_move_.clear();
        }

        Stockfish::Search::LimitsType limits;
        // Direct Engine::go callers must set the clock origin themselves;
        // UCI normally does this, but LimitsType leaves startTime unset.
        limits.startTime = Stockfish::now();
        limits.movetime = pikafish_move_time_millis;
        engine_->go(limits);
        engine_->wait_for_search_finished();

        std::string best_move;
        {
            std::lock_guard callback_lock(callback_mutex_);
            best_move = best_move_;
        }
        return decode_pikafish_move(best_move, legal_actions);
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
            [](const Stockfish::Engine::InfoFull&) {}
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
    }

    std::mutex mutex_;
    std::mutex callback_mutex_;
    std::unique_ptr<Stockfish::Engine> engine_;
    std::string loaded_network_path_;
    std::string best_move_;
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
    const std::string& fen,
    const std::vector<EngineAction>& legal_actions,
    const std::string& network_path
) {
    if (legal_actions.empty()) {
        return std::nullopt;
    }
    return runtime().choose(fen, legal_actions, network_path);
}

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
