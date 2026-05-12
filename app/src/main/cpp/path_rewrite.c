/*
 * LD_PRELOAD path rewrite library for renamed Termux packages.
 *
 * Intercepts filesystem calls and rewrites paths starting with
 * /data/data/com.termux/ to /data/data/com.hermes.termux/.
 *
 * This fixes ALL binaries with compiled-in old paths at once,
 * eliminating the need for per-tool workarounds (APT_CONFIG,
 * dpkg conf overrides, TERMINFO, etc.).
 *
 * Build: ndk-build produces libpath_rewrite.so
 * Usage: LD_PRELOAD=$PREFIX/lib/libpath_rewrite.so
 */

#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>
#include <limits.h>
#include <dirent.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdarg.h>

#define OLD_PREFIX  "/data/data/com.termux"
#define NEW_PREFIX  "/data/data/com.hermes.termux"
#define OLD_LEN     21
#define NEW_LEN     28

static __thread char g_buf[PATH_MAX];

static const char *rewrite(const char *p) {
    if (!p || strncmp(p, OLD_PREFIX, OLD_LEN) != 0) return p;
    size_t rest = strlen(p + OLD_LEN);
    if (NEW_LEN + rest >= PATH_MAX) return p;
    memcpy(g_buf, NEW_PREFIX, NEW_LEN);
    memcpy(g_buf + NEW_LEN, p + OLD_LEN, rest + 1);
    return g_buf;
}

/* --- Intercepted functions --- */

DIR *opendir(const char *name) {
    DIR *(*real)(const char *) = dlsym(RTLD_NEXT, "opendir");
    return real(rewrite(name));
}

int stat(const char *p, struct stat *s) {
    int (*real)(const char *, struct stat *) = dlsym(RTLD_NEXT, "stat");
    return real(rewrite(p), s);
}

int lstat(const char *p, struct stat *s) {
    int (*real)(const char *, struct stat *) = dlsym(RTLD_NEXT, "lstat");
    return real(rewrite(p), s);
}

int fstatat(int fd, const char *p, struct stat *s, int flags) {
    int (*real)(int, const char *, struct stat *, int) = dlsym(RTLD_NEXT, "fstatat");
    return real(fd, rewrite(p), s, flags);
}

int access(const char *p, int m) {
    int (*real)(const char *, int) = dlsym(RTLD_NEXT, "access");
    return real(rewrite(p), m);
}

int faccessat(int fd, const char *p, int m, int flags) {
    int (*real)(int, const char *, int, int) = dlsym(RTLD_NEXT, "faccessat");
    return real(fd, rewrite(p), m, flags);
}

ssize_t readlink(const char *p, char *b, size_t s) {
    ssize_t (*real)(const char *, char *, size_t) = dlsym(RTLD_NEXT, "readlink");
    return real(rewrite(p), b, s);
}

ssize_t readlinkat(int fd, const char *p, char *b, size_t s) {
    ssize_t (*real)(int, const char *, char *, size_t) = dlsym(RTLD_NEXT, "readlinkat");
    return real(fd, rewrite(p), b, s);
}

int open(const char *p, int f, ...) {
    mode_t m = 0;
    if (f & (O_CREAT | O_TMPFILE)) {
        va_list a;
        va_start(a, f);
        m = (mode_t)va_arg(a, int);
        va_end(a);
    }
    int (*real)(const char *, int, ...) = dlsym(RTLD_NEXT, "open");
    return real(rewrite(p), f, m);
}

int open64(const char *p, int f, ...) {
    mode_t m = 0;
    if (f & (O_CREAT | O_TMPFILE)) {
        va_list a;
        va_start(a, f);
        m = (mode_t)va_arg(a, int);
        va_end(a);
    }
    int (*real)(const char *, int, ...) = dlsym(RTLD_NEXT, "open64");
    return real(rewrite(p), f, m);
}

int openat(int fd, const char *p, int f, ...) {
    mode_t m = 0;
    if (f & (O_CREAT | O_TMPFILE)) {
        va_list a;
        va_start(a, f);
        m = (mode_t)va_arg(a, int);
        va_end(a);
    }
    int (*real)(int, const char *, int, ...) = dlsym(RTLD_NEXT, "openat");
    return real(fd, rewrite(p), f, m);
}

int creat(const char *p, mode_t m) {
    int (*real)(const char *, mode_t) = dlsym(RTLD_NEXT, "creat");
    return real(rewrite(p), m);
}

int rename(const char *o, const char *n) {
    int (*real)(const char *, const char *) = dlsym(RTLD_NEXT, "rename");
    return real(rewrite(o), rewrite(n));
}

int renameat(int oldfd, const char *o, int newfd, const char *n) {
    int (*real)(int, const char *, int, const char *) = dlsym(RTLD_NEXT, "renameat");
    return real(oldfd, rewrite(o), newfd, rewrite(n));
}

int unlink(const char *p) {
    int (*real)(const char *) = dlsym(RTLD_NEXT, "unlink");
    return real(rewrite(p));
}

int unlinkat(int fd, const char *p, int flags) {
    int (*real)(int, const char *, int) = dlsym(RTLD_NEXT, "unlinkat");
    return real(fd, rewrite(p), flags);
}

int mkdir(const char *p, mode_t m) {
    int (*real)(const char *, mode_t) = dlsym(RTLD_NEXT, "mkdir");
    return real(rewrite(p), m);
}

int mkdirat(int fd, const char *p, mode_t m) {
    int (*real)(int, const char *, mode_t) = dlsym(RTLD_NEXT, "mkdirat");
    return real(fd, rewrite(p), m);
}

int chmod(const char *p, mode_t m) {
    int (*real)(const char *, mode_t) = dlsym(RTLD_NEXT, "chmod");
    return real(rewrite(p), m);
}

int chown(const char *p, uid_t u, gid_t g) {
    int (*real)(const char *, uid_t, gid_t) = dlsym(RTLD_NEXT, "chown");
    return real(rewrite(p), u, g);
}

int lchown(const char *p, uid_t u, gid_t g) {
    int (*real)(const char *, uid_t, gid_t) = dlsym(RTLD_NEXT, "lchown");
    return real(rewrite(p), u, g);
}

int truncate(const char *p, off_t l) {
    int (*real)(const char *, off_t) = dlsym(RTLD_NEXT, "truncate");
    return real(rewrite(p), l);
}

int link(const char *o, const char *n) {
    int (*real)(const char *, const char *) = dlsym(RTLD_NEXT, "link");
    return real(rewrite(o), rewrite(n));
}

int symlink(const char *o, const char *n) {
    int (*real)(const char *, const char *) = dlsym(RTLD_NEXT, "symlink");
    return real(rewrite(o), rewrite(n));
}

char *realpath(const char *p, char *r) {
    char *(*real)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
    return real(rewrite(p), r);
}
