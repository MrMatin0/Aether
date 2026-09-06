package studio.cluvex.aether.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

val LocalReducedMotion = staticCompositionLocalOf { false }

/** Settings changes do not necessarily recreate the activity. Observe and refresh on resume. */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun read() = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
            )
        }.isSuccess
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reduced = read()
        }
        lifecycle.addObserver(lifecycleObserver)
        reduced = read()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            if (registered) runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return reduced
}

@Composable
@ReadOnlyComposable
fun aetherDuration(millis: Int): Int = if (LocalReducedMotion.current) 0 else millis
