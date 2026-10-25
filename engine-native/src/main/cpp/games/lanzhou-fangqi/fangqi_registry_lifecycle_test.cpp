#include "mocs/engine/native_engine_registry.hpp"

#ifdef NDEBUG
#error "Fangqi lifecycle tests must be compiled with assertions enabled (-UNDEBUG)"
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
namespace fq = mocs::engine::lanzhou_fangqi;
using mocs::engine::EngineHandle;
using mocs::engine::NativeEngineRegistry;
std::atomic<int> checks{0};

void require(const bool condition, const char* message) {
    ++checks;
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

void invalid_and_cross_type_handles(NativeEngineRegistry& registry) {
    for (const auto handle : {EngineHandle{0}, EngineHandle{-1}, std::numeric_limits<EngineHandle>::max()}) {
        require(!registry.find_fangqi(handle), "invalid Fangqi handle cannot be looked up");
        require(!registry.release_fangqi(handle), "invalid Fangqi handle cannot be released");
    }
    const auto chinese = registry.create_chinese_chess();
    const auto go = registry.create_go(9, 15);
    const auto tiger = registry.create_tiger_goat();
    const auto competition = registry.create_competition_mahjong(925, 0, 0, 0);
    const auto fangqi = registry.create_fangqi();
    require(chinese > 0 && go > chinese && tiger > go && competition > tiger && fangqi > competition,
        "all baseline types and Fangqi use one monotonic handle space");
    for (const auto handle : {chinese, go, tiger, competition}) {
        require(!registry.find_fangqi(handle), "a foreign session is never interpreted as Fangqi");
        require(!registry.release_fangqi(handle), "wrong typed release preserves a foreign session");
    }
    require(!registry.find_chinese_chess(fangqi) && !registry.find_go(fangqi)
        && !registry.find_tiger_goat(fangqi) && !registry.find_competition_mahjong(fangqi),
        "Fangqi handles are absent from every other game table");
    require(!registry.release_chinese_chess(fangqi) && !registry.release_go(fangqi)
        && !registry.release_tiger_goat(fangqi) && !registry.release_competition_mahjong(fangqi),
        "foreign typed releases cannot destroy Fangqi");
    require(!registry.find_go_search_runtime(fangqi) && !registry.find_go_search_request(fangqi),
        "Fangqi is not a Go search resource");
    require(!registry.release_go_search_runtime(fangqi) && !registry.release_go_search_request(fangqi),
        "Go resource release cannot destroy Fangqi");
    require(registry.find_chinese_chess(chinese) && registry.find_go(go)
        && registry.find_tiger_goat(tiger) && registry.find_competition_mahjong(competition)
        && registry.find_fangqi(fangqi), "every session survives all wrong typed releases");
    require(registry.release_fangqi(fangqi), "Fangqi typed release succeeds once");
    require(!registry.release_fangqi(fangqi) && !registry.find_fangqi(fangqi), "typed release is idempotent");
    for (const auto handle : {chinese, go, tiger, competition}) require(registry.release(handle), "release baseline fixture");
    const auto generic = registry.create_fangqi();
    require(generic > fangqi, "removed handle values are never reused");
    require(registry.release(generic) && !registry.find_fangqi(generic) && !registry.release(generic),
        "generic release includes the Fangqi table exactly once");
}

void validated_seed_and_retained_lookup(NativeEngineRegistry& registry) {
    fq::Board board{};
    board[0] = fq::red;
    board[48] = fq::black;
    const auto seeded = registry.create_fangqi_from_position(board, fq::black);
    auto retained = registry.find_fangqi(seeded);
    require(retained && retained->position().board == board && retained->position().to_play == fq::black,
        "authored seed and player survive registration");
    require(registry.release_fangqi(seeded) && !registry.find_fangqi(seeded), "release removes handle ownership");
    const auto before = retained->serialize();
    const auto legal = retained->legal_actions();
    require(!legal.empty() && retained->apply(legal.front()).accepted(), "retained lookup outlives handle removal");
    require(retained->undo() && retained->serialize() == before, "retained lookup still owns complete history");
    auto invalid = board;
    invalid[1] = 3;
    auto excessive = board;
    for (int index = 0; index < 15; ++index) excessive[static_cast<std::size_t>(index)] = fq::red;
    const std::array<fq::Board, 3> invalid_boards{fq::Board{}, invalid, excessive};
    for (const auto& bad : invalid_boards) {
        bool rejected = false;
        try { static_cast<void>(registry.create_fangqi_from_position(bad, fq::red)); }
        catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "invalid seed cannot enter the registry");
    }
    for (const auto side : {std::uint8_t{0}, std::uint8_t{3}}) {
        bool rejected = false;
        try { static_cast<void>(registry.create_fangqi_from_position(board, side)); }
        catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "invalid seed player cannot enter the registry");
    }
    const auto next = registry.create_fangqi();
    require(next > seeded && registry.find_fangqi(next), "rejected creation leaves future registration usable");
    require(registry.release_fangqi(next), "release post-rejection session");
}

