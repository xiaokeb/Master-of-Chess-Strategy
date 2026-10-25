#include "mocs/engine/lanzhou_fangqi_ai.hpp"
#include "mocs/engine/native_engine_registry.hpp"

#include <jni.h>

#include <array>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
namespace fq = mocs::engine::lanzhou_fangqi;
using mocs::engine::EngineHandle;
using mocs::engine::NativeEngineRegistry;

template <typename T, typename Operation>
T guarded(JNIEnv* env, T fallback, Operation&& operation) noexcept {
    try {
        return operation();
    } catch (...) {
        const auto type = env->FindClass("java/lang/RuntimeException");
        if (type != nullptr) env->ThrowNew(type, "Fangqi native operation failed");
        return fallback;
    }
}

template <typename Operation>
void guarded_void(JNIEnv* env, Operation&& operation) noexcept {
    try {
        operation();
    } catch (...) {
        const auto type = env->FindClass("java/lang/RuntimeException");
        if (type != nullptr) env->ThrowNew(type, "Fangqi native operation failed");
    }
}

std::shared_ptr<fq::Game> require_game(jlong handle) {
    auto game = NativeEngineRegistry::instance().find_fangqi(
        static_cast<EngineHandle>(handle));
    if (!game) throw std::invalid_argument("Invalid Fangqi handle");
    return game;
}

jintArray int_array(JNIEnv* env, const std::vector<jint>& values) {
    const auto array = env->NewIntArray(static_cast<jsize>(values.size()));
    if (array != nullptr && !values.empty()) {
        env->SetIntArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    }
    return array;
}

jbyteArray byte_array(JNIEnv* env, const std::string& bytes) {
    const auto array = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (array != nullptr && !bytes.empty()) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return array;
}

void add_action(std::vector<jint>& fields, const fq::Action& action) {
    const auto capture = action.kind == fq::ActionKind::capture;
    fields.push_back(capture ? 1 : 0);
    fields.push_back(action.from % fq::board_width);
    fields.push_back(action.from / fq::board_width);
    fields.push_back(capture ? 0 : action.to % fq::board_width);
    fields.push_back(capture ? 0 : action.to / fq::board_width);
}

jint rejection(const fq::ActionError error) noexcept {
    switch (error) {
        case fq::ActionError::none: return 0;
        case fq::ActionError::game_over: return 1;
        case fq::ActionError::capture_required: return 2;
        case fq::ActionError::no_capture_entitlement: return 3;
        case fq::ActionError::out_of_bounds: return 4;
        case fq::ActionError::wrong_source: return 5;
        case fq::ActionError::not_opponent_piece: return 5;
        case fq::ActionError::occupied_destination: return 6;
        case fq::ActionError::not_adjacent: return 7;
        case fq::ActionError::repeated_position: return 8;
        case fq::ActionError::wrong_turn: return 9;
        case fq::ActionError::resource_limit: return 10;
    }
    return 9;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_create(
    JNIEnv* env, jobject
) noexcept {
    return guarded<jlong>(env, 0, [] {
        return NativeEngineRegistry::instance().create_fangqi();
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_createFromPosition(
    JNIEnv* env, jobject, jintArray board, jint side
) noexcept {
    return guarded<jlong>(env, 0, [=] {
        if (board == nullptr || env->GetArrayLength(board) != fq::point_count ||
            (side != 0 && side != 1)) return static_cast<jlong>(0);
        std::array<jint, fq::point_count> values{};
        env->GetIntArrayRegion(board, 0, fq::point_count, values.data());
        if (env->ExceptionCheck()) return static_cast<jlong>(0);
        fq::Board position{};
        for (std::size_t i = 0; i < position.size(); ++i) {
            if (values[i] < 0 || values[i] > 2) return static_cast<jlong>(0);
            position[i] = static_cast<std::uint8_t>(values[i]);
        }
        return NativeEngineRegistry::instance().create_fangqi_from_position(
            position, static_cast<std::uint8_t>(side + 1));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_release(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    guarded_void(env, [=] {
        static_cast<void>(NativeEngineRegistry::instance().release_fangqi(handle));
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_snapshot(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jintArray>(env, nullptr, [=] {
        const auto game = require_game(handle);
        const auto& position = game->position();
        std::vector<jint> fields;
        fields.reserve(53);
        for (const auto piece : position.board) fields.push_back(piece);
        fields.push_back(position.to_play - 1);
        fields.push_back(position.captures_remaining);
        fields.push_back(static_cast<jint>(position.outcome));
        fields.push_back(game->actions().empty() ? 0 : 1);
        return int_array(env, fields);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_apply(
    JNIEnv* env, jobject, jlong handle, jint kind, jint x, jint y, jint to_x, jint to_y
) noexcept {
    return guarded<jint>(env, 9, [=] {
        const auto game = require_game(handle);
        const auto player = game->position().to_play;
        if (kind == 0) return rejection(game->move(player, x, y, to_x, to_y).error);
        if (kind == 1 && to_x == 0 && to_y == 0) {
            return rejection(game->capture(player, x, y).error);
        }
        return static_cast<jint>(9);
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_legalActions(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jintArray>(env, nullptr, [=] {
        const auto actions = require_game(handle)->legal_actions();
        std::vector<jint> fields;
        fields.reserve(actions.size() * 5);
        for (const auto& action : actions) add_action(fields, action);
        return int_array(env, fields);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_undo(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jboolean>(env, JNI_FALSE, [=] {
        return require_game(handle)->undo() ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_reset(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    guarded_void(env, [=] { *require_game(handle) = fq::Game{}; });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_serialize(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jbyteArray>(env, nullptr, [=] {
        return byte_array(env, require_game(handle)->serialize());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_restore(
    JNIEnv* env, jobject, jlong handle, jbyteArray bytes
) noexcept {
    return guarded<jboolean>(env, JNI_FALSE, [=] {
        if (bytes == nullptr) return JNI_FALSE;
        const auto length = env->GetArrayLength(bytes);
        // Reject before allocating or copying an untrusted snapshot into C++ memory.
        if (length <= 0 || static_cast<std::size_t>(length) > fq::max_snapshot_bytes) return JNI_FALSE;
        std::string saved(static_cast<std::size_t>(length), '\0');
        if (length > 0) env->GetByteArrayRegion(bytes, 0, length,
            reinterpret_cast<jbyte*>(saved.data()));
        if (env->ExceptionCheck()) return JNI_FALSE;
        fq::RestoreError error{};
        auto candidate = fq::Game::deserialize(saved, error);
        if (!candidate) return JNI_FALSE;
        *require_game(handle) = std::move(*candidate);
        return JNI_TRUE;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_games_lanzhou_1fangqi_FangqiBindings_chooseAction(
    JNIEnv* env, jobject, jlong handle, jint difficulty
) noexcept {
    return guarded<jintArray>(env, nullptr, [=] {
        if (difficulty < 0 || difficulty > 3) return int_array(env, {});
        const auto action = fq::choose_action(
            *require_game(handle), static_cast<fq::AiLevel>(difficulty));
        std::vector<jint> fields;
        if (action) add_action(fields, *action);
        return int_array(env, fields);
    });
}
