package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.HomeActivity

private const val LOG_TAG = "AppEnforcer"

/** applicationId of the kids-mdm-browser fork - see [AppEnforcer.applyBrowserPolicy]. */
private const val BROWSER_PACKAGE_NAME = "com.kidsmdm.browser"

/**
 * The set of packages [AppEnforcer] will ever consider suspending/hiding, and that
 * [com.kidslauncher.mdm.server.MdmSyncWorker]'s status report offers the admin site as
 * allow/restrict checkboxes - the two must stay in sync, or the admin could check a box for an
 * app this loop then silently never acts on.
 *
 * A direct [PackageManager] query, not the launcher's own `Application.apps` (LauncherApps-based)
 * list: that excludes apps already hidden via [android.app.admin.DevicePolicyManager.setApplicationHidden],
 * so relying on it here would mean a hidden app could never be found again to un-hide it - a
 * permanent one-way lock. But a raw, unfiltered [PackageManager.getInstalledApplications] is
 * actively dangerous: it includes core OS/system packages that must never be suspended (doing so
 * can crash or boot-loop the device - this is not hypothetical, it happened during development).
 * So: third-party (non-system) apps are always included, and system apps only if they expose a
 * launcher (home-screen) icon - see the function's own doc comment for why this replaced an
 * earlier hand-maintained package-name allowlist.
 */
internal fun controllablePackages(pm: PackageManager): List<String> {
    // MATCH_UNINSTALLED_PACKAGES is required here, or this list silently drops any package this
    // same enforcer has already hidden via setApplicationHidden - PackageManager excludes hidden
    // packages from getInstalledApplications() by default. Without this flag, a hidden app can
    // never be found again to un-hide it: a permanent one-way lock, and exactly the bug this
    // function was written to avoid in the first place (see the class-level doc above).
    //
    // System apps are included only if they expose a launcher (home-screen) icon - a real,
    // user-facing app a kid could actually open - rather than via a hand-maintained package-name
    // allowlist. The allowlist approach (this file's history) worked for the GrapheneOS test
    // device but left a GMS/OEM device (Chrome, Play Store, Gmail, YouTube, Maps, the
    // manufacturer's own Camera/Gallery/Messages, ...) with almost nothing controllable, since
    // none of those package names were in the list. A launcher-intent check is device-agnostic:
    // it naturally includes exactly the apps a kid can tap open, and naturally excludes headless
    // system services/components (SystemUI, telephony internals, resource overlays, ...) since
    // those don't expose a launcher activity in the first place - without needing to enumerate
    // every OEM's package names by hand. MATCH_UNINSTALLED_PACKAGES on this query too, for the
    // same already-hidden-app reason as above.
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val launchablePackages = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()

    return pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        .filter { info ->
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                info.packageName in launchablePackages
        }
        .map { it.packageName }
        .distinct()
}

/**
 * Suspends/unsuspends installed apps to match [PolicyResponse.allowlist] (null/empty means no
 * restriction - nothing suspended), and - only when the server says so via
 * [PolicyResponse.kioskDesired] - pins the device to the allowed packages via Android's Device
 * Owner lock-task API, plus the WiFi/Bluetooth radio restrictions below. Only acts when this app
 * is device owner - a no-op otherwise, so it's safe to ship before the phone is actually
 * re-provisioned.
 */
object AppEnforcer {

