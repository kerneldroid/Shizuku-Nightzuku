#include <fcntl.h>
#include <cstring>
#include <unistd.h>
#include <dlfcn.h>
#include <cerrno>
#include <syscall.h>
#include <cstdlib>
#include "selinux.h"

namespace se {

    static int __getcon(char **context) {
        if (context == nullptr) {
            errno = EINVAL;
            return -1;
        }
        *context = nullptr;

        int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
        if (fd < 0)
            return -1;

        long page_size = sysconf(_SC_PAGE_SIZE);
        size_t size = page_size > 0 ? static_cast<size_t>(page_size) : 4096;
        char *buf = static_cast<char *>(malloc(size));
        int rc = -1;

        if (buf != nullptr) {
            memset(buf, 0, size);

            ssize_t ret;
            do {
                ret = read(fd, buf, size - 1);
            } while (ret < 0 && errno == EINTR);

            if (ret > 0) {
                char *dup = strdup(buf);
                if (dup != nullptr) {
                    *context = dup;
                    rc = 0;
                }
            }
            free(buf);
        }

        int errno_hold = errno;
        close(fd);
        errno = errno_hold;
        return rc;
    }

    static int __setcon(const char *ctx) {
        if (ctx == nullptr) {
            errno = EINVAL;
            return -1;
        }
        int fd = open("/proc/self/attr/current", O_WRONLY | O_CLOEXEC);
        if (fd < 0)
            return fd;
        size_t len = strlen(ctx) + 1;
        ssize_t rc = write(fd, ctx, len);
        close(fd);
        return rc != static_cast<ssize_t>(len);
    }

    static int __setfilecon(const char *path, const char *ctx) {
        if (path == nullptr || ctx == nullptr) {
            errno = EINVAL;
            return -1;
        }
        long rc = syscall(__NR_setxattr, path, "security.selinux" /* XATTR_NAME_SELINUX */, ctx,
                          strlen(ctx) + 1, 0);
        if (rc != 0) {
            errno = static_cast<int>(-rc);
            return -1;
        }
        return 0;
    }

    static int __selinux_check_access(const char *scon, const char *tcon,
                                      const char *tclass, const char *perm, void *auditdata) {
        (void) scon;
        (void) tcon;
        (void) tclass;
        (void) perm;
        (void) auditdata;
        return 0;
    }

    static void __freecon(char *con) {
        free(con);
    }

    getcon_t *getcon = __getcon;
    setcon_t *setcon = __setcon;
    setfilecon_t *setfilecon = __setfilecon;
    selinux_check_access_t *selinux_check_access = __selinux_check_access;
    freecon_t *freecon = __freecon;

    void init() {
        if (access("/system/lib/libselinux.so", F_OK) != 0 &&
            access("/system/lib64/libselinux.so", F_OK) != 0)
            return;

        void *handle = dlopen("libselinux.so", RTLD_LAZY | RTLD_LOCAL);
        if (handle == nullptr)
            return;

        if (void *sym = dlsym(handle, "getcon"))
            getcon = reinterpret_cast<getcon_t *>(sym);
        if (void *sym = dlsym(handle, "setcon"))
            setcon = reinterpret_cast<setcon_t *>(sym);
        if (void *sym = dlsym(handle, "setfilecon"))
            setfilecon = reinterpret_cast<setfilecon_t *>(sym);
        if (void *sym = dlsym(handle, "selinux_check_access"))
            selinux_check_access = reinterpret_cast<selinux_check_access_t *>(sym);
        if (void *sym = dlsym(handle, "freecon"))
            freecon = reinterpret_cast<freecon_t *>(sym);
    }
}
