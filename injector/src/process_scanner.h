#pragma once

#include <string>
#include <vector>

#include <unistd.h>

namespace gnu {

struct McProcess {
    pid_t pid;
    std::string cmdline_summary;
};

/** Scan /proc for Minecraft 1.8.9 Forge JVM processes. */
std::vector<McProcess> scan_mc_processes();

} // namespace gnu
