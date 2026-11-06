#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <climits>
#include <csignal>
#include <fcntl.h>
#include <unistd.h>
#include <sys/wait.h>
#include "nightdog.h"
#include "misc.h"

#define LOG_TAG "NightDog"
#include "logging.h"

namespace nightdog {

    static char g_self_exe[PATH_MAX] = {0};
    static ssize_t g_self_exe_len = 0;
    static pid_t g_self_pid = -1;
    static pid_t g_replace_pid = -1;
    static const char *g_server_name = "shizuku_server";

    bool is_privileged_uid(pid_t pid) {
        uid_t uid = static_cast<uid_t>(-1);
        if (get_proc_uid(pid, &uid) != 0)
            return false;
        return uid == 0 || uid == 2000;
    }

    void wait_for_process_exit(pid_t pid, uint64_t timeout_ms) {
        if (pid <= 1)
            return;
        uint64_t start_ms = monotonic_now_ms();
        while (monotonic_now_ms() - start_ms < timeout_ms) {
            bool alive = (kill(pid, 0) == 0) || (errno != ESRCH);
            if (!alive)
                break;
            usleep(POLL_INTERVAL_US);
        }
    }

    bool is_process_stopped_or_zombie(pid_t pid) {
        if (pid <= 1)
            return false;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/stat", pid);
        int fd = open(path, O_RDONLY | O_CLOEXEC);
        if (fd == -1)
            return false;

        char buf[512];
        ssize_t n = fdgets(buf, sizeof(buf), fd);
        close(fd);
        if (n <= 0)
            return false;

        const char *rparen = strrchr(buf, ')');
        if (rparen == nullptr || rparen[1] != ' ')
            return false;

        char state = rparen[2];
        return state == 'Z' || state == 'T' || state == 't' || state == 'X';
    }

    static void kill_proc_visitor(pid_t pid) {
        if (pid == g_self_pid || (g_replace_pid > 1 && pid == g_replace_pid))
            return;

        // Only signal processes owned by root (0) or shell (2000) to avoid killing spoofed app processes
        if (!is_privileged_uid(pid))
            return;

        char name[1024];
        if (get_proc_name(pid, name, sizeof(name)) != 0)
            return;

        bool is_server = (strcmp(g_server_name, name) == 0);
        bool is_old_supervisor = false;

        if (!is_server && g_self_exe_len > 0) {
            char exe_link[64];
            char exe_buf[PATH_MAX];
            snprintf(exe_link, sizeof(exe_link), "/proc/%d/exe", pid);
            ssize_t len = readlink(exe_link, exe_buf, sizeof(exe_buf) - 1);
            if (len == g_self_exe_len && memcmp(exe_buf, g_self_exe, static_cast<size_t>(len)) == 0) {
                is_old_supervisor = true;
            }
        }

        if (!is_server && !is_old_supervisor)
            return;

        if (kill(pid, SIGKILL) == 0) {
            printf("info: killed %d (%s)\n", pid, name);
            fflush(stdout);
            if (is_server) {
                wait_for_process_exit(pid, KILL_WAIT_TIMEOUT_MS);
            }
        } else if (errno == EPERM) {
            printf("warn: can't kill %d (EPERM), ignoring potential fake process.\n", pid);
            fflush(stdout);
        } else if (errno != ESRCH) {
            printf("warn: failed to kill %d (%s)\n", pid, name);
            fflush(stdout);
        }
    }

