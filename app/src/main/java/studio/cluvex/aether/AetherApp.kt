package studio.cluvex.aether

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.LogLevel
import studio.cluvex.aether.core.SessionTracker
import studio.cluvex.aether.data.AppPrefs
import java.io.File

class AetherApp : Application() {

    /**
     * 1.3.0 IN-APP LANGUAGE: the selected language has to be layered onto the
     * resources BEFORE anything reads a string, which is what attachBaseContext
     * is for. Note that getApplicationContext() is still null at this point —
     * AppLocale deliberately reads its (synchronous, SharedPreferences-backed)
     * value from the context handed in here.
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()

        // Wire the persistent diagnostics log FIRST, so anything logged during
        // startup (and any crash) is written to disk and survives process death.
        DiagnosticsLog.init(File(filesDir, "diagnostics.log"))
        installCrashHandler()

        // 1.3.0: app-behaviour flags (auto-connect, history opt-out) and the
        // session recorder. Both are process-wide and are read from places with
        // no UI attached — the boot receiver, the VPN service's state changes —
        // so they are initialised here rather than from a composable.
        AppPrefs.init(this)
        SessionTracker.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Captures otherwise-fatal JVM exceptions and flushes them to the on-disk
     * diagnostics log BEFORE the process dies. This is why "after a crash the
     * log was empty": the log lived only in memory. Now the crash cause is
     * persisted and reloaded into the panel on the next launch. (Native faults
     * inside the in-process tunnel can't be caught here, but every line logged
     * up to that instant is flushed together with the crash line below.)
     *
     * The FATAL line is written with [DiagnosticsLog.logBlocking]: normal log
     * writes are handed to a background writer thread, which is NOT guaranteed
     * to be scheduled again while the process is being torn down — so the one
     * line that explains the crash was the line most likely to be lost.
     *
     * Feature merge: the same stack trace is ALSO written to a small
     * standalone file ([CRASH_FILE]). MainActivity checks for it on the next
     * cold start and opens [CrashReportActivity] so the user can actually SEE
     * and copy the report instead of it hiding inside the diagnostics log.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagnosticsLog.logBlocking(
                    "crash",
                    LogLevel.ERROR,
                    "FATAL on thread '${thread.name}': $throwable\n" +
                        Log.getStackTraceString(throwable),
                )
            }
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "Thread: ${thread.name}\n\n" + Log.getStackTraceString(throwable),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CHANNEL_ID = "aether_vpn"

        /** Standalone crash report consumed by [CrashReportActivity]. */
        const val CRASH_FILE = "last_crash.txt"
    }
}
