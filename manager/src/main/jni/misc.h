#ifndef MISC_H
#define MISC_H

#include <sys/types.h>
#include <cstddef>
#include <cstdint>

ssize_t fdgets(char *buf, size_t size, int fd);
int get_proc_name(int pid, char *name, size_t size);
int is_num(const char *s);
bool parse_proc_status_uid(const char *status_buf, size_t len, uid_t *out_uid);
int get_proc_uid(int pid, uid_t *out_uid);
int copyfileat(int src_path_fd, const char *src_path, int dst_path_fd, const char *dst_path);
int copyfile(const char *src_path, const char *dst_path);
uintptr_t memsearch(uintptr_t start, uintptr_t end, const void *value, size_t size);
int switch_mnt_ns(int pid);

using foreach_proc_function = void(pid_t);
void foreach_proc(foreach_proc_function *func);

char *trim(char *str);
bool write_exact_retry(int fd, const void *buf, size_t len);
bool read_exact_retry(int fd, void *buf, size_t len);
void detach_stdio_to_dev_null();
uint64_t monotonic_now_ms();

#endif // MISC_H
