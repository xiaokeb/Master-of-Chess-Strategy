#pragma once

#include <string>

namespace mocs::engine {

// Returns the native protocol identity used by the first JNI integration check.
[[nodiscard]] std::string health_check();

}  // namespace mocs::engine
