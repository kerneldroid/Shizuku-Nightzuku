#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <ctime>
#include <cstring>
#include <climits>
#include <csignal>
#include <libgen.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <cerrno>
#include <string>
#include "android.h"
#include "misc.h"
#include "selinux.h"
#include "cgroup.h"
#include "nightdog.h"
#include "logging.h"

#ifdef DEBUG
#define JAVA_DEBUGGABLE
#endif

#define perrorf(...) fprintf(stderr, __VA_ARGS__)

#define PACKAGE_NAME "moe.shizuku.privileged.api"
#define SERVER_NAME "shizuku_server"
#define SERVER_CLASS_PATH "rikka.shizuku.server.ShizukuService"

#if defined(__arm__)
#define ABI "arm"
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
#define ABI "x86_64"
#elif defined(__aarch64__)
#define ABI "arm64"
#endif

static std::string dirname_str(const std::string &path) {
    if (path.empty())
        return ".";
    bool all_slash = true;
    for (char c : path) {
        if (c != '/') {
            all_slash = false;
            break;
        }
    }
    if (all_slash)
        return "/";

    size_t end = path.size();
    while (end > 0 && path[end - 1] == '/')
        --end;

    std::string trimmed = path.substr(0, end);
    size_t pos = trimmed.rfind('/');
    if (pos == std::string::npos)
        return ".";
    if (pos == 0)
        return "/";

    size_t head_end = pos;
    while (head_end > 0 && trimmed[head_end - 1] == '/')
        --head_end;
    return head_end == 0 ? "/" : trimmed.substr(0, head_end);
}

