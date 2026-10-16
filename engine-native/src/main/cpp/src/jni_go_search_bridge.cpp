#include "mocs/engine/go_search_handle.hpp"
#include "mocs/engine/native_engine_registry.hpp"
#include <jni.h>
#include <array>
#include <stdexcept>

namespace {
using namespace mocs::engine;
using namespace mocs::engine::go_ai;

template<class Result, class Operation>
Result guarded(JNIEnv* env, Result fallback, Operation operation) noexcept {
    try { return operation(); }
    catch (const std::invalid_argument&) {
        const auto klass = env->FindClass("java/lang/IllegalArgumentException");
        if (klass) env->ThrowNew(klass, "Invalid Go search argument or model");
    } catch (const std::logic_error&) {
        const auto klass = env->FindClass("java/lang/IllegalStateException");
        if (klass) env->ThrowNew(klass, "Go search handle or lifecycle is invalid");
    } catch (...) {
        const auto klass = env->FindClass("java/lang/RuntimeException");
        if (klass) env->ThrowNew(klass, "Go search native operation failed");
    }
    return fallback;
}

std::shared_ptr<KataGoRuntime> runtime(jlong handle) {
    auto value = NativeEngineRegistry::instance().find_go_search_runtime(handle);
    if (!value) throw std::logic_error("Unknown Go runtime");
    return value;
}
std::shared_ptr<GoSearchRequestHandle> request(jlong handle) {
    auto value = NativeEngineRegistry::instance().find_go_search_request(handle);
    if (!value) throw std::logic_error("Unknown Go request");
    return value;
}

// JNI's modified UTF8 is not a filesystem encoding; convert checked UTF16 to ordinary UTF8.
std::string model_path(JNIEnv* env, jstring text) {
    if (!text) throw std::invalid_argument("Missing path");
    const auto length = env->GetStringLength(text);
    if (length < 1 || length > 4096) throw std::invalid_argument("Path length");
    std::vector<jchar> chars(static_cast<std::size_t>(length));
    env->GetStringRegion(text, 0, length, chars.data());
    if (env->ExceptionCheck()) throw std::logic_error("Path copy failed");
    std::string out;
    for (std::size_t index = 0; index < chars.size(); ++index) {
        std::uint32_t point = chars[index];
        if (point == 0) throw std::invalid_argument("Path NUL");
        if (point >= 0xd800 && point <= 0xdbff) {
            if (++index >= chars.size() || chars[index] < 0xdc00 || chars[index] > 0xdfff)
                throw std::invalid_argument("Invalid Unicode path");
            point = 0x10000 + ((point - 0xd800) << 10) + chars[index] - 0xdc00;
        } else if (point >= 0xdc00 && point <= 0xdfff) throw std::invalid_argument("Invalid Unicode path");
        if (point < 0x80) out.push_back(static_cast<char>(point));
        else if (point < 0x800) {
            out.push_back(static_cast<char>(0xc0 | (point >> 6)));
            out.push_back(static_cast<char>(0x80 | (point & 0x3f)));
        } else {
            if (point >= 0x10000) out.push_back(static_cast<char>(0xf0 | (point >> 18)));
            out.push_back(static_cast<char>((point >= 0x10000 ? 0x80 : 0xe0) | ((point >> 12) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | ((point >> 6) & 0x3f)));
            out.push_back(static_cast<char>(0x80 | (point & 0x3f)));
        }
    }
    return out;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_create(JNIEnv* env, jobject, jstring path) {
    return guarded(env, jlong{0}, [&] {
        return NativeEngineRegistry::instance().register_go_search_runtime(
            std::make_shared<KataGoRuntime>(model_path(env, path)));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_prepare(
    JNIEnv* env, jobject, jlong handle, jbyteArray snapshot, jint visits, jlong time) {
    return guarded(env, jlong{0}, [&] {
        const auto owned = runtime(handle);
        if (!snapshot) throw std::invalid_argument("Missing Go record");
        const auto length = env->GetArrayLength(snapshot);
        if (length < 1 || length > 1'000'000) throw std::invalid_argument("Go record length");
        std::vector<std::uint8_t> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(snapshot, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return EngineHandle{0};
        auto prepared = owned->prepare(bytes, {visits, std::chrono::milliseconds(time)});
        return NativeEngineRegistry::instance().register_go_search_request(
            std::make_shared<GoSearchRequestHandle>(std::move(prepared)));
    });
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_run(JNIEnv* env, jobject, jlong handle) {
    return guarded(env, static_cast<jdoubleArray>(nullptr), [&] {
        const auto result = request(handle)->run();
        std::array<jdouble, 9> frame{1, 0, 0, -1, -1, 0, 0, 0, 0};
        switch (result.status) {
            case GoSearchStatus::cancelled: frame[1] = 1; break;
            case GoSearchStatus::busy: frame[1] = 2; break;
            case GoSearchStatus::budget_exhausted: frame[1] = 3; break;
            case GoSearchStatus::move:
                if (!result.action || (result.action->kind != go_place_action_kind &&
                    result.action->kind != go_pass_action_kind)) throw std::logic_error("Invalid search candidate");
                frame[2] = result.action->kind;
                if (result.action->kind == go_place_action_kind) {
                    frame[3] = result.action->arguments[0]; frame[4] = result.action->arguments[1];
                }
                frame[5] = static_cast<double>(result.visits); frame[6] = result.elapsed_ms;
                frame[7] = result.white_win_probability; frame[8] = result.white_minus_black_points;
                break;
        }
        auto output = env->NewDoubleArray(frame.size());
        if (output) env->SetDoubleArrayRegion(output, 0, frame.size(), frame.data());
        return output;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_cancel(JNIEnv* env, jobject, jlong handle) {
    (void)guarded(env, false, [&] { request(handle)->cancel(); return true; });
}
extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_cancelAll(JNIEnv* env, jobject, jlong handle) {
    (void)guarded(env, false, [&] { runtime(handle)->cancel_all(); return true; });
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_processedRows(JNIEnv* env, jobject, jlong handle) {
    return guarded(env, jlong{0}, [&]() -> jlong { return static_cast<jlong>(runtime(handle)->processed_rows()); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_closeRuntime(JNIEnv* env, jobject, jlong handle) {
    (void)guarded(env, false, [&] { return NativeEngineRegistry::instance().release_go_search_runtime(handle); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_masterofchessstrategy_engine_internal_GoSearchBindings_closeRequest(JNIEnv* env, jobject, jlong handle) {
    (void)guarded(env, false, [&] { return NativeEngineRegistry::instance().release_go_search_request(handle); });
}
