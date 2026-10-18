#include "mocs/engine/tiger_goat_ai.hpp"

#include <jni.h>
#include <array>
#include <chrono>
#include <cstdint>
#include <new>
#include <vector>

namespace {
namespace tg = mocs::engine::tiger_goat;
using Clock = std::chrono::steady_clock;

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    // A VM allocation/callback exception is authoritative; never overwrite it.
    if (env->ExceptionCheck()) return;
    const auto klass = env->FindClass(type);
    if (klass != nullptr) env->ThrowNew(klass, message);
}

struct Cancellation {
    JNIEnv* env;
    jobject token;
    jmethodID get;
    bool failed = false;

    bool requested() noexcept {
        if (failed || env->ExceptionCheck()) {
            failed = true;
            return true;
        }
        const auto value = env->CallBooleanMethod(token, get);
        if (env->ExceptionCheck()) {
            failed = true;
            return true;
        }
        return value == JNI_TRUE;
    }

    static bool poll(void* context) noexcept {
        return static_cast<Cancellation*>(context)->requested();
    }
};

jlong stop_code(const tg::SearchStop stop) noexcept {
    // Wire codes are explicit, independent of the C++ enum representation.
    switch (stop) {
    case tg::SearchStop::depth_completed: return 0;
    case tg::SearchStop::no_legal_action: return 1;
    case tg::SearchStop::node_limit: return 2;
    case tg::SearchStop::time_limit: return 3;
    case tg::SearchStop::cancelled: return 4;
    case tg::SearchStop::invalid_limits: return 5;
    case tg::SearchStop::rule_rejected: return 6;
    }
    return 5;
}

jlong action_code(const tg::ActionKind kind) noexcept {
    switch (kind) {
    case tg::ActionKind::remove_goat: return 0;
    case tg::ActionKind::place_goat: return 1;
    case tg::ActionKind::step: return 2;
    case tg::ActionKind::capture: return 3;
    case tg::ActionKind::end_chain: return 4;
    case tg::ActionKind::resign: return 5;
    case tg::ActionKind::pass_goat: return 6;
    }
    return -1;
}

jlongArray result_array(JNIEnv* env, const tg::SearchResult& result,
                       const Clock::time_point started) {
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        Clock::now() - started).count();
    const std::array<jlong, 11> wire{{
        1, stop_code(result.stop), result.action ? 1 : 0,
        result.action ? action_code(result.action->kind) : -1,
        result.action ? result.action->from : -1,
        result.action ? result.action->to : -1,
        result.score ? 1 : 0, result.score.value_or(0),
        result.completed_turn_depth, static_cast<jlong>(result.visited_nodes), elapsed,
    }};
    const auto out = env->NewLongArray(static_cast<jsize>(wire.size()));
    if (out != nullptr) {
        env->SetLongArrayRegion(out, 0, static_cast<jsize>(wire.size()), wire.data());
    }
    return env->ExceptionCheck() ? nullptr : out;
}
}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatSearchBindings_search(
    JNIEnv* env, jobject, jbyteArray snapshot, jint turn_depth,
    jlong node_limit, jlong time_limit_millis, jobject cancelled
) noexcept {
    const auto started = Clock::now();
    if (env->ExceptionCheck()) return nullptr;
    try {
        if (snapshot == nullptr || cancelled == nullptr || turn_depth < 1
            || turn_depth > static_cast<jint>(tg::maximum_search_turn_depth)
            || node_limit < 0 || time_limit_millis < 0
            || time_limit_millis > tg::maximum_search_time.count()) {
            throw_java(env, "java/lang/IllegalArgumentException", "Invalid tiger-goat search input");
            return nullptr;
        }
        const auto length = env->GetArrayLength(snapshot);
        if (env->ExceptionCheck()) return nullptr;
        if (length < 53 || length > 196658) {
            throw_java(env, "java/lang/IllegalArgumentException", "Invalid tiger-goat snapshot size");
            return nullptr;
        }
        const auto atomic_boolean = env->FindClass("java/util/concurrent/atomic/AtomicBoolean");
        if (atomic_boolean == nullptr || env->ExceptionCheck()) return nullptr;
        if (!env->IsInstanceOf(cancelled, atomic_boolean)) {
            throw_java(env, "java/lang/IllegalArgumentException", "Invalid tiger-goat cancellation token");
            return nullptr;
        }
        const auto get = env->GetMethodID(atomic_boolean, "get", "()Z");
        if (get == nullptr || env->ExceptionCheck()) return nullptr;
        // These JNI references and JNIEnv belong only to this synchronous call.
        Cancellation cancellation{env, cancelled, get};
        tg::SearchResult result;
        if (cancellation.requested()) {
            if (env->ExceptionCheck()) return nullptr;
            result.stop = tg::SearchStop::cancelled;
            return result_array(env, result, started);
        }

        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(snapshot, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        tg::TigerGoatGame game;
        // Full rule replay, never a borrowed session handle or a board-only guess.
        if (!game.restore(bytes)) {
            throw_java(env, "java/lang/IllegalArgumentException", "Invalid tiger-goat snapshot");
            return nullptr;
        }
        const auto spent = std::chrono::duration_cast<std::chrono::milliseconds>(
            Clock::now() - started);
        if (cancellation.requested()) {
            result.stop = tg::SearchStop::cancelled;
        } else if (spent.count() >= time_limit_millis) {
            result.stop = tg::SearchStop::time_limit;
        } else {
            result = tg::search_action(game, {
                static_cast<unsigned>(turn_depth), static_cast<std::uint64_t>(node_limit),
                std::chrono::milliseconds{time_limit_millis} - spent,
            }, {Cancellation::poll, &cancellation});
        }
        // Cancellation wins a completion race; even a legal fallback is not an
        // instruction to apply an action after its owner has retired the request.
        if (cancellation.requested()) {
            result.stop = tg::SearchStop::cancelled;
            result.action.reset();
            result.score.reset();
        }
        if (env->ExceptionCheck()) return nullptr;
        if (result.stop == tg::SearchStop::rule_rejected
            || result.stop == tg::SearchStop::invalid_limits
            || result.stop == tg::SearchStop::no_legal_action) {
            result.action.reset();
            result.score.reset();
        }
        return result_array(env, result, started);
    } catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Tiger-goat search allocation failed");
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "Tiger-goat search failed");
    }
    return nullptr;
}