static std::string derive_apk_from_self_exe() {
    char buf[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (len <= 0)
        return "";
    buf[len] = '\0';

    std::string exe_path(buf);
    std::string abi_dir = dirname_str(exe_path);
    std::string lib_dir = dirname_str(abi_dir);
    std::string app_dir = dirname_str(lib_dir);
    if (app_dir.empty() || app_dir == "." || app_dir == "/")
        return "";

    std::string candidate = app_dir + "/base.apk";
    if (access(candidate.c_str(), R_OK) == 0)
        return candidate;

    size_t lib_pos = exe_path.find("/lib/");
    if (lib_pos != std::string::npos) {
        std::string fallback = exe_path.substr(0, lib_pos) + "/base.apk";
        if (access(fallback.c_str(), R_OK) == 0)
            return fallback;
    }
    return "";
}

static void push_arg(char ***argv_ptr, size_t *count_ptr, const char *arg) {
    size_t next_count = *count_ptr + 1;
    char **next_argv = (*argv_ptr == nullptr)
                       ? static_cast<char **>(malloc(next_count * sizeof(char *)))
                       : static_cast<char **>(realloc(*argv_ptr, next_count * sizeof(char *)));
    if (next_argv == nullptr) {
        _exit(nightdog::EXIT_FATAL_APP_PROCESS);
    }
    next_argv[*count_ptr] = (arg != nullptr) ? strdup(arg) : nullptr;
    *argv_ptr = next_argv;
    *count_ptr = next_count;
}

static void run_server(const char *dex_path, const char *main_class, const char *process_name) {
    signal(SIGPIPE, SIG_DFL);
    signal(SIGHUP, SIG_DFL);

    if (setenv("CLASSPATH", dex_path, true)) {
        LOGE("can't set CLASSPATH\n");
        _exit(nightdog::EXIT_FATAL_SET_CLASSPATH);
    }

    std::string dex_dir = dirname_str(dex_path);
    std::string lib_path = dex_dir + "/lib/" + ABI;
    std::string class_path_opt = std::string("-Djava.class.path=") + dex_path;
    std::string lib_path_opt = std::string("-Dshizuku.library.path=") + lib_path;
    std::string nice_name_opt = std::string("--nice-name=") + process_name;

    char **argv = nullptr;
    size_t argc = 0;

    push_arg(&argv, &argc, "/system/bin/app_process");
    push_arg(&argv, &argc, class_path_opt.c_str());
    push_arg(&argv, &argc, lib_path_opt.c_str());

#ifdef JAVA_DEBUGGABLE
    push_arg(&argv, &argc, "-Xcompiler-option");
    push_arg(&argv, &argc, "--debuggable");
    if (android::GetApiLevel() >= 30) {
        push_arg(&argv, &argc, "-XjdwpProvider:adbconnection");
        push_arg(&argv, &argc, "-XjdwpOptions:suspend=n,server=y");
    } else if (android::GetApiLevel() >= 28) {
        push_arg(&argv, &argc, "-XjdwpProvider:internal");
        push_arg(&argv, &argc, "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y");
    } else {
        push_arg(&argv, &argc, "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y");
    }
#endif

    push_arg(&argv, &argc, "/system/bin");
    push_arg(&argv, &argc, nice_name_opt.c_str());
    push_arg(&argv, &argc, main_class);
#ifdef JAVA_DEBUGGABLE
    push_arg(&argv, &argc, "--debug");
#endif
    push_arg(&argv, &argc, nullptr);

    LOGD("exec app_process");

    if (execvp((const char *) argv[0], argv)) {
        _exit(nightdog::EXIT_FATAL_APP_PROCESS);
    }
}

[[noreturn]] static void start_server(
        const char *path,
        const char *main_class,
        const char *process_name,
        pid_t replace_pid) {
    int pipe_fds[2] = {-1, -1};
    if (pipe2(pipe_fds, O_CLOEXEC) != 0) {
        pipe_fds[0] = -1;
        pipe_fds[1] = -1;
    }

    pid_t supervisor_pid = fork();
    if (supervisor_pid == -1) {
        if (pipe_fds[0] != -1) {
            close(pipe_fds[0]);
            close(pipe_fds[1]);
        }
        perrorf("fatal: can't fork\n");
        exit(nightdog::EXIT_FATAL_FORK);
    } else if (supervisor_pid == 0) {
        if (pipe_fds[0] != -1) {
            close(pipe_fds[0]);
        }
        setsid();
        chdir("/");
        nightdog::supervise_server(path, main_class, process_name, replace_pid, pipe_fds[1], run_server);
    }

    if (pipe_fds[1] != -1) {
        close(pipe_fds[1]);
    }

    pid_t server_pid = supervisor_pid;
    if (pipe_fds[0] != -1) {
        pid_t child_pid = -1;
        bool ok = read_exact_retry(pipe_fds[0], &child_pid, sizeof(child_pid));
        close(pipe_fds[0]);
        if (ok && child_pid > 0) {
            server_pid = child_pid;
        } else {
            perrorf("fatal: failed to spawn shizuku_server\n");
            exit(nightdog::EXIT_FATAL_FORK);
        }
    }

    printf("info: shizuku_server pid is %d\n", server_pid);
    printf("info: shizuku_starter exit with 0\n");
    fflush(stdout);
    fflush(stderr);
    exit(EXIT_SUCCESS);
}

static int check_selinux(const char *s, const char *t, const char *c, const char *p) {
    int res = se::selinux_check_access(s, t, c, p, nullptr);
#ifndef DEBUG
    if (res != 0) {
#endif
    printf("info: selinux_check_access %s %s %s %s: %d\n", s, t ? t : "(null)", c, p, res);
    fflush(stdout);
#ifndef DEBUG
    }
#endif
    return res;
}

static int switch_cgroup() {
    int pid = getpid();
    if (cgroup::switch_cgroup("/acct", pid)) {
        printf("info: switch cgroup succeeded, cgroup in /acct\n");
        return 0;
    }
    if (cgroup::switch_cgroup("/dev/cg2_bpf", pid)) {
        printf("info: switch cgroup succeeded, cgroup in /dev/cg2_bpf\n");
        return 0;
    }
    if (cgroup::switch_cgroup("/sys/fs/cgroup", pid)) {
        printf("info: switch cgroup succeeded, cgroup in /sys/fs/cgroup\n");
        return 0;
    }
    char buf[PROP_VALUE_MAX + 1];
    if (__system_property_get("ro.config.per_app_memcg", buf) > 0 &&
        strncmp(buf, "false", 5) != 0) {
        if (cgroup::switch_cgroup("/dev/memcg/apps", pid)) {
            printf("info: switch cgroup succeeded, cgroup in /dev/memcg/apps\n");
            return 0;
        }
    }
    printf("warn: can't switch cgroup\n");
    fflush(stdout);
    return -1;
}

int main(int argc, char *argv[]) {
    signal(SIGPIPE, SIG_IGN);
    signal(SIGHUP, SIG_IGN);

    std::string apk_path;
    pid_t replace_pid = -1;
    for (int i = 0; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0) {
            apk_path = argv[i] + 6;
        } else if (strncmp(argv[i], "--replace=", 10) == 0) {
            const char *pid_str = argv[i] + 10;
            if (is_num(pid_str)) {
                int parsed = atoi(pid_str);
                if (parsed > 1) {
                    replace_pid = parsed;
                }
            }
        }
    }

    uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        perrorf("fatal: run Shizuku from non root nor adb user (uid=%d).\n", uid);
        exit(nightdog::EXIT_FATAL_UID);
    }

    se::init();

    if (uid == 0) {
        switch_cgroup();

        if (android::GetApiLevel() >= 29) {
            printf("info: switching mount namespace to init...\n");
            switch_mnt_ns(1);
        }
    }

    if (uid == 0) {
        char *context = nullptr;
        if (se::getcon(&context) == 0 && context != nullptr) {
            int res = 0;

            res |= check_selinux("u:r:untrusted_app:s0", context, "binder", "call");
            res |= check_selinux("u:r:untrusted_app:s0", context, "binder", "transfer");

            if (res != 0) {
                perrorf("fatal: the su you are using does not allow app (u:r:untrusted_app:s0) to connect to su (%s) with binder.\n",
                        context);
                se::freecon(context);
                exit(nightdog::EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX);
            }
            se::freecon(context);
        }
    }

    printf("info: starter begin\n");
    fflush(stdout);

    if (replace_pid <= 1) {
        printf("info: killing old process...\n");
        fflush(stdout);
        nightdog::kill_old_processes(-1, SERVER_NAME);
    }

    if (!apk_path.empty() && access(apk_path.c_str(), R_OK) == 0) {
        printf("info: use apk path from argv\n");
        fflush(stdout);
    } else {
        apk_path.clear();
    }

    if (apk_path.empty()) {
        std::string derived = derive_apk_from_self_exe();
        if (!derived.empty()) {
            printf("info: resolved base.apk from /proc/self/exe\n");
            fflush(stdout);
            apk_path = derived;
        }
    }

    if (apk_path.empty()) {
        FILE *f = popen("pm path " PACKAGE_NAME, "r");
        if (f != nullptr) {
            char line[PATH_MAX]{0};
            if (fgets(line, sizeof(line), f) != nullptr) {
                char *trimmed = trim(line);
                if (trimmed != nullptr && strncmp(trimmed, "package:", 8) == 0) {
                    apk_path = trimmed + 8;
                }
            }
            pclose(f);
        }
    }

    if (apk_path.empty()) {
        perrorf("fatal: can't get path of manager\n");
        exit(nightdog::EXIT_FATAL_PM_PATH);
    }

    printf("info: apk path is %s\n", apk_path.c_str());
    if (access(apk_path.c_str(), R_OK) != 0) {
        perrorf("fatal: can't access manager %s\n", apk_path.c_str());
        exit(nightdog::EXIT_FATAL_PM_PATH);
    }

    printf("info: starting server...\n");
    fflush(stdout);
    LOGD("start_server");
    start_server(apk_path.c_str(), SERVER_CLASS_PATH, SERVER_NAME, replace_pid);
}
