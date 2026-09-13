package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * LOCAL-DEVIATION: upstream has no per-app scheduling at all - an allowlisted app is usable
 * whenever the device is, and the weekday/weekend/bedtime windows only drive the launcher's own
 * lock screen. That lock screen is useless here: this deployment deliberately keeps One UI Home as
 * the home screen (Samsung Kids needs it), so the launcher is rarely in the foreground and a kid
 * simply starts apps from the Samsung home screen instead. Suspension is the only enforcement that
 * holds regardless of which launcher is running, so the schedule has to feed *that* instead.
 *
 * [tier] is one of `never`, `window_only`, `until_bedtime` or `always` - see
 * [com.kidslauncher.mdm.server.KidModeEnforcer.isAppAllowedNow] for exactly what each covers.
 * [startMinutes]/[endMinutes] override the device's general window for this one app; null means
 * "use the general window for today".
 */
@Serializable
data class AppRule(
    val packageName: String,
    val tier: String = "window_only",
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
) {
    companion object {
        /** Allowed at no point in the day - the only tier that also hides the app's icon. */
        const val TIER_NEVER = "never"
    }
}
