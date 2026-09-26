#include "mocs/engine/health_check.hpp"
#include "mocs/engine/native_engine_registry.hpp"
#include "mocs/engine/pikafish_adapter.hpp"

#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using mocs::engine::ChineseChessEngine;
using mocs::engine::EngineHandle;
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
            NativeEngineRegistry::instance().release(
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
            engine->fen(),
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
