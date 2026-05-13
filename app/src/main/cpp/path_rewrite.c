/*
 * LD_PRELOAD path rewrite library for renamed Termux packages.
 *
 * Intercepts filesystem calls and rewrites paths starting with
 * /data/data/com.termux/ to /data/data/com.bahtya/.
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
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <linux/stat.h>

#define OLD_PREFIX  "/data/data/com.termux"
#define NEW_PREFIX  "/data/data/com.bahtya"
#define OLD_LEN     21
#define NEW_LEN     21

/* Two thread-local buffers so functions that take two path arguments
 * (rename, link, symlink, etc.) don't clobber each other's result. */
static __thread char g_buf[PATH_MAX];
static __thread char g_buf2[PATH_MAX];

static const char *rewrite_to(const char *p, char *buf) {
    if (!p || strncmp(p, OLD_PREFIX, OLD_LEN) != 0) return p;
    size_t rest = strlen(p + OLD_LEN);
    if (NEW_LEN + rest >= PATH_MAX) return p;
    memcpy(buf, NEW_PREFIX, NEW_LEN);
    memcpy(buf + NEW_LEN, p + OLD_LEN, rest + 1);
    return buf;
}

static const char *rewrite(const char *p) {
    return rewrite_to(p, g_buf);
}

static const char *rewrite2(const char *p) {
    return rewrite_to(p, g_buf2);
}

/* --- execve shebang patching --- */

/* Clean up stale temp files from previous execve calls.
 * Runs once per thread (via __thread guard). */
static void cleanup_stale_temps(void) {
    static __thread int cleaned = 0;
    if (cleaned) return;
    cleaned = 1;

    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || tmpdir[0] == '\0')
        tmpdir = NEW_PREFIX "/files/usr/tmp";

    DIR *d = opendir(tmpdir);
    if (!d) return;
    struct dirent *ent;
    char p[PATH_MAX];
    while ((ent = readdir(d)) != NULL) {
        if (strncmp(ent->d_name, ".rw_", 4) == 0) {
            snprintf(p, sizeof(p), "%s/%s", tmpdir, ent->d_name);
            unlink(p);
        }
    }
    closedir(d);
}

/*
 * Check if the file at 'path' is a script whose shebang contains OLD_PREFIX.
 * If so, create a temp file with the shebang patched and return its path.
 * Returns NULL if no patching needed or on failure.
 * Caller must not free the returned pointer (points to thread-local buffer).
 */
static const char *patch_shebang_if_needed(const char *path) {
    static __thread char tmpbuf[PATH_MAX];

    cleanup_stale_temps();

    int fd = open(path, O_RDONLY);
    if (fd < 0) return NULL;

    char head[512];
    ssize_t n = read(fd, head, sizeof(head));
    close(fd);

    if (n < 2 || head[0] != '#' || head[1] != '!') return NULL;

    /* Check if shebang line contains OLD_PREFIX */
    int found = 0;
    for (int i = 0; i <= n - OLD_LEN; i++) {
        if (head[i] == '\n') break;
        if (memcmp(head + i, OLD_PREFIX, OLD_LEN) == 0) {
            found = 1;
            break;
        }
    }
    if (!found) return NULL;

    /* Patch OLD_PREFIX → NEW_PREFIX in the first chunk */
    char patched[512];
    int plen = 0;
    for (int i = 0; i < n; ) {
        if (i <= n - OLD_LEN && memcmp(head + i, OLD_PREFIX, OLD_LEN) == 0) {
            memcpy(patched + plen, NEW_PREFIX, NEW_LEN);
            plen += NEW_LEN;
            i += OLD_LEN;
        } else {
            patched[plen++] = head[i++];
        }
    }

    /* Create temp file */
    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || tmpdir[0] == '\0')
        tmpdir = NEW_PREFIX "/files/usr/tmp";

    snprintf(tmpbuf, sizeof(tmpbuf), "%s/.rw_XXXXXX", tmpdir);
    int tmpfd = mkstemp(tmpbuf);
    if (tmpfd < 0) return NULL;

    /* Write patched first chunk */
    if (write(tmpfd, patched, plen) != plen) {
        close(tmpfd);
        unlink(tmpbuf);
        return NULL;
    }

    /* Copy the rest of the file unchanged.
     * Body references to old paths are handled by recursive execve
     * interception and other intercepted filesystem calls. */
    fd = open(path, O_RDONLY);
    if (fd >= 0) {
        /* Skip the bytes we already wrote */
        char skipbuf[512];
        read(fd, skipbuf, n);

        char copybuf[4096];
        ssize_t r;
        while ((r = read(fd, copybuf, sizeof(copybuf))) > 0) {
            write(tmpfd, copybuf, r);
        }
        close(fd);
    }

    close(tmpfd);
    chmod(tmpbuf, 0700);
    return tmpbuf;
}

