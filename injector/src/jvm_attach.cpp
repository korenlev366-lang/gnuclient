#include "jvm_attach.h"

#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <format>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <climits>
#include <errno.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace fs = std::filesystem;

namespace {

void log_msg(const std::string& msg) {
    std::fprintf(stderr, "[gnu-inject] %s\n", msg.c_str());
}

std::string socket_path(pid_t pid) {
    return std::format("/tmp/.java_pid{}", pid);
}

std::string create_attach_file(pid_t pid) {
    const auto primary = std::format("/proc/{}/cwd/.attach_pid{}", pid, pid);
    {
        std::ofstream f(primary);
        if (f)
            return primary;
    }
    const auto fallback = std::format("/tmp/.attach_pid{}", pid);
    std::ofstream f(fallback);
    return fallback;
}

bool process_exists(pid_t pid) {
    return kill(pid, 0) == 0;
}

std::optional<std::string> ensure_attach_listener(pid_t pid) {
    const auto path = socket_path(pid);
    if (fs::exists(path)) {
        log_msg(std::format("attach socket already present: {}", path));
        return std::nullopt;
    }

    const auto attach_file = create_attach_file(pid);
    log_msg(std::format("created attach file {}", attach_file));
    log_msg(std::format("sending SIGQUIT to pid {}", pid));

    if (kill(pid, SIGQUIT) != 0) {
        fs::remove(attach_file);
        return std::format("SIGQUIT failed for pid {}: {}", pid, strerror(errno));
    }

    using namespace std::chrono_literals;
    constexpr int max_attempts = 50;
    for (int i = 0; i < max_attempts; ++i) {
        std::this_thread::sleep_for(200ms);
        if (fs::exists(path)) {
            fs::remove(attach_file);
            log_msg(std::format("attach socket ready: {}", path));
            return std::nullopt;
        }
    }

    fs::remove(attach_file);
    return std::format(
        "attach socket {} did not appear after 10s (DisableAttachMechanism?)", path);
}

bool write_null_terminated(int fd, const char* str) {
    const auto len = std::strlen(str) + 1;
    return write(fd, str, len) == static_cast<ssize_t>(len);
}

constexpr int kAttachAgentStartFail = 102;

std::optional<std::string> check_agent_response(const std::string& response) {
    if (response.empty() || response[0] != '0')
        return std::format("JVM attach protocol error: {}", response);

    const auto nl = response.find('\n');
    if (nl == std::string::npos)
        return std::nullopt;

    std::string agent_line = response.substr(nl + 1);
    while (!agent_line.empty() && (agent_line.back() == '\n' || agent_line.back() == '\r'))
        agent_line.pop_back();

    const auto nl2 = agent_line.find('\n');
    if (nl2 != std::string::npos)
        agent_line = agent_line.substr(0, nl2);

    if (agent_line.empty())
        return std::nullopt;

    char* end = nullptr;
    const long code = std::strtol(agent_line.c_str(), &end, 10);
    if (end == agent_line.c_str())
        return std::nullopt;

    if (code == 0)
        return std::nullopt;
    if (code == 100)
        return "agent attach code 100: missing or invalid Agent-Class manifest (use gnu-inject --native)";
    if (code == kAttachAgentStartFail)
        return "agent attach code 102: agentmain failed";
    return std::format("agent attach code {}", code);
}

std::optional<std::string> attach_execute(pid_t pid,
                                          const std::vector<std::string>& args,
                                          std::string* out_response) {
    if (const auto err = ensure_attach_listener(pid)) {
        log_msg(*err);
        return *err;
    }

    const int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        const auto msg = std::format("socket() failed: {}", strerror(errno));
        log_msg(msg);
        return msg;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    const auto path = socket_path(pid);
    std::strncpy(addr.sun_path, path.c_str(), sizeof(addr.sun_path) - 1);

    log_msg(std::format("connecting to {}", path));
    if (connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        const auto msg = std::format("connect failed: {}", strerror(errno));
        log_msg(msg);
        close(fd);
        return msg;
    }

    const bool terminate_args =
        !args.empty() && args[0] != "load";

    bool ok = write_null_terminated(fd, "1");
    for (const auto& arg : args) {
        if (!ok)
            break;
        ok = write_null_terminated(fd, arg.c_str());
    }
    if (ok && terminate_args)
        ok = write_null_terminated(fd, "");

    if (!ok) {
        log_msg("failed to write attach protocol");
        close(fd);
        return "failed to write attach protocol";
    }

    std::string response;
    std::array<char, 256> read_buf{};
    ssize_t n = 0;
    while ((n = read(fd, read_buf.data(), read_buf.size())) > 0) {
        response.append(read_buf.data(), static_cast<size_t>(n));
    }
    close(fd);

    if (response.empty()) {
        log_msg("empty response from JVM");
        return "empty response from JVM";
    }

    log_msg(std::format("attach response: {}", response));

    if (response[0] != '0')
        return std::format("JVM attach protocol error: {}", response);

    if (out_response != nullptr)
        *out_response = response;

    return std::nullopt;
}

} // namespace

