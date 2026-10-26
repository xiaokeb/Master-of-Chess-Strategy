#include "mocs/engine/native_engine_registry.hpp"
#include "mocs/engine/lanzhou_mahjong_bot.hpp"

#include <jni.h>

#include <cstdint>
#include <limits>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {

using mocs::engine::EngineHandle;
using mocs::engine::LanzhouMahjongHandle;
using mocs::engine::NativeEngineRegistry;

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    const auto klass = env->FindClass(type);
    if (klass) env->ThrowNew(klass, message);
}

template <typename Result, typename Operation>
Result guarded(JNIEnv* env, Result fallback, Operation&& operation) noexcept {
    try {
        return operation();
    } catch (const std::invalid_argument&) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid Mahjong argument");
    } catch (const std::logic_error&) {
        throw_java(env, "java/lang/IllegalStateException", "Invalid Mahjong session");
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "Mahjong native operation failed");
    }
    return fallback;
}

std::shared_ptr<LanzhouMahjongHandle> require_session(jlong handle) {
    auto session = NativeEngineRegistry::instance().find_lanzhou_mahjong(
        static_cast<EngineHandle>(handle));
    if (!session) throw std::logic_error("Invalid Mahjong handle");
    return session;
}

jintArray ints(JNIEnv* env, const std::vector<jint>& values) {
    auto result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result && !values.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

template <typename Value>
jint optional_code(const std::optional<Value>& value) {
    return value ? static_cast<jint>(*value) : -1;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_createLanzhouMahjong(
    JNIEnv* env, jobject, jlong seed, jint dealer, jlong base, jint viewer
) {
    return guarded(env, static_cast<jlong>(0), [&]() -> jlong {
        if (seed < 0 || dealer < 0 || dealer >= 4 || base <= 0 || viewer < 0 || viewer >= 4) {
            throw std::invalid_argument("Invalid Mahjong setup");
        }
        return NativeEngineRegistry::instance().create_lanzhou_mahjong(
            static_cast<std::uint64_t>(seed), static_cast<std::uint8_t>(dealer),
            static_cast<std::int64_t>(base), static_cast<std::uint8_t>(viewer));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_releaseLanzhouMahjong(
    JNIEnv*, jobject, jlong handle
) {
    (void) NativeEngineRegistry::instance().release_lanzhou_mahjong(
        static_cast<EngineHandle>(handle));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_lanzhouMahjongStatus(
    JNIEnv* env, jobject, jlong handle
) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        const auto session = require_session(handle);
        const auto& game = *session->game;
        const auto& outcome = game.outcome();
        const jint flags = static_cast<jint>(outcome.false_win) |
            (static_cast<jint>(outcome.seven_pairs) << 1) |
            (static_cast<jint>(outcome.ready) << 2) |
            (static_cast<jint>(outcome.kong_draw) << 3) |
            (static_cast<jint>(outcome.last_tile) << 4);
        return ints(env, {
            static_cast<jint>(game.phase()), game.current_player(), game.dealer(),
            static_cast<jint>(game.wall_remaining()), optional_code(game.pending_tile()),
            optional_code(game.pending_player()), optional_code(outcome.winner),
            optional_code(outcome.payer), outcome.next_dealer, flags,
        });
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_lanzhouMahjongSeatView(
    JNIEnv* env, jobject, jlong handle, jint seat
) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        if (seat < 0 || seat >= 4) throw std::invalid_argument("Invalid seat");
        const auto session = require_session(handle);
        const auto& game = *session->game;
        const auto& melds = game.melds(static_cast<std::uint8_t>(seat));
        const auto& discards = game.discards(static_cast<std::uint8_t>(seat));
        const auto& concealed = game.hand(static_cast<std::uint8_t>(seat));
        jint concealed_count = 0;
        for (const auto copies : concealed) concealed_count += copies;
        std::vector<jint> fields{
            concealed_count, static_cast<jint>(game.ready(seat)),
            static_cast<jint>(game.responded(seat)), static_cast<jint>(melds.size()),
        };
        for (const auto& meld : melds) {
            const bool hidden = seat != session->viewer_seat &&
                meld.kind == mocs::engine::LanzhouMahjongMeldKind::concealed_kong;
            fields.insert(fields.end(), {static_cast<jint>(meld.kind),
                hidden ? -1 : static_cast<jint>(meld.tile), meld.from_player});
        }
        fields.push_back(static_cast<jint>(discards.size()));
        for (const auto tile : discards) fields.push_back(tile);
        const bool local = seat == session->viewer_seat;
        fields.push_back(local ? concealed_count : 0);
        if (local) {
            for (std::size_t tile = 0; tile < concealed.size(); ++tile) {
                for (std::uint8_t copy = 0; copy < concealed[tile]; ++copy) {
                    fields.push_back(static_cast<jint>(tile));
                }
            }
        }
        return ints(env, fields);
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_advanceLanzhouMahjongOtherSeats(
    JNIEnv* env, jobject, jlong handle, jint difficulty, jlong seed, jint step_limit
) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        const auto session = require_session(handle);
        if (difficulty < 0 || difficulty > 3 || seed < 0 || step_limit < 1 || step_limit > 512)
            throw std::invalid_argument("Invalid bot limit");
        const auto result = mocs::engine::LanzhouMahjongBot::advance_other_seats(
            *session->game, session->viewer_seat, static_cast<mocs::engine::Difficulty>(difficulty),
            static_cast<std::uint64_t>(seed), static_cast<std::uint16_t>(step_limit));
        return ints(env, {1, static_cast<jint>(result.stop), result.steps});
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_lanzhouMahjongNetScores(
    JNIEnv* env, jobject, jlong handle
) {
    return guarded(env, static_cast<jlongArray>(nullptr), [&]() {
        const auto session = require_session(handle);
        auto result = env->NewLongArray(4);
        if (result) {
            const auto& scores = session->game->net_scores();
            const jlong values[]{scores[0], scores[1], scores[2], scores[3]};
            env->SetLongArrayRegion(result, 0, 4, values);
        }
        return result;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_lanzhouMahjongLegalActions(
    JNIEnv* env, jobject, jlong handle
) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        const auto session = require_session(handle);
        const auto actions = session->game->legal_actions(session->viewer_seat);
        std::vector<jint> fields;
        fields.reserve(actions.size() * 2);
        for (const auto& action : actions) {
            fields.push_back(static_cast<jint>(action.kind));
            fields.push_back(action.tile);
        }
        return ints(env, fields);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_applyLanzhouMahjongAction(
    JNIEnv* env, jobject, jlong handle, jint kind, jint tile
) {
    return guarded(env, static_cast<jint>(2), [&]() -> jint {
        if (kind < 0 || kind > 8 || tile < 0 || tile > 255) {
            throw std::invalid_argument("Invalid action fields");
        }
        const auto session = require_session(handle);
        const auto result = session->game->apply({
            static_cast<mocs::engine::LanzhouMahjongActionKind>(kind), session->viewer_seat,
            static_cast<std::uint8_t>(tile),
        });
        return static_cast<jint>(result.error);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_undoLanzhouMahjong(
    JNIEnv* env, jobject, jlong handle
) {
    return guarded(env, JNI_FALSE, [&]() -> jboolean {
        return require_session(handle)->game->undo() ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_serializeLanzhouMahjong(
    JNIEnv* env, jobject, jlong handle
) {
    return guarded(env, static_cast<jbyteArray>(nullptr), [&]() {
        const auto bytes = require_session(handle)->game->serialize();
        if (bytes.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
            throw std::length_error("Mahjong record too large");
        }
        auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result && !bytes.empty()) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                reinterpret_cast<const jbyte*>(bytes.data()));
        }
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_restoreLanzhouMahjong(
    JNIEnv* env, jobject, jlong handle, jbyteArray data
) {
    return guarded(env, static_cast<jint>(3), [&]() -> jint {
        if (!data) throw std::invalid_argument("Null Mahjong record");
        const auto length = env->GetArrayLength(data);
        if (length < 0 || length > 1024 * 1024) {
            return static_cast<jint>(mocs::engine::EngineError::corrupted_data);
        }
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        if (length > 0) {
            env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
            if (env->ExceptionCheck()) return static_cast<jint>(mocs::engine::EngineError::corrupted_data);
        }
        const auto result = require_session(handle)->game->restore(bytes);
        return static_cast<jint>(result.error);
    });
}
