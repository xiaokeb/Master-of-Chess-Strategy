#include "mocs/engine/health_check.hpp"

#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_masterofchessstrategy_engine_internal_NativeBindings_healthCheck(
    JNIEnv* env,
    jobject
) noexcept {
    try {
        const auto value = mocs::engine::health_check();
        return env->NewStringUTF(value.c_str());
    } catch (...) {
        // No C++ exception may cross the JNI boundary.
        const auto error_class = env->FindClass("java/lang/RuntimeException");
        if (error_class != nullptr) {
            env->ThrowNew(error_class, "Native engine health check failed");
        }
        return nullptr;
    }
}
