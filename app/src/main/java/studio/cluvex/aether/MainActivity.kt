package studio.cluvex.aether

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AppLocale
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.IpRefreshPhase
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.data.AppPrefs
import studio.cluvex.aether.data.OnboardingStore
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.HomeScreen
import studio.cluvex.aether.ui.OnboardingScreen
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File

/**
 * NOTE: declared with `android:launchMode="singleTop"` in the manifest, and
 * [onNewIntent] depends on that. Under the default "standard" mode the system
 * builds a fresh instance for every delivered Intent and onNewIntent is never
 * called, which silently disabled the whole connect-on-launch path.
 */
class MainActivity : ComponentActivity() {
    private lateinit var profileStore: ProfileStore
    private lateinit var onboardingStore: OnboardingStore
    private var pendingProfile: ConnectionProfile? = null

    private val uiProfile = MutableStateFlow<ConnectionProfile?>(null)
    private val profileSaves = MutableSharedFlow<ConnectionProfile>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val requested = pendingProfile
            pendingProfile = null
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            // The process can be killed while the consent dialog is on screen;
            // the registry redelivers RESULT_OK to the recreated activity, but
            // its pendingProfile is null then. Fall back to the persisted
            // profile so a granted consent never ends in "nothing happened".
            lifecycleScope.launch {
                val profile = requested
                    ?: runCatching { profileStore.profile.first() }.getOrNull()
                    ?: ConnectionProfile()
                AetherController.connect(this@MainActivity, profile)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        profileStore = ProfileStore(applicationContext)
        onboardingStore = OnboardingStore(applicationContext)

        lifecycleScope.launch {
            uiProfile.compareAndSet(null, profileStore.profile.first())
        }
        lifecycleScope.launch {
            profileSaves.conflate().collect { snapshot -> profileStore.save(snapshot) }
        }

        // NOT unconditionally, and not here-and-now for a first-run user: see
        // maybeRequestNotificationPermission. A returning user still gets asked
        // as early as possible, because the session notification is the only
        // place the live throughput readout exists.
        lifecycleScope.launch {
            if (onboardingStore.completed.first()) maybeRequestNotificationPermission()
        }

        // Off the main thread: this is flash I/O on the cold-start critical
        // path, and a StrictMode disk-read violation on every launch.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val hasCrash = withContext(Dispatchers.IO) {
                    runCatching { File(filesDir, AetherApp.CRASH_FILE).exists() }.getOrDefault(false)
                }
                if (hasCrash) startActivity(Intent(this@MainActivity, CrashReportActivity::class.java))
            }
        }

        val launchedToConnect =
            intent?.getBooleanExtra(EXTRA_CONNECT_ON_LAUNCH, false) == true
        handleConnectOnLaunch(intent)
        if (savedInstanceState == null && !launchedToConnect) maybeAutoConnectOnLaunch()

        setContent {
            AetherTheme {
                val onboardingDone by onboardingStore.completed.collectAsState(initial = true)
                val state by AetherController.state.collectAsState()
                val profile by uiProfile.collectAsState()
                val connectedSince by AetherController.connectedSince.collectAsState()
                val ipInfo by AetherController.ipInfo.collectAsState()
                val ipLoading by AetherController.ipLoading.collectAsState()

                // Keep state classification out of the networking effect. Error
                // is intentionally IDLE so failures show the operator IP.
                val phase = IpRefreshPhase.from(state)
                LaunchedEffect(phase) {
                    when (phase) {
                        IpRefreshPhase.CONNECTED -> {
                            AetherController.setIpLoading(true)
                            withTimeoutOrNull(100_000L) {
                                AetherController.ipInfo.first { it?.viaTunnel == true }
                            }
                            if (AetherController.ipInfo.value?.viaTunnel != true) {
                                val info = withContext(Dispatchers.IO) {
                                    NetProbe.fetchIpInfoViaSocksWithRetry(
                                        TunnelConfig.SOCKS_HOST,
                                        TunnelConfig.SOCKS_PORT,
                                    )
                                }
                                if (info != null) {
                                    AetherController.offerTunnelIpInfo(
                                        IpEndpoint(info.ip, info.countryCode, true),
                                    )
                                }
                            }
                            AetherController.setIpLoading(false)
                        }
                        IpRefreshPhase.IDLE -> {
                            AetherController.setIpInfo(null)
                            AetherController.setIpLoading(true)
                            val info = withContext(Dispatchers.IO) { NetProbe.fetchIpInfoDirectWithRetry() }
                            AetherController.setIpInfo(info?.let { IpEndpoint(it.ip, it.countryCode, false) })
                            AetherController.setIpLoading(false)
                        }
                        IpRefreshPhase.BUSY -> {
                            AetherController.setIpInfo(null)
                            AetherController.setIpLoading(false)
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!onboardingDone) {
                        OnboardingScreen(
                            onFinished = {
                                lifecycleScope.launch {
                                    onboardingStore.markCompleted()
                                    // Now, and not a moment earlier: the user has
                                    // just been told what the app does, so the
                                    // permission dialog is a question they can
                                    // actually answer.
                                    maybeRequestNotificationPermission()
                                }
                            },
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            profile = profile ?: ConnectionProfile(),
                            connectedSince = connectedSince,
                            ipInfo = ipInfo,
                            ipLoading = ipLoading,
                            onProfileChange = { updated ->
                                uiProfile.value = updated
                                profileSaves.tryEmit(updated)
                            },
                            onToggleConnection = { toggleConnection(state) },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectOnLaunch(intent)
    }

    private fun handleConnectOnLaunch(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONNECT_ON_LAUNCH, false) != true) return
        intent.removeExtra(EXTRA_CONNECT_ON_LAUNCH)
        lifecycleScope.launch {
            val current = AetherController.state.value
            if (!current.isConnected && !current.isBusy) toggleConnection(current)
        }
    }

    private fun maybeAutoConnectOnLaunch() {
        AppPrefs.init(applicationContext)
        if (!AppPrefs.state.value.autoConnectOnLaunch) return
        lifecycleScope.launch {
            val current = AetherController.state.value
            if (!current.isConnected && !current.isBusy) toggleConnection(current)
        }
    }

    private fun toggleConnection(state: ConnectionState) {
        if (state.isConnected || state.isBusy) {
            AetherController.disconnect(this)
            return
        }
        lifecycleScope.launch {
            val profile = uiProfile.value ?: profileStore.profile.first()
            val consent = AetherController.prepare(this@MainActivity)
            if (consent != null) {
                pendingProfile = profile
                vpnPermissionLauncher.launch(consent)
            } else {
                AetherController.connect(this@MainActivity, profile)
            }
        }
    }

    /**
     * Ask for POST_NOTIFICATIONS, at a moment where the question makes sense.
     *
     * WHAT WAS WRONG: this was called unconditionally from onCreate. On a first
     * launch that put the system permission dialog on top of page one of
     * onboarding, before a single word had explained why a VPN client wants to
     * post notifications — and the session notification is not decoration here,
     * it is where the live throughput readout lives and the only thing that keeps
     * the foreground service honest. A dialog nobody has been given a reason for
     * gets dismissed, and on Android 13+ two dismissals mean the permission is
     * permanently denied with no in-app way back. Asking after onboarding costs
     * nothing and turns a reflex into an answer.
     *
     * It is also guarded twice: not re-asked when already granted, and not more
     * than once per PROCESS. That second guard used to be an INSTANCE field,
     * which does not survive an activity recreation — and onCreate runs again on
     * every configuration change the manifest does not swallow, so a rotation
     * re-armed the prompt and walked the user straight into the two-dismissal
     * permanent denial this whole comment exists to prevent.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (notificationPromptShown) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        notificationPromptShown = true
        runCatching {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_CONNECT_ON_LAUNCH = "studio.cluvex.aether.CONNECT_ON_LAUNCH"

        /**
         * One notification prompt per PROCESS. Lives here, not on the instance:
         * an activity is recreated far more often than a process is started.
         */
        @Volatile
        private var notificationPromptShown = false
    }
}
