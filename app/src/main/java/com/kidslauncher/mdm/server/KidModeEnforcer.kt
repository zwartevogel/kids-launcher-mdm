package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.AppRule
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.util.Calendar

enum class LockReason {
    NONE, SCREEN_TIME, BEDTIME
}

/**
 * Pure decision logic for whether the device should currently be locked - no Android or network
 * dependencies, so it keeps working from the last-cached policy even when the server is
 * unreachable.
 */
object KidModeEnforcer {

    fun evaluate(policy: PolicyResponse?, now: Calendar): LockReason {
        if (policy == null) return LockReason.NONE

        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        if (isRestricted(minuteOfDay, policy.bedtimeStartMinutes, policy.bedtimeEndMinutes)) {
            return LockReason.BEDTIME
        }

        val isWeekend = now.get(Calendar.DAY_OF_WEEK).let {
            it == Calendar.SATURDAY || it == Calendar.SUNDAY
        }
        val allowedStart = if (isWeekend) policy.weekendStartMinutes else policy.weekdayStartMinutes
        val allowedEnd = if (isWeekend) policy.weekendEndMinutes else policy.weekdayEndMinutes

        return if (isOutsideAllowedWindow(minuteOfDay, allowedStart, allowedEnd)) {
            LockReason.SCREEN_TIME
        } else {
            LockReason.NONE
        }
    }


    /**
     * LOCAL-DEVIATION: whether one specific app may be used right now. Upstream has no such notion -
     * an allowlisted app is usable whenever the device is, and the windows below only drove the
     * launcher's own lock screen. With One UI Home kept as the home screen (Samsung Kids needs it)
     * that lock screen is rarely on top, so scheduling has to drive *suspension* instead, which
     * holds no matter which launcher is running. See [AppRule].
     *
     * The tiers are nested, so a stricter one is always a subset of a looser one:
     *   never         - not usable at all (installed, but not for the kids)
     *   window_only   - window start .. window end                      (the default)
     *   until_bedtime - window start .. bedtime start
     *   always        - no time restriction, bedtime included
     *
     * A rule may carry its own [AppRule.startMinutes]/[AppRule.endMinutes], which replace the
     * device's general window for that day; null means "use the general window". A missing rule
     * means `window_only` on the general window - the strict default, so a newly installed app is
     * never quietly looser than the ones already configured.
     */
    fun isAppAllowedNow(rule: AppRule?, policy: PolicyResponse, now: Calendar): Boolean {
        when (rule?.tier) {
            AppRule.TIER_NEVER -> return false
            "always" -> return true
        }

        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (isRestricted(minuteOfDay, policy.bedtimeStartMinutes, policy.bedtimeEndMinutes)) {
            return false
        }

        val isWeekend = now.get(Calendar.DAY_OF_WEEK).let {
            it == Calendar.SATURDAY || it == Calendar.SUNDAY
        }
        val start = rule?.startMinutes
            ?: if (isWeekend) policy.weekendStartMinutes else policy.weekdayStartMinutes
        val end = rule?.endMinutes
            ?: if (isWeekend) policy.weekendEndMinutes else policy.weekdayEndMinutes

        if (rule?.tier == "until_bedtime") {
            if (start == null) return true
            val bedtimeStart = policy.bedtimeStartMinutes ?: return minuteOfDay >= start
            return inWindow(minuteOfDay, start, bedtimeStart)
        }

        // window_only, and anything unrecognised - fail strict rather than open.
        return !isOutsideAllowedWindow(minuteOfDay, start, end)
    }

    /** True while [minuteOfDay] falls inside a restricted [start]-[end] window (e.g. bedtime).
     * Handles overnight wraparound (start > end, e.g. 21:00-07:00). Null or start == end means
     * "no restriction". */
    private fun isRestricted(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return inWindow(minuteOfDay, start, end)
    }

    /** True while [minuteOfDay] falls outside an allowed [start]-[end] window (e.g. weekday screen
     * time). Handles overnight wraparound. Null or start == end means "always allowed". */
    private fun isOutsideAllowedWindow(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return !inWindow(minuteOfDay, start, end)
    }

    private fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean {
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }
}
