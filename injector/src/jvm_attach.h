#pragma once

#include <string>

#include <unistd.h>

namespace gnu {

enum class AttachResult {
    Ok,
    ProcessNotFound,
    SocketTimeout,
    AttachFailed,
    AgentLoadFailed,
};

/** HotSpot attach: load gnu-client.jar via instrument agent. */
AttachResult attach_and_load(pid_t pid, const std::string& jar_path);

/** Same as attach_and_load; {@code agent_args} is passed to agentmain (e.g. "reload"). */
AttachResult attach_and_load(pid_t pid,
                             const std::string& jar_path,
                             const std::string& agent_args);

/** HotSpot attach: load a native JVMTI agent (.so) via absolute path
 *  ("load <abs_so> true ""). Triggers Agent_OnAttach in gnu-agent.so. */
AttachResult attach_and_load_native(pid_t pid, const std::string& so_path);

const char* attach_result_str(AttachResult r);

/** Resolve ../lib/gnu-client.jar relative to gnu-inject executable. */
std::string default_jar_path();

/** Resolve ../lib/gnu-agent.so relative to gnu-inject executable. */
std::string default_agent_so_path();

} // namespace gnu