void concurrent_unique_registration(NativeEngineRegistry& registry) {
    constexpr int workers = 4;
    constexpr int per_worker = 48;
    std::mutex output_mutex;
    std::vector<EngineHandle> handles;
    std::vector<std::thread> threads;
    handles.reserve(workers * per_worker);
    for (int worker = 0; worker < workers; ++worker) {
        threads.emplace_back([&] {
            for (int iteration = 0; iteration < per_worker; ++iteration) {
                const auto handle = registry.create_fangqi();
                const auto retained = registry.find_fangqi(handle);
                require(retained && retained->position().board == fq::initial_board(), "concurrent registration has an intact game");
                require(!registry.release_go(handle), "concurrent wrong typed release is harmless");
                require(registry.release_fangqi(handle), "each independently owned concurrent handle releases once");
                require(!registry.find_fangqi(handle) && !registry.release_fangqi(handle), "removed concurrent handles stay absent");
                require(retained->position().to_play == fq::red, "retained immutable lookup survives concurrent table changes");
                std::lock_guard lock(output_mutex);
                handles.push_back(handle);
            }
        });
    }
    for (auto& thread : threads) thread.join();
    std::sort(handles.begin(), handles.end());
    require(handles.size() == workers * per_worker && handles.front() > 0
        && std::adjacent_find(handles.begin(), handles.end()) == handles.end(),
        "concurrent creation returns unique positive shared-space handles");
}

void concurrent_lookup_retention_and_release(NativeEngineRegistry& registry) {
    constexpr std::size_t readers = 4;
    const auto handle = registry.create_fangqi();
    std::atomic<std::size_t> acquired{0};
    std::atomic<bool> released{false};
    std::array<bool, readers> survived{};
    std::vector<std::thread> threads;
    for (std::size_t index = 0; index < readers; ++index) {
        threads.emplace_back([&, index] {
            const auto retained = registry.find_fangqi(handle);
            require(static_cast<bool>(retained), "reader acquires shared ownership before release");
            ++acquired;
            while (!released.load()) std::this_thread::yield();
            survived[index] = retained->position().board == fq::initial_board()
                && !registry.find_fangqi(handle);
        });
    }
    while (acquired.load() != readers) std::this_thread::yield();
    require(registry.release_fangqi(handle), "release occurs while every reader retains ownership");
    released = true;
    for (auto& thread : threads) thread.join();
    require(std::all_of(survived.begin(), survived.end(), [](bool value) { return value; }),
        "all retained readers survive release and observe the handle removed");
    require(!registry.release(handle), "no generic duplicate release after retained readers finish");
}
} // namespace

int main() {
    auto& registry = NativeEngineRegistry::instance();
    invalid_and_cross_type_handles(registry);
    validated_seed_and_retained_lookup(registry);
    concurrent_unique_registration(registry);
    concurrent_lookup_retention_and_release(registry);
    std::cout << "Fangqi registry lifecycle tests passed (" << checks.load() << " checks).\n";
}
