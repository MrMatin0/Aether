package studio.cluvex.aether.vpn.session

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.SplitMode

private const val TAG = "vpn"

/**
 * Builds the two TUN interfaces the service can put up: the forwarding one a
 * live session runs on, and the kill-switch blackhole.
 *
 * Everything the platform is told about routing, DNS, MTU and per-app policy is
 * decided here and nowhere else, so a leak can only ever have one root cause.
 */
internal object TunFactory {

    /**
     * The interface a live session forwards through.
     *
     * @throws IllegalStateException when the platform refuses to establish it
     *   (consent revoked, or another VPN already holds the slot).
     */
    fun establishSession(
        service: VpnService,
        profile: ConnectionProfile,
    ): ParcelFileDescriptor {
        // User-tunable MTU (defaults to 1280 — safe for Iranian mobile/DPI).
        // Clamped to a sane range so a bad saved value can't break establish().
        val mtu = profile.safeMtu()
        val builder = with(service) { Builder() }
            .setSession("Aether")
            .setMtu(mtu)
            // The TUN address MUST match hev's tunnel.ipv4/ipv6 (see HevConfig).
            .addAddress(TunnelConfig.TUN_IPV4, TunnelConfig.TUN_IPV4_PREFIX)
            .addAddress(TunnelConfig.TUN_IPV6, TunnelConfig.TUN_IPV6_PREFIX)
            .addRoute("0.0.0.0", 0)

        // IPv6 LEAK PROTECTION (1.2.4): on by default -- the v6 default
        // route keeps IPv6 traffic inside the tunnel. Can be disabled for
        // networks where a default v6 route breaks connectivity.
        if (profile.ipv6LeakProtection) {
            builder.addRoute("::", 0)
        }

        // KILL SWITCH (1.2.4): a blocking interface never falls back to
        // direct traffic while the tunnel is not forwarding.
        //
        // BATTERY FIX (same flag, second reason): the userspace filter bridge
        // reads this fd from a plain Java thread. `establish()` hands out a
        // NON-BLOCKING fd by default, and on Android a non-blocking read with
        // no packet pending returns 0 — so that reader spun read()→0→read()
        // forever and pinned a CPU core for the entire session, while writes
        // could fail with EAGAIN and silently drop packets. Blocking mode parks
        // the reader on the fd instead: zero CPU while the tunnel is idle.
        // (hev-socks5-tunnel manages the fd mode itself, and the two paths are
        // mutually exclusive, so this only ever affects the bridge.)
        if (profile.killSwitch || profile.strictKillSwitch || profile.blockedApps.isNotEmpty()) {
            builder.setBlocking(true)
        }

        TunnelConfig.DNS_SERVERS.forEach { builder.addDnsServer(it) }

        // Split tunneling + loop prevention (keeps the engine's own traffic off
        // the TUN, equivalent to v2rayNG's in-process protect()).
        applyAppFilter(service, builder, profile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val tun = builder.establish()
            ?: throw IllegalStateException("Failed to establish the VPN interface")
        DiagnosticsLog.i(
            TAG,
            "TUN established: ipv4=${TunnelConfig.TUN_IPV4}/${TunnelConfig.TUN_IPV4_PREFIX} " +
                "ipv6=${TunnelConfig.TUN_IPV6}/${TunnelConfig.TUN_IPV6_PREFIX} mtu=$mtu " +
                "split=${profile.splitMode} apps=${profile.splitApps.size} dns=${TunnelConfig.DNS_SERVERS}",
        )
        return tun
    }

    /**
     * The kill-switch blackhole: routes everything, reads nothing, so every
     * packet is dropped instead of leaking direct.
     *
     * Returns null when the platform refuses to establish it (consent revoked,
     * another VPN took over). Reporting lockdown anyway would leave all traffic
     * flowing direct while the UI claims protection, so the caller MUST treat
     * null as "not protected".
     */
    fun establishLockdown(
        service: VpnService,
        profile: ConnectionProfile,
    ): ParcelFileDescriptor? {
        val builder = with(service) { Builder() }
            .setSession("Aether KillSwitch")
            .setMtu(profile.safeMtu())
            .addAddress(TunnelConfig.TUN_IPV4, TunnelConfig.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        if (profile.ipv6LeakProtection) {
            builder.addAddress(TunnelConfig.TUN_IPV6, TunnelConfig.TUN_IPV6_PREFIX)
            builder.addRoute("::", 0)
        }
        return runCatching { builder.establish() }.getOrNull()
    }

    /**
     * Applies the split-tunnel policy and always keeps the app's own engine
     * traffic off the TUN (loop prevention).
     *
     * - OFF     : everything routes through the VPN except our own package.
     * - INCLUDE : ONLY the chosen apps route through the VPN. Our own package is
     *             implicitly excluded because it is never added to the allow-list.
     * - EXCLUDE : everything routes through the VPN except the chosen apps + us.
     *
     * Blocked apps must stay INSIDE the TUN in BOTH modes so the filter bridge
     * can drop their traffic; leaving them outside gives them direct internet
     * instead of none. EXCLUDE always got this right, INCLUDE did not — a
     * blocked app that was not also a split app was simply never routed into
     * the tunnel, so per-app blocking silently did nothing for it.
     */
    private fun applyAppFilter(
        service: VpnService,
        builder: VpnService.Builder,
        profile: ConnectionProfile,
    ) {
        val ownPackage = service.packageName
        val apps = profile.splitApps.filter { it.isNotBlank() && it != ownPackage }
        val blocked = profile.blockedApps.filter { it.isNotBlank() && it != ownPackage }
        when (profile.splitMode) {
            SplitMode.INCLUDE -> {
                if (apps.isEmpty()) {
                    // Nothing selected -> fall back to OFF so we don't build a
                    // tunnel that carries no traffic at all. Blocked apps end up
                    // inside the TUN that way too, so they stay blocked.
                    safeDisallow(builder, ownPackage, ownPackage)
                    return
                }
                (apps + blocked).distinct().forEach { safeAllow(builder, it) }
            }
            SplitMode.EXCLUDE -> {
                safeDisallow(builder, ownPackage, ownPackage)
                apps.filter { it !in profile.blockedApps }
                    .forEach { safeDisallow(builder, it, ownPackage) }
            }
            SplitMode.OFF -> safeDisallow(builder, ownPackage, ownPackage)
        }
    }

    private fun safeAllow(builder: VpnService.Builder, pkg: String) {
        try {
            builder.addAllowedApplication(pkg)
        } catch (_: Exception) {
            DiagnosticsLog.w(TAG, "addAllowedApplication failed for $pkg (not installed?)")
        }
    }

    private fun safeDisallow(builder: VpnService.Builder, pkg: String, ownPackage: String) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (_: Exception) {
            if (pkg != ownPackage) DiagnosticsLog.w(TAG, "addDisallowedApplication failed for $pkg")
        }
    }
}
