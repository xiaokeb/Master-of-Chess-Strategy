#include "mocs/engine/native_engine_registry.hpp"
#include "../games/competition-mahjong/competition_mahjong_auto_session.hpp"
#include "jni_competition_mahjong_public_frame.hpp"

#include <jni.h>
#include <stdexcept>

namespace {
using namespace mocs::engine;

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    const auto klass = env->FindClass(type);
    if (klass) env->ThrowNew(klass, message);
}

template <typename Result, typename Operation>
Result guarded(JNIEnv* env, Result fallback, Operation&& operation) noexcept {
    try { return operation(); }
    catch (const std::invalid_argument&) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid automatic Mahjong argument");
    } catch (const std::logic_error&) {
        throw_java(env, "java/lang/IllegalStateException", "Invalid automatic Mahjong session");
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "Automatic Mahjong native operation failed");
    }
    return fallback;
}

std::shared_ptr<CompetitionMahjongAutoSession> require_session(jlong handle) {
    auto session = NativeEngineRegistry::instance().find_competition_mahjong_auto(handle);
    if (!session) throw std::logic_error("Invalid automatic Mahjong handle");
    return session;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_create(
    JNIEnv* env, jobject, jlong seed, jint dealer, jint wind, jint viewer,
    jintArray levels, jlong decision_seed) {
    return guarded(env, static_cast<jlong>(0), [&]() -> jlong {
        if (seed < 0 || decision_seed < 0 || dealer < 0 || dealer > 3 ||
            wind < 0 || wind > 3 || viewer < 0 || viewer > 3 || !levels ||
            env->GetArrayLength(levels) != 4) throw std::invalid_argument("Invalid setup");
        std::array<jint, 4> values{};
        env->GetIntArrayRegion(levels, 0, 4, values.data());
        if (env->ExceptionCheck()) return 0;
        std::array<std::uint8_t, 4> strategies{};
        for (std::size_t seat = 0; seat < strategies.size(); ++seat) {
            if (values[seat] < 0 || values[seat] > 3) throw std::invalid_argument("Invalid strategy");
            strategies[seat] = static_cast<std::uint8_t>(values[seat]);
        }
        return NativeEngineRegistry::instance().create_competition_mahjong_auto(
            seed, dealer, wind, viewer, strategies, decision_seed);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_release(
    JNIEnv*, jobject, jlong handle) {
    (void) NativeEngineRegistry::instance().release_competition_mahjong_auto(handle);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_snapshot(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        return mocs::engine::jni::competition_mahjong_public_frame(env, require_session(handle)->snapshot());
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_advance(
    JNIEnv* env, jobject, jlong handle, jint budget) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        const auto session = require_session(handle);
        if (budget < 1 || budget > 32) throw std::invalid_argument("Invalid budget");
        const auto advanced = session->advance(static_cast<std::uint32_t>(budget));
        const std::array<jint, 3> fields{1, static_cast<jint>(advanced.accepted_actions), advanced.terminal};
        auto result = env->NewIntArray(3);
        if (result) env->SetIntArrayRegion(result, 0, 3, fields.data());
        return result;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_undo(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env, JNI_FALSE, [&]() -> jboolean {
        return require_session(handle)->undo() ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_serialize(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env, static_cast<jbyteArray>(nullptr), [&]() {
        const auto data = require_session(handle)->serialize();
        auto result = env->NewByteArray(static_cast<jsize>(data.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()),
            reinterpret_cast<const jbyte*>(data.data()));
        return result;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_internal_CompetitionMahjongAutoBindings_restore(
    JNIEnv* env, jobject, jlong handle, jbyteArray input) {
    return guarded(env, JNI_FALSE, [&]() -> jboolean {
        const auto session = require_session(handle);
        if (!input) throw std::invalid_argument("Null record");
        const auto size = env->GetArrayLength(input);
        if (size < 28 || size > 1024 * 1024) return JNI_FALSE;
        std::vector<std::uint8_t> data(static_cast<std::size_t>(size));
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(data.data()));
        if (env->ExceptionCheck()) return JNI_FALSE;
        return session->restore(data) ? JNI_TRUE : JNI_FALSE;
    });
}
