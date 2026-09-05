package studio.cluvex.aether.core.tunnel.net

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import studio.cluvex.aether.core.ConnectionOwnerResolver
import studio.cluvex.aether.core.LogRepository
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Per-app blocking: resolves which app owns a flow and whether it is blocked.
 *
 * The package-name -> UID translation is cached against the provider's current
 * set, so the common case (`blockedPackagesProvider()` returns the same set it
 * returned last packet) costs one set comparison instead of a PackageManager
 * round trip per connection.
 */
internal class AppUidGuard(
    connectivityManager: ConnectivityManager,
    private val packageManager: PackageManager,
    private val blockedPackagesProvider: () -> Set<String>
) {
    private val ownerResolver by lazy { ConnectionOwnerResolver(connectivityManager) }

    @Volatile
    private var cachedPackages: Set<String> = emptySet()

    @Volatile
    private var cachedUids: Set<Int> = emptySet()

    /** True when a block list is active at all — lets callers skip work entirely. */
    fun hasBlockedApps(): Boolean = blockedUids().isNotEmpty()

    fun isBlocked(uid: Int): Boolean = uid != UNKNOWN_UID && uid in blockedUids()

    /**
     * Owning app UID of a flow, or [UNKNOWN_UID] when it cannot be resolved.
     * An unresolvable UID is PERMITTED (fail-open), which is only worth a log
     * line while a block list is actually active.
     */
    fun ownerUid(
        protocol: Int,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int
    ): Int {
        val local = InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort)
        val remote = InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
        val uid = ownerResolver.resolve(protocol, local, remote)
        if (uid == UNKNOWN_UID && blockedUids().isNotEmpty()) {
            LogRepository.w("Flow owner UID unresolved; permitting connection")
        }
        return uid
    }

    private fun blockedUids(): Set<Int> {
        val packages = blockedPackagesProvider()
        if (packages == cachedPackages) return cachedUids

        return synchronized(this) {
            if (packages == cachedPackages) return@synchronized cachedUids
            val uids = packages.mapNotNullTo(mutableSetOf()) { pkg -> uidOf(pkg) }
            cachedPackages = packages
            cachedUids = uids
            uids
        }
    }

    private fun uidOf(pkg: String): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                pkg,
                PackageManager.ApplicationInfoFlags.of(0)
            ).uid
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(pkg, 0).uid
        }
    }.getOrNull()

    companion object {
        const val UNKNOWN_UID = -1
    }
}