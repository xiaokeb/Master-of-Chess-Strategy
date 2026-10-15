#include "mocs/engine/native_engine_registry.hpp"
#include "mocs/engine/tiger_goat_wire.hpp"

#include <jni.h>
#include <cstdint>
#include <memory>
#include <new>
#include <stdexcept>
#include <vector>

namespace {
namespace tg = mocs::engine::tiger_goat;
using mocs::engine::NativeEngineRegistry;

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    if (env->ExceptionCheck()) return; // Preserve allocation/copy failures from the VM.
    const auto klass = env->FindClass(type);
    if (klass != nullptr) env->ThrowNew(klass, message);
}

template<class Result, class Operation>
Result guarded(JNIEnv* env, Result fallback, Operation operation) noexcept {
    if (env->ExceptionCheck()) return fallback;
    try { return operation(); }
    catch (const std::bad_alloc&) {
        throw_java(env, "java/lang/OutOfMemoryError", "Tiger-goat allocation failed");
    } catch (const std::logic_error&) {
        throw_java(env, "java/lang/IllegalStateException", "Invalid tiger-goat session handle");
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "Tiger-goat native operation failed");
    }
    return fallback;
}

std::shared_ptr<mocs::engine::TigerGoatHandle> session(jlong handle) {
    auto owned = NativeEngineRegistry::instance().find_tiger_goat(handle);
    if (!owned) throw std::logic_error("Unknown tiger-goat session");
    return owned;
}

jintArray int_array(JNIEnv* env, const std::vector<int>& values) {
    static_assert(sizeof(int) == sizeof(jint));
    const auto out = env->NewIntArray(static_cast<jsize>(values.size()));
    if (out != nullptr && !values.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
    }
    return out;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_create(
    JNIEnv* env, jobject
) noexcept {
    return guarded(env, jlong{0}, [] { return NativeEngineRegistry::instance().create_tiger_goat(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_release(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    static_cast<void>(guarded(env, false, [=] {
        return NativeEngineRegistry::instance().release_tiger_goat(handle);
    }));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_geometry(
    JNIEnv* env, jobject
) noexcept {
    return guarded<jintArray>(env, nullptr, [=] { return int_array(env, tg::wire_geometry()); });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_snapshot(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jintArray>(env, nullptr, [=] {
        const auto owned = session(handle);
        std::lock_guard lock(owned->mutex);
        return int_array(env, tg::wire_snapshot(owned->game));
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_legalActions(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jintArray>(env, nullptr, [=] {
        const auto owned = session(handle);
        std::lock_guard lock(owned->mutex);
        return int_array(env, tg::wire_legal_actions(owned->game));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_apply(
    JNIEnv* env, jobject, jlong handle, jint kind, jint from, jint to
) noexcept {
    return guarded(env, static_cast<jint>(tg::ApplyError::wrong_phase), [=] {
        const auto owned = session(handle);
        std::lock_guard lock(owned->mutex);
        // Check Java integers before narrowing the enum: 256 must not become action 0.
        if (kind < 0 || kind > static_cast<jint>(tg::ActionKind::pass_goat)) {
            return static_cast<jint>(tg::ApplyError::wrong_phase);
        }
        if (from < -1 || from >= static_cast<jint>(tg::official_point_count)
            || to < -1 || to >= static_cast<jint>(tg::official_point_count)) {
            return static_cast<jint>(tg::ApplyError::invalid_point);
        }
        return static_cast<jint>(owned->game.apply({static_cast<tg::ActionKind>(kind), from, to}));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_undo(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded(env, JNI_FALSE, [=] {
        const auto owned = session(handle);
        std::lock_guard lock(owned->mutex);
        return owned->game.undo() ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_reset(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    static_cast<void>(guarded(env, false, [=] {
        const auto owned = session(handle);
        std::lock_guard lock(owned->mutex);
        owned->game.reset();
        return true;
    }));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_serialize(
    JNIEnv* env, jobject, jlong handle
) noexcept {
    return guarded<jbyteArray>(env, nullptr, [=] {
        const auto owned = session(handle);
        std::lock_guard lock(owned->mutex);
        const auto bytes = owned->game.serialize();
        const auto out = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()),
                                    reinterpret_cast<const jbyte*>(bytes.data()));
        }
        return out;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_masterofchessstrategy_engine_games_tiger_1goat_TigerGoatBindings_restore(
    JNIEnv* env, jobject, jlong handle, jbyteArray input
) noexcept {
    return guarded(env, JNI_FALSE, [=] {
        const auto owned = session(handle);
        if (input == nullptr) return JNI_FALSE;
        const auto length = env->GetArrayLength(input);
        if (length < 53 || length > 196658) return JNI_FALSE;
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return JNI_FALSE;
        std::lock_guard lock(owned->mutex);
        // Native restore validates every replay action and commits only on success.
        return owned->game.restore(bytes) ? JNI_TRUE : JNI_FALSE;
    });
}
