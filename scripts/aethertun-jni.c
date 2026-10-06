/*
 * aethertun-jni.c — Aether's OWN JNI bridge to hev-socks5-tunnel.
 *
 * PERMANENT FIX for the "VPN mode never connects while proxy mode works" bug:
 *
 * The app used to System.loadLibrary("hev-socks5-tunnel") and rely on hev's
 * bundled hev-jni.c (built with -DPKGNAME=...) to register the TProxy*
 * natives onto studio.cluvex.aether.core.TProxyService. That coupled the
 * Kotlin declarations to WHATEVER JNI signatures the upstream default branch
 * happens to use. Upstream then changed TProxyStartService from
 * '(Ljava/lang/String;I)V' to '(Ljava/lang/String;I)Z', RegisterNatives
 * inside its JNI_OnLoad failed with:
 *   NoSuchMethodError: no static or non-static method
 *   "Lstudio/cluvex/aether/core/TProxyService;.TProxyStartService(Ljava/lang/String;I)Z"
 * System.loadLibrary() threw, TProxyService.available stayed false, and VPN
 * mode died with "hev native library unavailable". Proxy mode never loads
 * hev, which is why it kept working.
 *
 * This bridge removes that failure mode CATEGORICALLY:
 *  - It binds ONLY hev's stable public C API (include/hev-main.h):
 *      hev_socks5_tunnel_main / hev_socks5_tunnel_quit / hev_socks5_tunnel_stats
 *    which is verified at LINK time (-Wl,--no-undefined) — if upstream ever
 *    breaks it, the CI build fails loudly instead of shipping a broken APK.
 *  - It exposes conventional Java_* symbols that WE control, matching the
 *    Kotlin declarations in TProxyService.kt exactly.
 *  - It defines its OWN JNI_OnLoad. This is CRITICAL: ART locates JNI_OnLoad
 *    with dlsym() on the loaded library's handle, and dlsym() searches the
 *    library AND its DT_NEEDED dependencies. Without our own JNI_OnLoad,
 *    dlsym() found hev's one inside libhev-socks5-tunnel.so and executed it
 *    anyway; its RegisterNatives then failed on upstream's drifted
 *    'TProxyStopService()Z' signature (observed in the field, round 2).
 *    Ours shadows it (the root object is searched first), and
 *    build-natives.sh ALSO strips hev-jni.c out of the hev build entirely,
 *    so upstream JNI ABI drift can never again break library loading.
 *
 * THREADING (do not "simplify" this): the tunnel event loop MUST run on a
 * native pthread. hev-task-system implements its coroutines by swapping the
 * thread's stack pointer; doing that on an ART-attached Java thread corrupts
 * what the runtime expects of the stack and kills the whole app with a native
 * SIGSEGV shortly after real traffic starts. It must also run IN-PROCESS,
 * because the VpnService TUN fd is only valid inside this process.
 *
 * LIFECYCLE (r4, fix for the reconnect race): the loop used to run on a
 * DETACHED thread, and TProxyStopService only *asked* it to quit. The
 * `running` flag was cleared later, by the dying thread itself, so a connect
 * that came straight after a disconnect could be told "already running, start
 * ignored" (and get JNI_TRUE!) while the OLD loop was still alive on the OLD
 * TUN fd - or start a second loop next to it. Now:
 *  - the worker is JOINABLE and its handle is kept;
 *  - stop re-sends the quit request and waits (bounded) until the loop has
 *    really returned, then joins the thread;
 *  - start reaps a finished worker first and REFUSES to start over a live
 *    one (JNI_FALSE), so the caller can never believe in a tunnel that is not
 *    the one it asked for;
 *  - TProxyIsRunning reports the truth to Kotlin.
 */

#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>

/* hev-socks5-tunnel's stable public C API (include/hev-main.h). */
extern int hev_socks5_tunnel_main(const char *config_path, int tun_fd);
extern void hev_socks5_tunnel_quit(void);
extern void hev_socks5_tunnel_stats(size_t *tx_packets, size_t *tx_bytes,
                                    size_t *rx_packets, size_t *rx_bytes);

#define TAG "aethertun"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* How long a stop waits for the loop to return before giving up. */
#define STOP_WAIT_MS 3000
/* How often the quit request is re-sent while waiting. A quit that lands
 * before hev's main has set up its task system is a no-op, so one request is
 * not enough on a fast connect/disconnect. */
#define STOP_POLL_MS 100

/*
 * Our own JNI_OnLoad — it registers NOTHING and only reports the JNI version,
 * but it MUST exist. ART resolves "JNI_OnLoad" with dlsym() on this library's
 * handle, and dlsym() searches this object FIRST, then its DT_NEEDED
 * dependencies. If this symbol were missing, dlsym() could find a JNI_OnLoad
 * inside a dependency (as happened with hev's hev-jni.c) and run foreign
 * RegisterNatives code with signatures we do not control. Never remove this.
 */
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)vm;
    (void)reserved;
    LOGI("libaethertun r4 loaded: conventional Java_* bindings; hev JNI layer unused");
    return JNI_VERSION_1_6;
}

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t exited_cond = PTHREAD_COND_INITIALIZER;
/* A worker thread has been created and not yet joined. */
static bool started = false;
/* That worker has returned from hev_socks5_tunnel_main(). */
static bool exited = false;
/* The worker, valid while `started` is true. */
static pthread_t worker;

