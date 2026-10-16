#include "mocs/engine/health_check.hpp"
#include "mocs/engine/native_engine_registry.hpp"
#include "mocs/engine/pikafish_adapter.hpp"

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using mocs::engine::ChineseChessEngine;
using mocs::engine::EngineHandle;
using mocs::engine::GoEngine;
using mocs::engine::NativeEngineRegistry;

void throw_java(
    JNIEnv* env,
    const char* class_name,
    const char* message
) noexcept {
    const auto error_class = env->FindClass(class_name);
    if (error_class != nullptr) {
        env->ThrowNew(error_class, message);
    }
}

template <typename Result, typename Operation>
Result guard_jni(
    JNIEnv* env,
    const Result fallback,
    Operation&& operation
) noexcept {
    try {
        return operation();
    } catch (...) {
        throw_java(
            env,
            "java/lang/RuntimeException",
            "Native engine operation failed"
        );
        return fallback;
    }
}

template <typename Operation>
void guard_jni_void(JNIEnv* env, Operation&& operation) noexcept {
    try {
        operation();
    } catch (...) {
        throw_java(
            env,
            "java/lang/RuntimeException",
            "Native engine operation failed"
        );
    }
}

std::shared_ptr<ChineseChessEngine> require_chinese_chess_engine(
    const jlong handle
) {
    const auto engine = NativeEngineRegistry::instance().find_chinese_chess(
        static_cast<EngineHandle>(handle)
    );
    if (!engine) {
        throw std::runtime_error("Invalid native engine handle");
    }
    return engine;
}

std::shared_ptr<GoEngine> require_go_engine(const jlong handle) {
    const auto engine = NativeEngineRegistry::instance().find_go(
        static_cast<EngineHandle>(handle));
    if (!engine) throw std::runtime_error("Invalid Go engine handle");
    return engine;
}