    fun apply(context: Context, policy: PolicyResponse?) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return
        }
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)

        // While a locally-entered offline override is active, or a parent has flipped the manual
        // "pause all restrictions" kill-switch in Settings, treat the device exactly as if the
        // server sent no policy at all - every branch below already means "fully open" for a null
        // policy (no allowlist, kiosk not desired, WiFi/Bluetooth "open"), so this reuses the same
        // code path rather than duplicating an "unlock everything" special case.
        val overrideActive = OfflineOverride.isActive() || LauncherPreferences.mdm().restrictionsPaused()
        val effectivePolicy = if (overrideActive) null else policy

        // LOCAL-DEVIATION: upstream pins this launcher as the permanent HOME app unconditionally.
        // Samsung Kids only functions while One UI Home is still the home screen, so this follows
        // the same kiosk setting as the lock-task pinning below - pinned while kiosk is on,
        // actively released (not merely left unset) while it is off.
        enforceDefaultHome(dpm, admin, context, kioskDesired = effectivePolicy?.kioskDesired == true)

        val allowedPackages = effectivePolicy?.allowlist?.takeIf { it.isNotEmpty() }?.toSet()
        val ownPackage = context.packageName
        val pm = context.packageManager

        val installedPackages = controllablePackages(pm)

        for (packageName in installedPackages) {
            if (packageName == ownPackage) continue

            val shouldBeSuspended = allowedPackages != null && packageName !in allowedPackages
            val currentlySuspended = try {
                pm.isPackageSuspended(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }
            if (shouldBeSuspended == currentlySuspended) continue

            try {
                // Both calls can fail *without* throwing - setPackagesSuspended returns the
                // subset of package names it couldn't act on (some privileged/protected system
                // packages silently refuse suspension by platform policy) and
                // setApplicationHidden returns a plain boolean. Discarding these previously made
                // that failure mode invisible - confirmed live with a carrier-privileged /product-
                // partition system app that stayed reachable despite being correctly excluded
                // from the allowlist, with nothing in logs to explain why.
                val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), shouldBeSuspended)
                if (!notSuspended.isNullOrEmpty()) {
                    Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName (setPackagesSuspended)")
                }
                val hiddenOk = dpm.setApplicationHidden(admin, packageName, shouldBeSuspended)
                if (!hiddenOk) {
                    Log.w(LOG_TAG, "Platform refused to ${if (shouldBeSuspended) "hide" else "unhide"} $packageName (setApplicationHidden)")
                }
            } catch (e: Exception) {
                Log.w(
                    LOG_TAG,
                    "Failed to ${if (shouldBeSuspended) "suspend" else "unsuspend"} $packageName",
                    e
                )
            }
        }

        applyKioskState(
            dpm, admin, ownPackage, allowedPackages,
            kioskDesired = effectivePolicy?.kioskDesired == true,
            lockTaskFeatures = effectivePolicy?.lockTaskFeatures ?: 0,
        )

        clearRadioRestrictions(dpm, admin)

        // LOCAL-DEVIATION: without kiosk pinning the notification shade stays reachable, and its
        // Location tile let the kid turn location tracking off from the home screen - the one
        // restriction that has to hold for Home Assistant to know where the phone is. Lifted
        // together with every other restriction while an override is active, same as the rest of
        // apply().
        applyLocationRestriction(dpm, admin, locked = !overrideActive)

        // Same "fully open" treatment as everything else while an override is active - confirmed
        // live this needs to include the VPN filter too: the whole point of the offline-override PIN
        // and the pause-restrictions kill-switch is a guaranteed working, unblocked device when
        // something's wrong, which can include the VPN/filter itself misbehaving. An earlier version
        // of this deliberately excluded vpnFilterEnabled from that (reasoning: an emergency escape
        // hatch for access restrictions shouldn't silently override a parent's separate content-
        // filtering choice) - wrong in practice, reverted. Only true-by-default (filtering on) when
        // no override is active AND no policy has ever been fetched, which has no admin choice yet
        // to respect.
        val vpnFilterEnabled = if (overrideActive) false else (policy?.vpnFilterEnabled ?: true)
        applyVpnRestrictions(context, dpm, admin, vpnFilterEnabled)

        applyPrivateDnsLock(dpm, admin)

        applySideloadRestriction(dpm, admin, blockSideloading = !overrideActive)

        applyBrowserPolicy(dpm, admin, context, locked = !overrideActive)
    }

    /**
     * Only engages lock-task/kiosk pinning when BOTH an allowlist is configured AND the server
     * says to via [PolicyResponse.kioskDesired] - the admin site is the sole source of truth for
     * this, there is no on-device switch. Still gated on an allowlist existing at all: pinning
     * with zero allowed packages would strand the device on nothing but the launcher itself.
     */
    private fun applyKioskState(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        ownPackage: String,
        allowedPackages: Set<String>?,
        kioskDesired: Boolean,
        lockTaskFeatures: Long,
    ) {
        val mdm = LauncherPreferences.mdm()
        val shouldEngageKiosk = allowedPackages != null && kioskDesired

        if (!shouldEngageKiosk) {
            try {
                dpm.setLockTaskPackages(admin, emptyArray())
                mdm.kioskEnabled(false)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to unpin kiosk state", e)
            }
            return
        }

        // Order matters here, and it didn't before: setLockTaskFeatures (which carries the
        // keyguard-safe bit) must be verified *before* setLockTaskPackages ever pins the device,
        // and a failure here must skip pinning entirely rather than continue on with whatever
        // feature set the platform already had. The previous version called both DPM calls back
        // to back inside one try/catch that just logged and swallowed either failure - if
        // setLockTaskPackages succeeded but setLockTaskFeatures then threw (silently, for any
        // device-specific reason), the device would end up pinned with Android's own default
        // (non-keyguard) feature set. That's the exact condition that caused a real, unrecoverable-
        // except-via-hardware-recovery-mode boot deadlock before (GrapheneOS's auto-reboot, or any
        // plain reboot, re-locks storage pre-decrypt with no keyguard reachable and no launcher
        // resolvable either - see kid-phone-server's CLAUDE.md for the full incident). Failing
        // closed to "not pinned this cycle" is safe either way, since apply() re-runs every ~2
        // minutes (or sooner via SSE push) and will retry.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!applyLockTaskFeaturesVerified(dpm, admin, lockTaskFeatures.toInt())) {
                Log.w(LOG_TAG, "Refusing to pin kiosk this cycle - lock task features never verified")
                return
            }
        }

        try {
            dpm.setLockTaskPackages(admin, (allowedPackages + ownPackage).toTypedArray())
            mdm.kioskEnabled(true)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to pin kiosk packages", e)
        }
    }

    /**
     * setLockTaskFeatures is a cheap Binder call - one immediate retry after a failed
     * write-then-verify is trivial insurance against a transient platform hiccup, not a real
     * performance concern. Reads back [DevicePolicyManager.getLockTaskFeatures] rather than
     * trusting the setter not to throw, since a silent mismatch is exactly what would otherwise
     * let a device get pinned without the keyguard bit actually applied.
     */
    private fun applyLockTaskFeaturesVerified(dpm: DevicePolicyManager, admin: ComponentName, features: Int): Boolean {
        repeat(2) { attempt ->
            try {
                dpm.setLockTaskFeatures(admin, features)
                if (dpm.getLockTaskFeatures(admin) == features) return true
                Log.w(
                    LOG_TAG,
                    "setLockTaskFeatures didn't verify on attempt ${attempt + 1} " +
                        "(wanted $features, got ${dpm.getLockTaskFeatures(admin)})"
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "setLockTaskFeatures threw on attempt ${attempt + 1}", e)
            }
        }
        return false
    }

    /**
     * WiFi/Bluetooth restriction *levels* (open/restricted/disabled, independent of the always-on
     * kiosk allowlist) were retired as an admin-configurable policy - confirmed in practice not
     * worth the UI complexity. This unconditionally clears the four restrictions that feature used
     * to set, every `apply()` cycle same as before, rather than just deleting the code outright -
     * a device that already had "restricted" or "disabled" saved from before this change needs
     * those actively lifted, not just abandoned in whatever state they were last left in.
     */
    /**
     * LOCAL-DEVIATION: stops the kid flipping Location off from the Quick Settings tile (or the
     * Settings app, if it is ever allowed). [UserManager.DISALLOW_CONFIG_LOCATION] only blocks
     * *changing* the setting - it does not turn location on by itself, so a parent still decides
     * whether it is on in the first place. Idempotent and individually guarded, matching the rest
     * of this file.
     */
    private fun applyLocationRestriction(dpm: DevicePolicyManager, admin: ComponentName, locked: Boolean) {
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_LOCATION, locked)
    }

    private fun clearRadioRestrictions(dpm: DevicePolicyManager, admin: ComponentName) {
        setRestriction(dpm, admin, UserManager.DISALLOW_CHANGE_WIFI_STATE, false)
        setRestriction(dpm, admin, UserManager.DISALLOW_ADD_WIFI_CONFIG, false)
        setRestriction(dpm, admin, UserManager.DISALLOW_BLUETOOTH, false)
        setRestriction(dpm, admin, UserManager.DISALLOW_CONFIG_BLUETOOTH, false)
    }

    /**
     * Sets Android's always-on-VPN requirement on the launcher's own package via
     * [DevicePolicyManager.setAlwaysOnVpnPackage] - [KidVpnService] is now the device's only VPN
     * (the standalone Tailscale app and its managed-config/exit-node plumbing are retired). This is
     * what makes Android auto-start/restart the service as needed, independent of anything this app
     * does itself.
     *
     * Lockdown is deliberately NOT enabled here - unconditional, not gated on any policy field,
     * because it's actively wrong for this VPN's design, not just risky. Confirmed live: with
     * lockdown on, once this VPN becomes the system default network, `dumpsys connectivity` showed
     * its routes as only the one fake-DNS-server address plus an explicit `::/0 unreachable` -
     * [KidVpnService] deliberately never adds a general/default route (see that class's doc comment
     * on why: it's what makes the "everything else flows over the real network untouched" design
     * work at all when NOT locked down). Lockdown forces every app's traffic onto this network
     * regardless of what routes it declares, so with no default route to fall back to, general
     * internet connectivity broke device-wide - not a hypothetical, reproduced on the very first
     * live test of this code. This is the exact same failure mode as the old
     * Tailscale-without-an-exit-node bug (github.com/tailscale/tailscale#12925) that motivated
     * gating lockdown on an exit node being configured for that VPN - except here there's no
     * equivalent "configure a broader route" escape hatch to gate on, since narrow routing is
     * permanent by design, not a transient unconfigured state. Without lockdown, Android's
     * always-on designation still auto-restarts the service and still blocks a kid from disabling
     * it via Settings (both Device-Owner-enforced); the only thing lost is that DNS briefly goes
     * unfiltered through the OS's normal path if the service is ever down, which is an acceptable
     * gap next to bricking the device's entire network.
     *
     * [vpnFilterEnabled] is [PolicyResponse.vpnFilterEnabled] - a per-device admin toggle for the
     * filter itself (independent of the lockdown discussion above). When off, both the always-on
     * designation and the running service are torn down; when on, both are (re)established. Also
     * caches the value so [com.kidslauncher.mdm.Application.onCreate]'s cold-start
     * [KidVpnService.start] call - which runs before any policy has ever been fetched - knows
     * whether to start the service at all, rather than always starting and then immediately
     * stopping it again once this function runs on the first sync.
     */
    private fun applyVpnRestrictions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        vpnFilterEnabled: Boolean,
    ) {
        LauncherPreferences.mdm().vpnFilterEnabled(vpnFilterEnabled)
        try {
            if (vpnFilterEnabled) {
                dpm.setAlwaysOnVpnPackage(admin, context.packageName, false)
                KidVpnService.start(context)
            } else {
                // LOCAL-DEVIATION: upstream cleared the always-on designation unconditionally here.
                // With the built-in filter off, this household runs WireGuard instead (one VPN at a
                // time - see OPDRACHT.md's conflict 1), and a parent-configured always-on WireGuard
                // was being wiped on every sync, which is exactly what stops a kid turning the
                // tunnel - and with it the DNS filtering - off. Only clear our own designation.
                if (dpm.getAlwaysOnVpnPackage(admin) == context.packageName) {
                    dpm.setAlwaysOnVpnPackage(admin, null, false)
                }
                KidVpnService.stop(context)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply VPN filter enabled state", e)
        }
    }

    /**
     * Locks Android's system-wide Private DNS to Opportunistic (never a specific host - the retired
     * DoT-to-Pi approach's `setGlobalPrivateDnsModeSpecifiedHost` call lived here previously, see
     * this repo's CLAUDE.md) and prevents it being switched away via
     * [UserManager.DISALLOW_CONFIG_PRIVATE_DNS]. Unconditional on every `apply()` call, not gated on
     * any policy field - closes the one gap [KidVpnService]'s DNS filtering can't otherwise cover on
     * its own: a kid manually switching Private DNS to Strict mode against some other resolver would
     * produce encrypted DoT traffic on port 853 that this app can't inspect, silently bypassing
     * filtering entirely. Opportunistic mode, by contrast, only *attempts* DoT and transparently
     * falls back to plain port-53 DNS if that fails - which is exactly what happens against
     * [KidVpnService]'s own fake DNS server, since it deliberately doesn't answer on port 853 (see
     * that class's doc comment on why it must stay DoT-silent for this to work).
     */
    private fun applyPrivateDnsLock(
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        try {
            dpm.setGlobalPrivateDnsModeOpportunistic(admin)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to apply Private DNS lock", e)
        }
    }

    /**
     * Blocks installing apps from outside the device's trusted app store (sideloaded APKs - e.g.
     * one downloaded via an allowlisted browser). Not gated on any policy field, but - unlike
     * [applyPrivateDnsLock], which this was originally modeled on - IS lifted while an offline
     * override or the pause-restrictions kill-switch is active, via [blockSideloading]. Confirmed
     * live this needed to be the standing rule for every restriction added here going forward, not
     * just this one: the whole point of those overrides is a guaranteed, fully-unblocked device
     * when something's wrong, and there's no such thing as a restriction a parent can't reach
     * through their own offline PIN if they need to. Doesn't affect the device's own app store
     * (Play Store / GrapheneOS's, if present) installing or updating apps, nor this app's own
     * Device-Owner `PackageInstaller`-based tracked-app pushes (see `AppInstaller.kt`) - both go
     * through a privileged install path this restriction doesn't gate at all, only the
     * user-facing "install unknown apps" permission a browser/file manager would otherwise need.
     * Sets both the plain and device-wide ("_GLOBALLY") restrictions together since the public
     * docs don't clearly distinguish which one a Device Owner on a single-user device (no separate
     * work profile) actually needs to enforce this - costs nothing to set both.
     *
     * Doesn't, on its own, stop a kid from *opening* an already-installed app that isn't on the
     * allowlist - that's [enforceOnNewPackage]'s job for anything installed after this policy
     * first applied, same as the regular suspend/hide loop above for anything already present
     * ([enforceOnNewPackage] already respects the same overrides independently, since it runs
     * outside this function entirely - see its own doc comment).
     */
    private fun applySideloadRestriction(dpm: DevicePolicyManager, admin: ComponentName, blockSideloading: Boolean) {
        setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, blockSideloading)
        setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, blockSideloading)
    }

    /**
     * Pushes a Chrome enterprise-policy bundle to the kids-mdm-browser fork via Android's
     * managed-configuration mechanism (`DevicePolicyManager.setApplicationRestrictions` ->
     * Chrome's built-in `AppRestrictionsProvider` on the browser side - no browser-side patch
     * needed for this to take effect, only for it to *not* be strippable by the kid). A no-op if
     * the browser isn't installed (`NameNotFoundException`) - safe to call unconditionally on
     * every [apply] cycle, same tolerance pattern as every other per-package call in this file.
     *
     * Scope is deliberately narrow: DNS-based blocking is handled elsewhere (KidVpnService's
     * on-device filter / the server's DNS blocklist), so this only closes the browser-side gaps
     * that would otherwise route around it - Secure DNS (would bypass the DNS filter entirely),
     * Incognito/Guest mode, developer tools and extension installs
     * (both plausible tamper vectors on a kid's device), and the browser's own proxy settings
     * (another potential bypass route). Same "fully open" treatment as every other restriction
     * in [apply] while an override is active - [locked] is false in that case and every value
     * below reverts to Chrome's un-managed default.
     *
     * Policy keys/types follow https://chromeenterprise.google/policies/ as of Chrome 151;
     * verify against `chrome://policy` on the actual device after the first build, since Android
     * app-restriction bundle typing (plain values vs. JSON-encoded strings for object/array
     * policies) isn't unit-testable from here.
     */
    private fun applyBrowserPolicy(dpm: DevicePolicyManager, admin: ComponentName, context: Context, locked: Boolean) {
        try {
            context.packageManager.getApplicationInfo(BROWSER_PACKAGE_NAME, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }

        val bundle = if (!locked) {
            Bundle()
        } else {
            Bundle().apply {
                putString("DnsOverHttpsMode", "off")
                putInt("IncognitoModeAvailability", 1) // 1 = Disabled
                putBoolean("BrowserGuestModeEnabled", false)
                putInt("DeveloperToolsAvailability", 2) // 2 = DeveloperToolsDisallowed
                putStringArray("ExtensionInstallBlocklist", arrayOf("*"))
                // Object-valued policy - Chrome's Android provider expects these JSON-encoded,
                // unlike the scalar policies above (Android restriction bundles have no native
                // nested-object type).
                putString("ProxySettings", """{"ProxyMode":"system"}""")
            }
        }

        try {
            dpm.setApplicationRestrictions(admin, BROWSER_PACKAGE_NAME, bundle)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set browser app restrictions", e)
        }
    }

    /**
     * Suspends/hides a single newly-installed package immediately if it's not on the current
     * allowlist, rather than waiting for the next full [apply] cycle (up to the 5-minute periodic-
     * sync backstop). Called from [com.kidslauncher.mdm.Application]'s `LauncherApps.Callback
     * .onPackageAdded`, which fires the instant Android finishes installing anything - on-device,
     * no network round-trip, regardless of whether the install came from an app store or (if
     * [applySideloadRestriction] hasn't been set, or the app was already present before it was) a
     * sideloaded APK. Deliberately narrower than a full [apply] pass: only this one package needs
     * checking, so there's no reason to re-touch every other controllable package's state on every
     * single install event.
     */
    fun enforceOnNewPackage(context: Context, packageName: String) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        if (packageName == context.packageName) return
        if (OfflineOverride.isActive() || LauncherPreferences.mdm().restrictionsPaused()) return

        val allowedPackages = cachedPolicy()?.allowlist?.takeIf { it.isNotEmpty() }?.toSet() ?: return
        if (packageName in allowedPackages) return

        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        try {
            val notSuspended = dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            if (!notSuspended.isNullOrEmpty()) {
                Log.w(LOG_TAG, "Platform refused to suspend newly-installed $packageName")
            }
            val hiddenOk = dpm.setApplicationHidden(admin, packageName, true)
            if (!hiddenOk) {
                Log.w(LOG_TAG, "Platform refused to hide newly-installed $packageName")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to suspend newly-installed $packageName", e)
        }
    }

    private fun setRestriction(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        restriction: String,
        enabled: Boolean,
    ) {
        try {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction)
            } else {
                dpm.clearUserRestriction(admin, restriction)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to ${if (enabled) "add" else "clear"} restriction $restriction", e)
        }
    }

    /**
     * The regular "set as default launcher" flow ([com.kidslauncher.mdm.setDefaultHomeScreen]) is
     * just a user-revocable preference - a reinstall/reboot race, or a kid holding down the home
     * button, can fall back to another HOME-capable app (e.g. the OS's own stock launcher) if one
     * is installed. As device owner we can pin this instead.
     *
     * LOCAL-DEVIATION: upstream pins unconditionally. Here the pinning follows [kioskDesired], so
     * that One UI Home stays the home screen while kiosk is off - Samsung Kids is launched from it
     * and is meaningless without it. Releasing has to be an active
     * [DevicePolicyManager.clearPackagePersistentPreferredActivities] call rather than just
     * skipping the setter, since a device pinned by an earlier build (or by an earlier cycle with
     * kiosk on) stays pinned until something explicitly clears it. Both branches are idempotent and
     * individually guarded, matching the defensive style of the rest of this file.
     */
    private fun enforceDefaultHome(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
        kioskDesired: Boolean,
    ) {
        if (!kioskDesired) {
            try {
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Failed to clear persistent preferred HOME activity", e)
            }
            return
        }

        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        try {
            dpm.addPersistentPreferredActivity(
                admin, filter, ComponentName(context, HomeActivity::class.java)
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set persistent preferred HOME activity", e)
        }
    }
}
