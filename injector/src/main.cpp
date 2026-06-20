#include "jvm_attach.h"
#include "process_scanner.h"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

namespace {

struct CliArgs {
    pid_t pid = 0;
    std::string jar_path;
    bool jar_given = false;
    bool reload = false;
    /** Default native JVMTI attach; gnu-client.jar has no Java Agent-Class manifest. */
    bool native = true;
    std::string agent_so;
    bool agent_so_given = false;
};

void usage(const char* argv0) {
    std::fprintf(stderr,
                 "Usage: %s [--pid PID] [--native] [--agent-so PATH]\n"
                 "       %s --jar [--pid PID] [--jar PATH] [--reload]\n"
                 "  Default (native mode): load gnu-agent.so as a JVMTI agent (Agent_OnAttach).\n"
                 "    Native agent addURL-loads gnu-client.jar onto LaunchClassLoader.\n"
                 "    --agent-so overrides the default install/lib/gnu-agent.so path.\n"
                 "  --jar: legacy instrument-agent load (requires Agent-Class manifest).\n"
                 "    Re-injecting the same JVM reloads the client (no restart).\n"
                 "    --reload passes agentmain args=reload before loading the JAR.\n"
                 "    --jar cannot be combined with --native.\n",
                 argv0, argv0);
}

CliArgs parse_args(int argc, char** argv) {
    CliArgs args;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--pid" && i + 1 < argc) {
            args.pid = static_cast<pid_t>(std::atoi(argv[++i]));
        } else if (arg == "--jar" && i + 1 < argc) {
            args.jar_path = argv[++i];
            args.jar_given = true;
        } else if (arg == "--reload") {
            args.reload = true;
        } else if (arg == "--jar") {
            args.native = false;
        } else if (arg == "--native") {
            args.native = true;
        } else if (arg == "--agent-so" && i + 1 < argc) {
            args.agent_so = argv[++i];
            args.agent_so_given = true;
        } else if (arg == "--help" || arg == "-h") {
            usage(argv[0]);
            std::exit(1);
        }
    }
    return args;
}

pid_t resolve_target(pid_t requested);

int run_native_mode(const CliArgs& args) {
    if (args.reload) {
        std::fprintf(stderr, "[gnu-inject] error: --native cannot be combined with --reload\n");
        return 1;
    }
    std::string so_path = args.agent_so_given ? args.agent_so : gnu::default_agent_so_path();
    if (!args.agent_so_given)
        std::fprintf(stderr, "[gnu-inject] default agent: %s\n", so_path.c_str());
    if (!std::filesystem::exists(so_path)) {
        std::fprintf(stderr, "[gnu-inject] error: native agent not found at %s\n", so_path.c_str());
        return 1;
    }

    pid_t target = resolve_target(args.pid);
    if (target == 0)
        return 1;

    std::fprintf(stderr, "[gnu-inject] injecting native agent into pid %d\n", target);
    const gnu::AttachResult result = gnu::attach_and_load_native(target, so_path);
    if (result == gnu::AttachResult::Ok) {
        std::printf("gnu-inject: native agent loaded (pid %d)\n", target);
        return 0;
    }
    std::fprintf(stderr, "[gnu-inject] failed: %s\n", gnu::attach_result_str(result));
    return result == gnu::AttachResult::AgentLoadFailed ? 3 : 2;
}

pid_t prompt_select_process(const std::vector<gnu::McProcess>& procs) {
    std::printf("\nMinecraft JVM processes:\n");
    for (size_t i = 0; i < procs.size(); ++i) {
        std::printf("  [%zu] pid=%d  %s\n", i + 1, procs[i].pid, procs[i].cmdline_summary.c_str());
    }
    std::printf("\nSelect number (1-%zu): ", procs.size());
    std::fflush(stdout);

    size_t choice = 0;
    if (std::scanf("%zu", &choice) != 1 || choice < 1 || choice > procs.size())
        return 0;
    return procs[choice - 1].pid;
}

int exit_code_for(gnu::AttachResult r) {
    switch (r) {
    case gnu::AttachResult::Ok:
        return 0;
    case gnu::AttachResult::ProcessNotFound:
    case gnu::AttachResult::SocketTimeout:
    case gnu::AttachResult::AttachFailed:
        return 2;
    case gnu::AttachResult::AgentLoadFailed:
        return 3;
    }
    return 2;
}

} // namespace

namespace {

pid_t resolve_target(pid_t requested) {
    if (requested != 0)
        return requested;
    const auto procs = gnu::scan_mc_processes();
    if (procs.empty()) {
        std::fprintf(stderr, "[gnu-inject] No MC JVM found\n");
        return 0;
    }
    if (procs.size() == 1) {
        std::fprintf(stderr, "[gnu-inject] auto-selected pid %d\n", procs.front().pid);
        std::fprintf(stderr, "[gnu-inject]   %s\n", procs.front().cmdline_summary.c_str());
        return procs.front().pid;
    }
    pid_t target = prompt_select_process(procs);
    if (target == 0)
        std::fprintf(stderr, "[gnu-inject] invalid selection\n");
    return target;
}

} // namespace

int run_jar_mode(const CliArgs& args);

int main(int argc, char** argv) {
    const CliArgs args = parse_args(argc, argv);

    if (args.native) {
        if (args.reload) {
            std::fprintf(stderr, "[gnu-inject] error: --reload requires --jar (legacy instrument mode)\n");
            return 1;
        }
        return run_native_mode(args);
    }

    return run_jar_mode(args);
}

int run_jar_mode(const CliArgs& args) {
    std::string jar_path = args.jar_given ? args.jar_path : gnu::default_jar_path();
    if (!args.jar_given) {
        std::fprintf(stderr, "[gnu-inject] default jar: %s\n", jar_path.c_str());
    }

    if (!std::filesystem::exists(jar_path)) {
        std::fprintf(stderr, "[gnu-inject] error: JAR not found at %s\n", jar_path.c_str());
        return 1;
    }

    pid_t target = resolve_target(args.pid);
    if (target == 0)
        return 1;

    std::fprintf(stderr, "[gnu-inject] injecting pid %d\n", target);

    const std::string agent_args = args.reload ? "reload" : "";
    if (args.reload) {
        std::fprintf(stderr, "[gnu-inject] reload: agentmain args=reload\n");
    }

    const gnu::AttachResult result = gnu::attach_and_load(target, jar_path, agent_args);

    if (result == gnu::AttachResult::Ok) {
        std::printf("gnu-inject: success (pid %d)\n", target);
        return 0;
    }

    std::fprintf(stderr, "[gnu-inject] failed: %s\n", gnu::attach_result_str(result));
    if (result == gnu::AttachResult::AgentLoadFailed) {
        std::fprintf(stderr,
                     "[gnu-inject] hint: gnu-client.jar has no Agent-Class manifest; "
                     "use default native mode (omit --jar) or rebuild with --native\n");
    }
    return exit_code_for(result);
}