namespace gnu {

std::string default_jar_path() {
    std::array<char, PATH_MAX> buf{};
    const auto len = readlink("/proc/self/exe", buf.data(), buf.size() - 1);
    if (len < 0)
        return "lib/gnu-client.jar";

    const fs::path exe = std::string(buf.data(), static_cast<size_t>(len));
    const fs::path root = exe.parent_path().parent_path();

    const fs::path candidates[] = {
        root / "lib" / "gnu-client.jar",
        root / "install" / "lib" / "gnu-client.jar",
        exe.parent_path().parent_path().parent_path() / "install" / "lib" / "gnu-client.jar",
    };

    for (const auto& jar : candidates) {
        if (fs::exists(jar))
            return fs::canonical(jar).string();
    }
    return (root / "install" / "lib" / "gnu-client.jar").string();
}

std::string default_agent_so_path() {
    std::array<char, PATH_MAX> buf{};
    const auto len = readlink("/proc/self/exe", buf.data(), buf.size() - 1);
    if (len < 0)
        return "lib/gnu-agent.so";

    const fs::path exe = std::string(buf.data(), static_cast<size_t>(len));
    const fs::path root = exe.parent_path().parent_path();

    const fs::path candidates[] = {
        root / "lib" / "gnu-agent.so",
        root / "install" / "lib" / "gnu-agent.so",
        exe.parent_path().parent_path().parent_path() / "install" / "lib" / "gnu-agent.so",
    };

    for (const auto& so : candidates) {
        if (fs::exists(so))
            return fs::canonical(so).string();
    }
    return (root / "install" / "lib" / "gnu-agent.so").string();
}

const char* attach_result_str(AttachResult r) {
    switch (r) {
    case AttachResult::Ok:
        return "success";
    case AttachResult::ProcessNotFound:
        return "process not found";
    case AttachResult::SocketTimeout:
        return "attach socket timeout";
    case AttachResult::AttachFailed:
        return "attach failed";
    case AttachResult::AgentLoadFailed:
        return "agent load failed";
    }
    return "unknown";
}

AttachResult attach_and_load(pid_t pid, const std::string& jar_path) {
    return attach_and_load(pid, jar_path, "");
}

AttachResult attach_and_load(pid_t pid,
                             const std::string& jar_path,
                             const std::string& agent_args) {
    if (!process_exists(pid)) {
        log_msg(std::format("pid {} does not exist", pid));
        return AttachResult::ProcessNotFound;
    }

    if (!fs::exists(jar_path)) {
        log_msg(std::format("JAR not found: {}", jar_path));
        return AttachResult::AttachFailed;
    }

    std::string abs_jar = fs::absolute(jar_path).string();
    if (!agent_args.empty()) {
        abs_jar += "=" + agent_args;
        log_msg(std::format("agent args: {}", agent_args));
    }
    log_msg(std::format("target pid {}", pid));
    log_msg(std::format("jar path {}", abs_jar));

    log_msg("sending attach protocol: load instrument false");
    std::string response;
    if (const auto err = attach_execute(pid, {"load", "instrument", "false", abs_jar}, &response)) {
        if (err->find("attach socket") != std::string::npos)
            return AttachResult::SocketTimeout;
        return AttachResult::AttachFailed;
    }

    if (const auto agent_err = check_agent_response(response)) {
        log_msg(*agent_err);
        return AttachResult::AgentLoadFailed;
    }

    log_msg("agent loaded successfully");
    return AttachResult::Ok;
}

AttachResult attach_and_load_native(pid_t pid, const std::string& so_path) {
    if (!process_exists(pid)) {
        log_msg(std::format("pid {} does not exist", pid));
        return AttachResult::ProcessNotFound;
    }
    if (!fs::exists(so_path)) {
        log_msg(std::format("native agent not found: {}", so_path));
        return AttachResult::AttachFailed;
    }

    const std::string abs_so = fs::absolute(so_path).string();
    log_msg(std::format("target pid {}", pid));
    log_msg(std::format("native agent {}", abs_so));
    log_msg("sending attach protocol: load <so> true");

    // "load" command, agent = abs_so, isAbsolutePath = "true", options = "".
    std::string response;
    if (const auto err = attach_execute(pid, {"load", abs_so, "true", ""}, &response)) {
        if (err->find("attach socket") != std::string::npos)
            return AttachResult::SocketTimeout;
        return AttachResult::AttachFailed;
    }

    if (const auto agent_err = check_agent_response(response)) {
        log_msg(*agent_err);
        return AttachResult::AgentLoadFailed;
    }

    log_msg("native agent loaded successfully");
    return AttachResult::Ok;
}

} // namespace gnu