    void kill_old_processes(pid_t replace_pid, const char *server_name) {
        g_self_pid = getpid();
        g_replace_pid = replace_pid;
        g_server_name = server_name ? server_name : "shizuku_server";

        g_self_exe_len = readlink("/proc/self/exe", g_self_exe, sizeof(g_self_exe) - 1);
        if (g_self_exe_len > 0) {
            g_self_exe[g_self_exe_len] = '\0';
        } else {
            g_self_exe_len = 0;
        }

        if (replace_pid > 1 && replace_pid != g_self_pid && is_privileged_uid(replace_pid)) {
            char name[1024];
            if (get_proc_name(replace_pid, name, sizeof(name)) == 0 &&
                strcmp(g_server_name, name) == 0) {
                if (kill(replace_pid, SIGKILL) == 0) {
                    LOGI("replaced old %s pid %d", g_server_name, replace_pid);
                    wait_for_process_exit(replace_pid, REPLACE_WAIT_TIMEOUT_MS);
                }
            }
        }

        foreach_proc(kill_proc_visitor);
    }

    [[noreturn]] void supervise_server(
            const char *dex_path,
            const char *main_class,
            const char *process_name,
            pid_t replace_pid,
            int pipe_write_fd,
            server_entry_fn run_server_fn) {
        if (replace_pid > 1) {
            kill_old_processes(replace_pid, process_name);
        }

        int restart_count = 0;

        for (;;) {
            pid_t pid = fork();
            if (pid == -1) {
                LOGE("fatal: can't fork %s", process_name);
                if (pipe_write_fd != -1) {
                    close(pipe_write_fd);
                }
                _exit(EXIT_FATAL_FORK);
            } else if (pid == 0) {
                if (pipe_write_fd != -1) {
                    close(pipe_write_fd);
                }
                LOGD("child %s starting", process_name);
                setsid();
                chdir("/");
                detach_stdio_to_dev_null();
                run_server_fn(dex_path, main_class, process_name);
                _exit(EXIT_FATAL_APP_PROCESS);
            }

            uint64_t started_at_ms = monotonic_now_ms();
            if (pipe_write_fd != -1) {
                (void) write_exact_retry(pipe_write_fd, &pid, sizeof(pid));
                close(pipe_write_fd);
                pipe_write_fd = -1;
                detach_stdio_to_dev_null();
            }

            LOGI("shizuku_server pid is %d", pid);

            int status = 0;
            for (;;) {
                pid_t waited = waitpid(pid, &status, WUNTRACED);
                if (waited == -1) {
                    if (errno == EINTR)
                        continue;
                    break;
                }
                if (WIFSTOPPED(status) || is_process_stopped_or_zombie(pid)) {
                    LOGW("shizuku_server (pid=%d) entered stopped/traced state, killing for recovery", pid);
                    kill(pid, SIGKILL);
                    continue;
                }
                break;
            }

            uint64_t now_ms = monotonic_now_ms();
            uint64_t uptime_secs = (now_ms >= started_at_ms) ? ((now_ms - started_at_ms) / 1000ULL) : 0ULL;

            int exit_code = -1;
            bool killed_by_signal = false;
            if (WIFEXITED(status)) {
                exit_code = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code = 128 + WTERMSIG(status);
                killed_by_signal = true;
            }

            LOGI("shizuku_server exited with code %d after %llus",
                 exit_code, static_cast<unsigned long long>(uptime_secs));

            if (!killed_by_signal && exit_code == 0) {
                LOGI("shizuku_server exited cleanly, no restart");
                _exit(0);
            }

            if (!killed_by_signal && exit_code == EXIT_MANAGER_APP_NOT_FOUND) {
                LOGW("manager application not found, stopping supervisor without restart");
                _exit(0);
            }

            if (uptime_secs >= STABLE_UPTIME_RESET_SECS) {
                restart_count = 0;
            }

            ++restart_count;
            if (restart_count > MAX_RESTARTS) {
                LOGE("fatal: shizuku_server crashed %d times consecutively, giving up", restart_count);
                _exit(EXIT_FATAL_APP_PROCESS);
            }

            LOGW("restarting shizuku_server in %u seconds (attempt %d/%d)...",
                 RESTART_DELAY_SECS, restart_count, MAX_RESTARTS);
            sleep(RESTART_DELAY_SECS);
        }
    }

} // namespace nightdog