jintArray new_int_array(JNIEnv* env, const std::vector<jint>& values) {
    const auto result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

std::vector<jint> score_fields(const std::optional<mocs::engine::GoScore>& score) {
    if (!score) return {};
    return {score->black_area_quarters, score->white_area_quarters,
            score->black_komi_quarters, static_cast<jint>(score->result),
            score->handicap_adjustment_quarters};
}

jint encode_piece(const mocs::engine::Piece& piece) noexcept {
    constexpr std::uint8_t black_side_bit = 0x80;
    const auto side = piece.side == mocs::engine::Side::black
        ? black_side_bit
        : static_cast<std::uint8_t>(0);
    return static_cast<jint>(
        side | static_cast<std::uint8_t>(piece.type)
    );
}

std::string require_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        throw std::invalid_argument("Required Java string was null");
    }
    const auto* characters = env->GetStringUTFChars(value, nullptr);
    if (characters == nullptr) {
        throw std::runtime_error("Could not read Java string");
    }
    const std::string result(characters);
    env->ReleaseStringUTFChars(value, characters);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_healthCheck(
    JNIEnv* env,
    jobject
) noexcept {
    return guard_jni<jstring>(env, nullptr, [env] {
        const auto value = mocs::engine::health_check();
        return env->NewStringUTF(value.c_str());
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_createChineseChessEngine(
    JNIEnv* env,
    jobject
) noexcept {
    return guard_jni<jlong>(env, 0, [] {
        return static_cast<jlong>(
            NativeEngineRegistry::instance().create_chinese_chess()
        );
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_releaseChineseChessEngine(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    guard_jni_void(env, [handle] {
        // Releasing an already released handle is intentionally idempotent.
        static_cast<void>(
            NativeEngineRegistry::instance().release_chinese_chess(
                static_cast<EngineHandle>(handle)
            )
        );
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessCurrentPlayer(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    return guard_jni<jint>(env, 0, [handle] {
        return static_cast<jint>(
            require_chinese_chess_engine(handle)->current_player()
        );
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_resetChineseChess(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    guard_jni_void(env, [handle] {
        require_chinese_chess_engine(handle)->reset();
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_applyChineseChessMove(
    JNIEnv* env,
    jobject,
    const jlong handle,
    const jint from_x,
    const jint from_y,
    const jint to_x,
    const jint to_y
) noexcept {
    return guard_jni<jint>(env, 2, [=] {
        const auto result = require_chinese_chess_engine(handle)->apply(
            mocs::engine::make_board_move(from_x, from_y, to_x, to_y)
        );
        return static_cast<jint>(result.error);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_undoChineseChess(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    return guard_jni<jboolean>(env, JNI_FALSE, [handle] {
        return require_chinese_chess_engine(handle)->undo()
            ? JNI_TRUE
            : JNI_FALSE;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessLegalMoves(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    return guard_jni<jintArray>(env, nullptr, [env, handle] {
        const auto actions =
            require_chinese_chess_engine(handle)->legal_actions();
        std::vector<jint> flattened;
        flattened.reserve(actions.size() * 4);
        for (const auto& action : actions) {
            flattened.insert(
                flattened.end(),
                action.arguments.begin(),
                action.arguments.end()
            );
        }

        const auto result = env->NewIntArray(
            static_cast<jsize>(flattened.size())
        );
        if (result != nullptr && !flattened.empty()) {
            env->SetIntArrayRegion(
                result,
                0,
                static_cast<jsize>(flattened.size()),
                flattened.data()
            );
        }
        return result;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessBestMove(
    JNIEnv* env,
    jobject,
    const jlong handle,
    const jint difficulty_code,
    jstring network_path
) noexcept {
    return guard_jni<jintArray>(
        env,
        nullptr,
        [env, handle, difficulty_code, network_path] {
        if (
            difficulty_code < static_cast<jint>(mocs::engine::Difficulty::easy) ||
            difficulty_code > static_cast<jint>(mocs::engine::Difficulty::master)
        ) {
            throw std::invalid_argument("Unsupported AI difficulty");
        }
        const auto engine = require_chinese_chess_engine(handle);
        const auto difficulty =
            static_cast<mocs::engine::Difficulty>(difficulty_code);
        const auto action = mocs::engine::choose_pikafish_move(
            engine->search_position(),
            engine->legal_actions(),
            require_utf8(env, network_path),
            difficulty
        );
        const auto result = env->NewIntArray(action ? 4 : 0);
        if (result != nullptr && action) {
            std::array<jint, 4> flattened{};
            std::copy(
                action->arguments.begin(),
                action->arguments.end(),
                flattened.begin()
            );
            env->SetIntArrayRegion(result, 0, 4, flattened.data());
        }
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessGameResult(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    return guard_jni<jint>(env, 0, [handle] {
        return static_cast<jint>(
            require_chinese_chess_engine(handle)->game_result()
        );
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_serializeChineseChess(
    JNIEnv* env,
    jobject,
    const jlong handle
) noexcept {
    return guard_jni<jbyteArray>(env, nullptr, [env, handle] {
        const auto data = require_chinese_chess_engine(handle)->serialize();
        const auto result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result != nullptr && !data.empty()) {
            env->SetByteArrayRegion(
                result,
                0,
                static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data())
            );
        }
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_restoreChineseChess(
    JNIEnv* env,
    jobject,
    const jlong handle,
    jbyteArray source
) noexcept {
    return guard_jni<jint>(env, 3, [env, handle, source] {
        if (source == nullptr) {
            return static_cast<jint>(mocs::engine::EngineError::corrupted_data);
        }

        const auto size = env->GetArrayLength(source);
        std::vector<std::uint8_t> data(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(
            source,
            0,
            size,
            reinterpret_cast<jbyte*>(data.data())
        );
        if (env->ExceptionCheck()) {
            return static_cast<jint>(mocs::engine::EngineError::corrupted_data);
        }

        const auto result =
            require_chinese_chess_engine(handle)->restore(data);
        return static_cast<jint>(result.error);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessPieceAt(
    JNIEnv* env,
    jobject,
    const jlong handle,
    const jint x,
    const jint y
) noexcept {
    return guard_jni<jint>(env, 0, [=] {
        const auto piece =
            require_chinese_chess_engine(handle)->piece_at(x, y);
        return piece ? encode_piece(*piece) : 0;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessIsInCheck(
    JNIEnv* env,
    jobject,
    const jlong handle,
    const jint side_code
) noexcept {
    return guard_jni<jboolean>(env, JNI_FALSE, [=] {
        if (side_code != 0 && side_code != 1) {
            throw std::invalid_argument("Invalid Chinese chess side");
        }
        const auto side = side_code == 0
            ? mocs::engine::Side::red
            : mocs::engine::Side::black;
        return require_chinese_chess_engine(handle)->is_in_check(side)
            ? JNI_TRUE
            : JNI_FALSE;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_chineseChessNaturalLimitReview(
    JNIEnv* env, jobject, const jlong handle, const jint side_code
) noexcept {
    return guard_jni<jintArray>(env, nullptr, [=] {
        if (side_code != 0 && side_code != 1) {
            throw std::invalid_argument("Invalid Chinese chess side");
        }
        const auto side = side_code == 0 ? mocs::engine::Side::red : mocs::engine::Side::black;
        const auto review = require_chinese_chess_engine(handle)->natural_limit_review(side);
        const std::array<jint, 6> fields{
            review.no_capture_plies, review.recorded_plies, review.claimant_checks,
            review.effective_plies, review.complete_record ? 1 : 0, review.eligible ? 1 : 0};
        const auto result = env->NewIntArray(static_cast<jsize>(fields.size()));
        if (result != nullptr) env->SetIntArrayRegion(result, 0, static_cast<jsize>(fields.size()), fields.data());
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_claimChineseChessNaturalLimit(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jint>(env, 3, [=] {
        return static_cast<jint>(require_chinese_chess_engine(handle)->claim_natural_limit());
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_createGoEngine(
    JNIEnv* env, jobject, const jint size, const jint komi, const jint handicap
) noexcept {
    return guard_jni<jlong>(env, 0, [=] {
        if (size != 9 && size != 13 && size != 19) {
            throw std::invalid_argument("Invalid Go board size");
        }
        if (handicap < 0 || handicap > 9) {
            throw std::invalid_argument("Invalid Go handicap");
        }
        return static_cast<jlong>(NativeEngineRegistry::instance().create_go(
            static_cast<std::uint8_t>(size), komi, static_cast<std::uint8_t>(handicap)));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_releaseGoEngine(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    guard_jni_void(env, [=] {
        static_cast<void>(NativeEngineRegistry::instance().release_go(
            static_cast<EngineHandle>(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goCurrentPlayer(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jint>(env, -1, [=] {
        return static_cast<jint>(require_go_engine(handle)->current_player());
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goKomiQuarters(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jint>(env, -1, [=] {
        return require_go_engine(handle)->black_komi_quarters();
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goHandicapStones(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jint>(env, -1, [=] {
        return require_go_engine(handle)->handicap_stones();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_resetGo(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    guard_jni_void(env, [=] { require_go_engine(handle)->reset(); });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_applyGoAction(
    JNIEnv* env, jobject, const jlong handle, const jint kind, const jint a,
    const jint b, const jint c, const jint d
) noexcept {
    return guard_jni<jint>(env, 2, [=] {
        if (kind < mocs::engine::go_place_action_kind ||
            kind > mocs::engine::go_resume_play_action_kind) {
            return static_cast<jint>(mocs::engine::EngineError::illegal_action);
        }
        const mocs::engine::EngineAction action{
            static_cast<std::uint16_t>(kind), {a, b, c, d}};
        return static_cast<jint>(require_go_engine(handle)->apply(action).error);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_undoGo(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jboolean>(env, JNI_FALSE, [=] {
        return require_go_engine(handle)->undo() ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goLegalActions(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jintArray>(env, nullptr, [=] {
        const auto actions = require_go_engine(handle)->legal_actions();
        std::vector<jint> fields;
        fields.reserve(actions.size() * 5);
        for (const auto& action : actions) {
            fields.push_back(static_cast<jint>(action.kind));
            fields.insert(fields.end(), action.arguments.begin(), action.arguments.end());
        }
        return new_int_array(env, fields);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goGameResult(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jint>(env, -1, [=] {
        return static_cast<jint>(require_go_engine(handle)->game_result());
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_serializeGo(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jbyteArray>(env, nullptr, [=] {
        const auto data = require_go_engine(handle)->serialize();
        const auto result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result != nullptr && !data.empty()) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
                reinterpret_cast<const jbyte*>(data.data()));
        }
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_restoreGo(
    JNIEnv* env, jobject, const jlong handle, jbyteArray source
) noexcept {
    return guard_jni<jint>(env, 3, [=] {
        if (source == nullptr) return static_cast<jint>(mocs::engine::EngineError::corrupted_data);
        const auto size = env->GetArrayLength(source);
        std::vector<std::uint8_t> data(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(source, 0, size, reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return static_cast<jint>(mocs::engine::EngineError::corrupted_data);
        return static_cast<jint>(require_go_engine(handle)->restore(data).error);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goStoneAt(
    JNIEnv* env, jobject, const jlong handle, const jint x, const jint y
) noexcept {
    return guard_jni<jint>(env, -1, [=] {
        const auto stone = require_go_engine(handle)->stone_at(x, y);
        return stone ? static_cast<jint>(*stone) : -1;
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goScoringState(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jintArray>(env, nullptr, [=] {
        const auto state = require_go_engine(handle)->scoring_state();
        std::vector<jint> fields{
            static_cast<jint>(state.phase), static_cast<jint>(state.proposal_revision),
            state.black_confirmed ? 1 : 0, state.white_confirmed ? 1 : 0,
            static_cast<jint>(state.dead_stones.size())};
        fields.reserve(5 + state.dead_stones.size() * 2);
        for (const auto point : state.dead_stones) {
            fields.push_back(point.x);
            fields.push_back(point.y);
        }
        return new_int_array(env, fields);
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goFinalScore(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jintArray>(env, nullptr, [=] {
        return new_int_array(env, score_fields(require_go_engine(handle)->final_score()));
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goPreviewScore(
    JNIEnv* env, jobject, const jlong handle
) noexcept {
    return guard_jni<jintArray>(env, nullptr, [=] {
        const auto engine = require_go_engine(handle);
        const auto dead = engine->scoring_state().dead_stones;
        return new_int_array(env, score_fields(engine->preview_area_score(
            dead, engine->black_komi_quarters())));
    });
}
