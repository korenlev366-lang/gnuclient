#pragma once

#include <string>

namespace gnu {

// Append a line to /tmp/gnu_debug.log. Caller supplies the phase prefix
// (e.g. "NATIVE_", "JNI_", "GUI_"). A "[GNUClient] " marker is prepended.
void log_line(const std::string& msg);

} // namespace gnu
