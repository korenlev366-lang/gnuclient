#include "process_scanner.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <format>
#include <string>

namespace fs = std::filesystem;

namespace {

bool is_pid_dir(const fs::directory_entry& entry) {
    if (!entry.is_directory())
        return false;
    const auto name = entry.path().filename().string();
    return !name.empty() && std::ranges::all_of(name, ::isdigit);
}

std::string read_file_contents(const fs::path& path) {
    std::ifstream file(path, std::ios::binary);
    if (!file)
        return {};
    return {std::istreambuf_iterator<char>(file), {}};
}

std::string read_cmdline(pid_t pid) {
    auto raw = read_file_contents(std::format("/proc/{}/cmdline", pid));
    std::ranges::replace(raw, '\0', ' ');
    if (!raw.empty() && raw.back() == ' ')
        raw.pop_back();
    return raw;
}

bool has_jvm_loaded(pid_t pid) {
    std::ifstream maps(std::format("/proc/{}/maps", pid));
    if (!maps)
        return false;
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find("libjvm.so") != std::string::npos)
            return true;
    }
    return false;
}

bool contains(const std::string& haystack, const char* needle) {
    return haystack.find(needle) != std::string::npos;
}

bool is_forge_cmdline(const std::string& cmdline) {
    const bool mc_marker =
        contains(cmdline, "launchwrapper") || contains(cmdline, "minecraft-1.8.9");
    const bool java_marker = contains(cmdline, "jre-legacy") || contains(cmdline, "java");
    const bool forge_marker = contains(cmdline, "net.minecraftforge") ||
                              contains(cmdline, "--tweakClass") ||
                              contains(cmdline, "minecraftforge") ||
                              contains(cmdline, "cpw.mods.fml");
    return mc_marker && java_marker && forge_marker;
}

bool is_lunar_cmdline(const std::string& cmdline) {
    const bool lunar_marker = contains(cmdline, "lunarclient") ||
                              contains(cmdline, ".lunarclient") ||
                              contains(cmdline, "com.moonsworth.lunar") ||
                              contains(cmdline, "com/lunarclient") ||
                              contains(cmdline, "LunarClient");
    const bool badlion_marker = contains(cmdline, "badlion");
    const bool mc_marker = contains(cmdline, "minecraft") || contains(cmdline, "1.8.9")
                           || contains(cmdline, "1.8");
    const bool java_marker = contains(cmdline, "java") || contains(cmdline, "jre");
    const bool not_forge = !contains(cmdline, "net.minecraftforge")
                           && !contains(cmdline, "minecraftforge")
                           && !contains(cmdline, "launchwrapper");
    return (lunar_marker || badlion_marker) && mc_marker && java_marker && not_forge;
}

bool is_vanilla_mc_cmdline(const std::string& cmdline) {
    const bool mc_marker = contains(cmdline, "minecraft-1.8.9") || contains(cmdline, "1.8.9");
    const bool java_marker = contains(cmdline, "java") || contains(cmdline, "jre");
    const bool not_forge = !contains(cmdline, "net.minecraftforge")
                           && !contains(cmdline, "minecraftforge")
                           && !contains(cmdline, "launchwrapper");
    return mc_marker && java_marker && not_forge;
}

bool is_mc_jvm_cmdline(const std::string& cmdline) {
    if (is_forge_cmdline(cmdline))
        return true;
    if (is_lunar_cmdline(cmdline))
        return true;
    if (is_vanilla_mc_cmdline(cmdline))
        return true;
    return false;
}

std::string summarize_cmdline(const std::string& cmdline, size_t max_len = 120) {
    if (cmdline.size() <= max_len)
        return cmdline;
    return cmdline.substr(0, max_len) + "...";
}

} // namespace

namespace gnu {

std::vector<McProcess> scan_mc_processes() {
    std::vector<McProcess> results;

    std::error_code ec;
    for (const auto& entry : fs::directory_iterator("/proc", ec)) {
        if (ec)
            break;
        if (!is_pid_dir(entry))
            continue;

        const pid_t pid = static_cast<pid_t>(std::stoi(entry.path().filename().string()));
        if (!has_jvm_loaded(pid))
            continue;

        const std::string cmdline = read_cmdline(pid);
        if (!is_mc_jvm_cmdline(cmdline))
            continue;

        results.push_back(McProcess{
            .pid = pid,
            .cmdline_summary = summarize_cmdline(cmdline),
        });
    }

    return results;
}

} // namespace gnu