typedef struct {
    char *config_path;
    int tun_fd;
} StartArgs;

static long long
monotonic_ms(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + (long long)(ts.tv_nsec / 1000000L);
}

/* Absolute CLOCK_REALTIME deadline `ms` from now, for pthread_cond_timedwait. */
static void
realtime_in(struct timespec *ts, long ms)
{
    clock_gettime(CLOCK_REALTIME, ts);
    ts->tv_sec += ms / 1000L;
    ts->tv_nsec += (ms % 1000L) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec += 1;
        ts->tv_nsec -= 1000000000L;
    }
}

/* Joins a worker that has already returned. Caller holds `lock`. */
static void
reap_locked(void)
{
    if (started && exited) {
        pthread_join(worker, NULL);
        started = false;
        exited = false;
    }
}

static void *
tunnel_thread(void *data)
{
    StartArgs *args = (StartArgs *)data;
    int res;

    LOGI("tunnel loop starting on native pthread (fd=%d)", args->tun_fd);
    res = hev_socks5_tunnel_main(args->config_path, args->tun_fd);
    LOGI("tunnel loop exited (res=%d)", res);

    free(args->config_path);
    free(args);

    pthread_mutex_lock(&lock);
    exited = true;
    pthread_cond_broadcast(&exited_cond);
    pthread_mutex_unlock(&lock);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyStartService(JNIEnv *env,
                                                                jclass clazz,
                                                                jstring config_path,
                                                                jint tun_fd)
{
    const char *path = NULL;
    StartArgs *args = NULL;
    pthread_t thread;
    int rc;

    (void)clazz;

    path = (*env)->GetStringUTFChars(env, config_path, NULL);
    if (!path)
        return JNI_FALSE;

    args = (StartArgs *)malloc(sizeof(StartArgs));
    if (!args) {
        (*env)->ReleaseStringUTFChars(env, config_path, path);
        return JNI_FALSE;
    }
    args->config_path = strdup(path);
    args->tun_fd = (int)tun_fd;
    (*env)->ReleaseStringUTFChars(env, config_path, path);
    if (!args->config_path) {
        free(args);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&lock);
    reap_locked();
    if (started) {
        /* The previous loop is still alive. Starting "successfully" here is
         * exactly the race this revision removes: refuse, and let the caller
         * stop it first. */
        pthread_mutex_unlock(&lock);
        LOGE("previous tunnel loop is still running; refusing to start a second one");
        free(args->config_path);
        free(args);
        return JNI_FALSE;
    }

    rc = pthread_create(&thread, NULL, tunnel_thread, args);
    if (rc != 0) {
        pthread_mutex_unlock(&lock);
        LOGE("pthread_create failed: %d", rc);
        free(args->config_path);
        free(args);
        return JNI_FALSE;
    }
    worker = thread;
    started = true;
    exited = false;
    pthread_mutex_unlock(&lock);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyStopService(JNIEnv *env, jclass clazz)
{
    long long deadline;
    bool done;

    (void)env;
    (void)clazz;

    pthread_mutex_lock(&lock);
    if (!started) {
        pthread_mutex_unlock(&lock);
        return;
    }
    pthread_mutex_unlock(&lock);

    deadline = monotonic_ms() + STOP_WAIT_MS;
    for (;;) {
        struct timespec until;

        hev_socks5_tunnel_quit();

        pthread_mutex_lock(&lock);
        realtime_in(&until, STOP_POLL_MS);
        while (started && !exited) {
            if (pthread_cond_timedwait(&exited_cond, &lock, &until) == ETIMEDOUT)
                break;
        }
        done = !started || exited;
        if (done)
            reap_locked();
        pthread_mutex_unlock(&lock);

        if (done) {
            LOGI("tunnel loop stopped and joined");
            return;
        }
        if (monotonic_ms() >= deadline) {
            LOGE("tunnel loop did not exit within %d ms", STOP_WAIT_MS);
            return;
        }
    }
}

JNIEXPORT jboolean JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyIsRunning(JNIEnv *env, jclass clazz)
{
    bool live;

    (void)env;
    (void)clazz;

    pthread_mutex_lock(&lock);
    live = started && !exited;
    pthread_mutex_unlock(&lock);
    return live ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_studio_cluvex_aether_core_TProxyService_TProxyGetStats(JNIEnv *env, jclass clazz)
{
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    jlong values[4];
    jlongArray result;

    (void)clazz;

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    values[0] = (jlong)tx_packets;
    values[1] = (jlong)tx_bytes;
    values[2] = (jlong)rx_packets;
    values[3] = (jlong)rx_bytes;

    result = (*env)->NewLongArray(env, 4);
    if (!result)
        return NULL;
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
