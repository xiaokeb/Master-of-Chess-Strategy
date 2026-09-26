// Offline calibration only. Paired colors reduce first-move bias; capped games
// remain unfinished and must never be silently counted as draws or wins.
#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/pikafish_adapter.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using namespace mocs::engine;
using Move = std::array<std::int32_t, 4>;
using Clock = std::chrono::steady_clock;

// Frozen project-authored opening suite, version 1. Keep stable across tuning.
const std::array<std::array<Move, 4>, 6> openings{{
    {{{7,7,4,7}, {7,0,6,2}, {7,9,6,7}, {8,0,7,0}}},
    {{{6,9,4,7}, {2,0,4,2}, {1,9,2,7}, {1,0,2,2}}},
    {{{4,6,4,5}, {4,3,4,4}, {1,9,2,7}, {7,0,6,2}}},
    {{{2,6,2,5}, {1,2,2,2}, {1,9,2,7}, {7,0,6,2}}},
    {{{1,7,4,7}, {1,2,4,2}, {1,9,2,7}, {1,0,2,2}}},
    {{{0,6,0,5}, {1,0,2,2}, {0,9,0,8}, {8,3,8,4}}},
}};

int bounded_number(const char* value, const int low, const int high) {
    const std::string text(value);
    if (text.empty() || text.find_first_not_of("0123456789") != std::string::npos) {
        throw std::invalid_argument("expected a decimal integer");
    }
    const auto number = std::stoi(text);
    if (number < low || number > high) throw std::out_of_range("argument range");
    return number;
}

std::string uci(const EngineAction& action) {
    const auto& p = action.arguments;
    return {static_cast<char>('a' + p[0]), static_cast<char>('9' - p[1]),
            static_cast<char>('a' + p[2]), static_cast<char>('9' - p[3])};
}

void latency(const char* name, std::vector<double> samples) {
    std::sort(samples.begin(), samples.end());
    double sum = 0;
    for (const auto value : samples) sum += value;
    const auto percentile = samples.empty() ? 0.0 :
        samples[(samples.size() * 95 + 99) / 100 - 1];
    std::cout << ",\"" << name << "\":{\"moves\":" << samples.size()
              << ",\"mean_ms\":" << (samples.empty() ? 0.0 : sum / samples.size())
              << ",\"p95_ms\":" << percentile
              << ",\"max_ms\":" << (samples.empty() ? 0.0 : samples.back()) << '}';
}
} // namespace

int main(const int argc, const char* argv[]) {
    try {
        if (argc < 2 || argc > 5) throw std::invalid_argument("argument count");
        const std::string pair(argv[1]);
        Difficulty weak;
        Difficulty strong;
        if (pair == "easy-medium") {
            weak = Difficulty::easy; strong = Difficulty::medium;
        } else if (pair == "medium-hard") {
            weak = Difficulty::medium; strong = Difficulty::hard;
        } else if (pair == "hard-master") {
            weak = Difficulty::hard; strong = Difficulty::master;
        } else {
            throw std::invalid_argument("unknown difficulty pair");
        }
        const int count = argc >= 3 ? bounded_number(argv[2], 1, 6) : 6;
        const int limit = argc >= 4 ? bounded_number(argv[3], 16, 600) : 240;
        const std::string network = argc >= 5 ? argv[4] : "";
        if (strong == Difficulty::master && network.empty()) {
            throw std::invalid_argument("master requires a verified NNUE path");
        }
        int wins = 0, losses = 0, draws = 0, unfinished = 0;
        std::vector<double> strong_times, weak_times;
        std::cout << "{\"type\":\"config\",\"suite\":1,\"pair\":\"" << pair
                  << "\",\"openings\":" << count << ",\"max_search_plies\":" << limit
                  << ",\"master_move_ms\":" << pikafish_move_time_millis << "}\n";
        for (int opening = 0; opening < count; ++opening) {
            for (int strong_side = 0; strong_side < 2; ++strong_side) {
                ChineseChessEngine engine;
                for (const auto& move : openings[opening]) {
                    if (!engine.apply(make_board_move(move[0], move[1], move[2], move[3])).accepted) {
                        throw std::runtime_error("invalid frozen opening");
                    }
                }
                const auto initial_fen = engine.fen();
                std::vector<std::string> moves;
                while (engine.game_result() == GameResult::ongoing &&
                       moves.size() < static_cast<std::size_t>(limit)) {
                    const bool stronger_turn = engine.current_player() == strong_side;
                    const auto difficulty = stronger_turn ? strong : weak;
                    const auto start = Clock::now();
                    const auto move = difficulty == Difficulty::master
                        ? choose_pikafish_move(engine.fen(), engine.legal_actions(), network)
                        : engine.best_move(difficulty);
                    const auto elapsed = std::chrono::duration<double, std::milli>(
                        Clock::now() - start).count();
                    if (!move || !engine.apply(*move).accepted) {
                        throw std::runtime_error("AI returned no legal move in an ongoing game");
                    }
                    (stronger_turn ? strong_times : weak_times).push_back(elapsed);
                    moves.push_back(uci(*move));
                }
                const auto result = engine.game_result();
                std::string outcome;
                if (result == GameResult::ongoing) {
                    ++unfinished; outcome = "unfinished";
                } else if (result == GameResult::draw) {
                    ++draws; outcome = "draw";
                } else {
                    const int winner = result == GameResult::first_player_win ? 0 : 1;
                    const bool strong_won = winner == strong_side;
                    strong_won ? ++wins : ++losses;
                    outcome = strong_won ? "strong_win" : "weak_win";
                }
                std::cout << "{\"type\":\"game\",\"opening\":" << opening + 1
                          << ",\"strong_side\":" << strong_side << ",\"outcome\":\""
                          << outcome << "\",\"initial_fen\":\"" << initial_fen
                          << "\",\"final_fen\":\"" << engine.fen() << "\",\"moves\":[";
                for (std::size_t i = 0; i < moves.size(); ++i) {
                    if (i != 0) std::cout << ',';
                    std::cout << '"' << moves[i] << '"';
                }
                std::cout << "]}\n" << std::flush;
            }
        }
        const int completed = wins + losses + draws;
        std::cout << "{\"type\":\"summary\",\"strong_wins\":" << wins
                  << ",\"weak_wins\":" << losses << ",\"draws\":" << draws
                  << ",\"unfinished\":" << unfinished << ",\"completed\":" << completed
                  << ",\"strong_score\":";
        if (completed == 0) std::cout << "null";
        else std::cout << (wins + 0.5 * draws) / completed;
        std::cout << ",\"decisive_strong_win_rate\":";
        if (wins + losses == 0) std::cout << "null";
        else std::cout << static_cast<double>(wins) / (wins + losses);
        latency("strong_latency", strong_times);
        latency("weak_latency", weak_times);
        std::cout << "}\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "calibration failed: " << error.what()
                  << "\nusage: mocs_benchmark_xiangqi_ai <easy-medium|medium-hard|hard-master>"
                  << " [openings 1..6] [max-search-plies 16..600] [verified NNUE path]\n";
        return 2;
    }
}
