package studio.cluvex.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "aether_onboarding")

/**
 * What the Telegram channel popup needs to know about its own history.
 * [timesSeen] counts OK taps, not displays.
 */
data class ChannelPromoState(val timesSeen: Int, val optedOut: Boolean)

/**
 * Tracks whether the first-run onboarding (merged into Aether) has been
 * completed, and the history of the Telegram channel popup that follows it.
 * Kept in its own tiny DataStore so it survives profile resets.
 */
class OnboardingStore(private val context: Context) {
    private val doneKey = booleanPreferencesKey("onboarding_done")
    private val promoSeenKey = intPreferencesKey("channel_promo_seen")
    private val promoOptOutKey = booleanPreferencesKey("channel_promo_opt_out")

    val completed: Flow<Boolean> =
        context.onboardingDataStore.data.map { it[doneKey] ?: false }

    val channelPromo: Flow<ChannelPromoState> =
        context.onboardingDataStore.data.map {
            ChannelPromoState(
                timesSeen = it[promoSeenKey] ?: 0,
                optedOut = it[promoOptOutKey] ?: false,
            )
        }

    suspend fun markCompleted() {
        context.onboardingDataStore.edit { it[doneKey] = true }
    }

    /** One acknowledged popup; [optOut] is sticky, it is never cleared here. */
    suspend fun recordChannelPromoSeen(optOut: Boolean) {
        context.onboardingDataStore.edit { prefs ->
            val seen = prefs[promoSeenKey] ?: 0
            if (seen < Int.MAX_VALUE) prefs[promoSeenKey] = seen + 1
            if (optOut) prefs[promoOptOutKey] = true
        }
    }
}
