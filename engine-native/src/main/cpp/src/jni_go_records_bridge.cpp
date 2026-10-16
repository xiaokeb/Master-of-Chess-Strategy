#include "mocs/engine/go_sgf.hpp"
#include "mocs/engine/native_engine_registry.hpp"

#include <jni.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using namespace mocs::engine;
constexpr std::size_t max_sgf_bytes = 1'000'000;
constexpr std::size_t max_events = 16'386;

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    const auto klass = env->FindClass(type);
    if (klass) env->ThrowNew(klass, message);
}

template <typename Result, typename Operation>
Result guarded(JNIEnv* env, Result fallback, Operation&& operation) noexcept {
    try { return operation(); }
    catch (const std::logic_error&) {
        throw_java(env, "java/lang/IllegalStateException", "Invalid Go record session");
    } catch (...) {
        throw_java(env, "java/lang/RuntimeException", "Go record operation failed");
    }
    return fallback;
}

std::shared_ptr<GoEngine> require_session(jlong handle) {
    auto session = NativeEngineRegistry::instance().find_go(handle);
    if (!session) throw std::logic_error("Invalid Go handle");
    return session;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_exportGoSgf(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env, static_cast<jbyteArray>(nullptr), [&]() {
        const auto text = export_go_sgf(*require_session(handle));
        if (text.size() > max_sgf_bytes) throw std::length_error("Oversized SGF");
        const auto result = env->NewByteArray(static_cast<jsize>(text.size()));
        if (result && !text.empty()) env->SetByteArrayRegion(
            result, 0, static_cast<jsize>(text.size()),
            reinterpret_cast<const jbyte*>(text.data()));
        return result;
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_restoreGoSgf(
    JNIEnv* env, jobject, jlong handle, jbyteArray source) {
    return guarded(env, static_cast<jint>(EngineError::corrupted_data), [&]() -> jint {
        const auto session = require_session(handle);
        if (!source) return static_cast<jint>(EngineError::corrupted_data);
        const auto length = env->GetArrayLength(source);
        if (length <= 0 || static_cast<std::size_t>(length) > max_sgf_bytes)
            return static_cast<jint>(EngineError::corrupted_data);
        std::string text(static_cast<std::size_t>(length), '\0');
        env->GetByteArrayRegion(source, 0, length, reinterpret_cast<jbyte*>(text.data()));
        if (env->ExceptionCheck()) return static_cast<jint>(EngineError::corrupted_data);
        return static_cast<jint>(restore_go_sgf(*session, text).error);
    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_goEventHistory(
    JNIEnv* env, jobject, jlong handle) {
    return guarded(env, static_cast<jintArray>(nullptr), [&]() {
        const auto events = require_session(handle)->event_history();
        if (events.size() > max_events) throw std::length_error("Oversized Go history");
        std::vector<jint> fields{1, static_cast<jint>(events.size())};
        fields.reserve(2 + events.size() * 5);
        for (const auto& event : events) {
            fields.push_back(event.kind);
            for (const auto argument : event.arguments) fields.push_back(argument);
        }
        const auto result = env->NewIntArray(static_cast<jsize>(fields.size()));
        if (result) env->SetIntArrayRegion(
            result, 0, static_cast<jsize>(fields.size()), fields.data());
        return result;
    });
}