int execve(const char *pathname, char *const argv[], char *const envp[]) {
    int (*real_execve)(const char *, char *const [], char *const []) =
        dlsym(RTLD_NEXT, "execve");
    if (!real_execve) { errno = ENOSYS; return -1; }

    const char *rewritten = rewrite(pathname);

    /* Check if the target file is a script with a shebang needing patching */
    const char *patched = patch_shebang_if_needed(rewritten);
    if (patched) {
        int ret = real_execve(patched, argv, envp);
        int saved = errno;
        unlink(patched);
        errno = saved;
        return ret;
    }

    return real_execve(rewritten, argv, envp);
}

int execveat(int dirfd, const char *pathname, char *const argv[],
             char *const envp[], int flags) {
    int (*real_execveat)(int, const char *, char *const [], char *const [], int) =
        dlsym(RTLD_NEXT, "execveat");
    if (!real_execveat) { errno = ENOSYS; return -1; }

    if (pathname[0] == '/' || dirfd == AT_FDCWD) {
        const char *rewritten = rewrite(pathname);

        const char *patched = patch_shebang_if_needed(rewritten);
        if (patched) {
            int ret = real_execveat(dirfd, patched, argv, envp, flags);
            int saved = errno;
            unlink(patched);
            errno = saved;
            return ret;
        }

        return real_execveat(dirfd, rewritten, argv, envp, flags);
    }

    return real_execveat(dirfd, pathname, argv, envp, flags);
}

/* --- Intercepted functions --- */

DIR *opendir(const char *name) {
    DIR *(*real)(const char *) = dlsym(RTLD_NEXT, "opendir");
    if (!real) return NULL;
    return real(rewrite(name));
}

int stat(const char *p, struct stat *s) {
    int (*real)(const char *, struct stat *) = dlsym(RTLD_NEXT, "stat");
    if (!real) return -1;
    return real(rewrite(p), s);
}

int lstat(const char *p, struct stat *s) {
    int (*real)(const char *, struct stat *) = dlsym(RTLD_NEXT, "lstat");
    if (!real) return -1;
    return real(rewrite(p), s);
}

int fstatat(int fd, const char *p, struct stat *s, int flags) {
    int (*real)(int, const char *, struct stat *, int) = dlsym(RTLD_NEXT, "fstatat");
    if (!real) return -1;
    return real(fd, rewrite(p), s, flags);
}

