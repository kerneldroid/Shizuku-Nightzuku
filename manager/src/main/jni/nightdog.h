#ifndef NIGHTDOG_H
#define NIGHTDOG_H

#include <sys/types.h>
#include <cstdint>

namespace nightdog {

    constexpr int EXIT_FATAL_SET_CLASSPATH = 3;
    constexpr int EXIT_FATAL_FORK = 4;
    constexpr int EXIT_FATAL_APP_PROCESS = 5;
    constexpr int EXIT_FATAL_UID = 6;
    constexpr int EXIT_FATAL_PM_PATH = 7;
    constexpr int EXIT_FATAL_KILL = 9;
    constexpr int EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX = 10;
    constexpr int EXIT_MANAGER_APP_NOT_FOUND = 50;

    constexpr uint64_t STABLE_UPTIME_RESET_SECS = 60;
    constexpr int MAX_RESTARTS = 5;
    constexpr unsigned int RESTART_DELAY_SECS = 5;
    constexpr uint64_t REPLACE_WAIT_TIMEOUT_MS = 5000;
    constexpr uint64_t KILL_WAIT_TIMEOUT_MS = 1000;
    constexpr useconds_t POLL_INTERVAL_US = 25000;

    using server_entry_fn = void (*)(const char *dex_path, const char *main_class, const char *process_name);

    bool is_privileged_uid(pid_t pid);
    void wait_for_process_exit(pid_t pid, uint64_t timeout_ms);
    void kill_old_processes(pid_t replace_pid, const char *server_name);
    bool is_process_stopped_or_zombie(pid_t pid);

    [[noreturn]] void supervise_server(
            const char *dex_path,
            const char *main_class,
            const char *process_name,
            pid_t replace_pid,
            int pipe_write_fd,
            server_entry_fn run_server_fn);

} // namespace nightdog

#endif // NIGHTDOG_H
