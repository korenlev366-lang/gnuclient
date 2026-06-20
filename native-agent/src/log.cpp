#include "log.h"

#include <fstream>
#include <mutex>

namespace gnu {

namespace {
std::mutex g_log_mutex;
} // namespace

void log_line(const std::string& msg) {
    std::lock_guard<std::mutex> guard(g_log_mutex);
    std::ofstream out("/tmp/gnu_debug.log", std::ios::app);
    if (!out)
        return;
    out << "[GNUClient] " << msg << '\n';
    out.flush();
}

} // namespace gnu