int access(const char *p, int m) {
    int (*real)(const char *, int) = dlsym(RTLD_NEXT, "access");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int faccessat(int fd, const char *p, int m, int flags) {
    int (*real)(int, const char *, int, int) = dlsym(RTLD_NEXT, "faccessat");
    if (!real) return -1;
    return real(fd, rewrite(p), m, flags);
}

ssize_t readlink(const char *p, char *b, size_t s) {
    ssize_t (*real)(const char *, char *, size_t) = dlsym(RTLD_NEXT, "readlink");
    if (!real) return -1;
    return real(rewrite(p), b, s);
}

ssize_t readlinkat(int fd, const char *p, char *b, size_t s) {
    ssize_t (*real)(int, const char *, char *, size_t) = dlsym(RTLD_NEXT, "readlinkat");
    if (!real) return -1;
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
    if (!real) return -1;
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
    if (!real) return -1;
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
    if (!real) return -1;
    return real(fd, rewrite(p), f, m);
}

int creat(const char *p, mode_t m) {
    int (*real)(const char *, mode_t) = dlsym(RTLD_NEXT, "creat");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int rename(const char *o, const char *n) {
    int (*real)(const char *, const char *) = dlsym(RTLD_NEXT, "rename");
    if (!real) return -1;
    return real(rewrite(o), rewrite2(n));
}

int renameat(int oldfd, const char *o, int newfd, const char *n) {
    int (*real)(int, const char *, int, const char *) = dlsym(RTLD_NEXT, "renameat");
    if (!real) return -1;
    return real(oldfd, rewrite(o), newfd, rewrite2(n));
}

int unlink(const char *p) {
    int (*real)(const char *) = dlsym(RTLD_NEXT, "unlink");
    if (!real) return -1;
    return real(rewrite(p));
}

int unlinkat(int fd, const char *p, int flags) {
    int (*real)(int, const char *, int) = dlsym(RTLD_NEXT, "unlinkat");
    if (!real) return -1;
    return real(fd, rewrite(p), flags);
}

int mkdir(const char *p, mode_t m) {
    int (*real)(const char *, mode_t) = dlsym(RTLD_NEXT, "mkdir");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int mkdirat(int fd, const char *p, mode_t m) {
    int (*real)(int, const char *, mode_t) = dlsym(RTLD_NEXT, "mkdirat");
    if (!real) return -1;
    return real(fd, rewrite(p), m);
}

int chmod(const char *p, mode_t m) {
    int (*real)(const char *, mode_t) = dlsym(RTLD_NEXT, "chmod");
    if (!real) return -1;
    return real(rewrite(p), m);
}

int chown(const char *p, uid_t u, gid_t g) {
    int (*real)(const char *, uid_t, gid_t) = dlsym(RTLD_NEXT, "chown");
    if (!real) return -1;
    return real(rewrite(p), u, g);
}

int lchown(const char *p, uid_t u, gid_t g) {
    int (*real)(const char *, uid_t, gid_t) = dlsym(RTLD_NEXT, "lchown");
    if (!real) return -1;
    return real(rewrite(p), u, g);
}

int truncate(const char *p, off_t l) {
    int (*real)(const char *, off_t) = dlsym(RTLD_NEXT, "truncate");
    if (!real) return -1;
    return real(rewrite(p), l);
}

int link(const char *o, const char *n) {
    int (*real)(const char *, const char *) = dlsym(RTLD_NEXT, "link");
    if (!real) return -1;
    return real(rewrite(o), rewrite2(n));
}

int symlink(const char *o, const char *n) {
    int (*real)(const char *, const char *) = dlsym(RTLD_NEXT, "symlink");
    if (!real) return -1;
    return real(rewrite(o), rewrite2(n));
}

char *realpath(const char *p, char *r) {
    char *(*real)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
    if (!real) return NULL;
    return real(rewrite(p), r);
}

FILE *fopen(const char *p, const char *m) {
    FILE *(*real)(const char *, const char *) = dlsym(RTLD_NEXT, "fopen");
    if (!real) return NULL;
    return real(rewrite(p), m);
}

FILE *fopen64(const char *p, const char *m) {
    FILE *(*real)(const char *, const char *) = dlsym(RTLD_NEXT, "fopen64");
    if (!real) return NULL;
    return real(rewrite(p), m);
}

FILE *freopen(const char *p, const char *m, FILE *s) {
    FILE *(*real)(const char *, const char *, FILE *) = dlsym(RTLD_NEXT, "freopen");
    if (!real) return NULL;
    return real(rewrite(p), m, s);
}

int statx(int dirfd, const char *p, int flags, unsigned mask, struct statx *s) {
    int (*real)(int, const char *, int, unsigned, struct statx *) = dlsym(RTLD_NEXT, "statx");
    if (!real) return -1;
    return real(dirfd, rewrite(p), flags, mask, s);
}

int rmdir(const char *p) {
    int (*real)(const char *) = dlsym(RTLD_NEXT, "rmdir");
    if (!real) return -1;
    return real(rewrite(p));
}

int remove(const char *p) {
    int (*real)(const char *) = dlsym(RTLD_NEXT, "remove");
    if (!real) return -1;
    return real(rewrite(p));
}
