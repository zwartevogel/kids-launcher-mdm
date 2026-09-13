package com.kidslauncher.mdm.server

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable

private const val LOG_TAG = "ScreenTimeTracker"

/**
 * How far back to read events when there is no cursor yet (first run, or the counters were reset).
 * Android keeps roughly a week of events; a day is enough to reconstruct today's usage and avoids
 * walking a pile of history that gets discarded by the day filter anyway.
 */
private const val COLD_START_LOOKBACK_MS = 24 * 60 * 60 * 1000L

/**
 * Ignore a gap larger than this when closing an open foreground interval. Android drops
 * ACTIVITY_PAUSED in some situations (confirmed with Samsung's own Modes/Game Booster taking over
 * the foreground), which would otherwise bill a single app for every hour since it was last seen.
 */
private const val MAX_PLAUSIBLE_INTERVAL_MS = 2 * 60 * 60 * 1000L

/** UsageEvents.Event constants that are not in the public API surface on every level. */
private const val EVENT_SCREEN_INTERACTIVE = 15
private const val EVENT_SCREEN_NON_INTERACTIVE = 16

/**
 * LOCAL-DEVIATION: counts how long the device and each app have actually been used today, from
 * Android's own [UsageStatsManager]. Upstream has no notion of a budget at all - its windows say
 * *when* the phone may be used, never for how long.
 *
 * Everything here is local: the usage database lives in `/data/system/usagestats` and nothing is
 * sent to Google or Samsung. Digital Wellbeing and Samsung's own screen-time UI read the same
 * source, so neither app needs to be present (both may be debloated).
 *
 * Counting is done from the event stream rather than from
 * `queryUsageStats(...).totalTimeInForeground`: the aggregate is unreliable across reboots and
 * split-screen, and its day buckets roll over at a time we do not control. Reconstructing
 * intervals from ACTIVITY_RESUMED/PAUSED costs a cursor and a little arithmetic and is exact.
 *
 * **Permission.** `PACKAGE_USAGE_STATS` is an appop, not a runtime permission - a Device Owner
 * cannot grant it to itself through DevicePolicyManager, and `pm grant` refuses it. It is granted
 * once per install from a machine with adb:
 *
 *     adb shell appops set com.kidslauncher.mdm.debug android:get_usage_stats allow
 *
 * Note the `.debug` suffix - CI publishes the debug build, and the command silently does nothing
 * against the release application id. That survives reboots but not a reinstall or `pm clear`. [hasUsageAccess] reports whether it is
 * currently held, and every read degrades to "no usage recorded" without it - which means budgets
 * never block anything rather than blocking everything, the safe direction for a permission that
 * can quietly disappear.
 */
object ScreenTimeTracker {

    /** Today's usage so far, in seconds. */
    data class Usage(
        val day: String,
        val screenSeconds: Long,
        val perPackageSeconds: Map<String, Long>,
    )

    /**
     * What the budgets say about right now, resolved against [Usage] and the day's grants. Kept as
     * plain data so [KidModeEnforcer.isAppAllowedNow] stays a pure function with no Android or
     * clock dependencies of its own.
     */
    data class BudgetState(
        /** True when the whole-device daily budget is used up. */
        val deviceExhausted: Boolean,
        /** Packages whose own daily budget is used up. */
        val exhaustedPackages: Set<String>,
    ) {
        companion object {
            /** No budget configured, or no way to measure one - nothing is ever blocked by budget. */
            val UNRESTRICTED = BudgetState(deviceExhausted = false, exhaustedPackages = emptySet())
        }
    }

    @Serializable
    private data class State(
        val day: String = "",
        val cursorMs: Long = 0,
        val screenSeconds: Long = 0,
        val perPackageSeconds: Map<String, Long> = emptyMap(),
        /** The app in the foreground when the last poll ended, and since when - an interval that is
         * still open and gets closed by a later event or by [usage]'s own "up to now" tail. */
        val openPackage: String? = null,
        val openSinceMs: Long = 0,
        /** Whether the screen was on when the last poll ended, and since when. */
        val screenOnSinceMs: Long = 0,
    )

    /** `SimpleDateFormat` is not thread-safe; this object is touched from the service's timer and
     * from AppEnforcer, so it gets a fresh one per call rather than a shared instance. */
    private fun dayOf(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timeMs))

    /**
     * True when this app currently holds usage access. Queried straight from AppOpsManager rather
     * than inferred from an empty result: a genuinely idle minute also returns no events, and
     * treating that as "permission missing" would silently stop enforcing budgets on a quiet phone.
     */
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads every usage event since the last call and folds it into today's counters. Cheap enough
     * to call once a minute: it only ever walks events newer than the stored cursor.
     *
     * Safe to call concurrently in the sense that it never throws - a lost or duplicated poll costs
     * at most the accuracy of one interval, never a crash.
     */
    @Synchronized
    fun poll(context: Context) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        var state = load()

        // A new day resets the counters. Deliberately keyed off the *local* date rather than a
        // fixed 24h window: "two hours a day" means a calendar day to a parent.
        val today = dayOf(now)
        if (state.day != today) {
            state = State(day = today, cursorMs = now - COLD_START_LOOKBACK_MS)
        }
        if (state.cursorMs <= 0) {
            state = state.copy(cursorMs = now - COLD_START_LOOKBACK_MS)
        }

        val events = try {
            usm.queryEvents(state.cursorMs, now)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not read usage events - is android:get_usage_stats granted?", e)
            null
        } ?: return

        var screenSeconds = state.screenSeconds
        val perPackage = state.perPackageSeconds.toMutableMap()
        var openPackage = state.openPackage
        var openSince = state.openSinceMs
        var screenOnSince = state.screenOnSinceMs
        var lastEventMs = state.cursorMs

        fun closeForeground(atMs: Long) {
            val pkg = openPackage ?: return
            val elapsed = atMs - openSince
            if (elapsed in 1..MAX_PLAUSIBLE_INTERVAL_MS && dayOf(openSince) == today) {
                perPackage[pkg] = (perPackage[pkg] ?: 0) + elapsed / 1000
            }
            openPackage = null
            openSince = 0
        }

        fun closeScreen(atMs: Long) {
            if (screenOnSince <= 0) return
            val elapsed = atMs - screenOnSince
            if (elapsed in 1..MAX_PLAUSIBLE_INTERVAL_MS && dayOf(screenOnSince) == today) {
                screenSeconds += elapsed / 1000
            }
            screenOnSince = 0
        }

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            lastEventMs = maxOf(lastEventMs, ts)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // Closing the previous app on the next RESUMED as well as on its own PAUSED is
                    // what makes a dropped PAUSED harmless - see MAX_PLAUSIBLE_INTERVAL_MS.
                    closeForeground(ts)
                    openPackage = event.packageName
                    openSince = ts
                    if (screenOnSince <= 0) screenOnSince = ts
                }

                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (event.packageName == openPackage) closeForeground(ts)
                }

                EVENT_SCREEN_INTERACTIVE -> {
                    if (screenOnSince <= 0) screenOnSince = ts
                }

                EVENT_SCREEN_NON_INTERACTIVE -> {
                    closeForeground(ts)
                    closeScreen(ts)
                }
            }
        }

        save(
            State(
                day = today,
                cursorMs = maxOf(lastEventMs, state.cursorMs),
                screenSeconds = screenSeconds,
                perPackageSeconds = perPackage,
                openPackage = openPackage,
                openSinceMs = openSince,
                screenOnSinceMs = screenOnSince,
            )
        )
    }

    /**
     * Today's usage, including the interval currently in progress. The open interval is added here
     * rather than folded into the stored counters so that repeated reads between two polls stay
     * consistent instead of compounding.
     */
    @Synchronized
    fun usage(): Usage {
        val state = load()
        val now = System.currentTimeMillis()
        val today = dayOf(now)
        if (state.day != today) return Usage(today, 0, emptyMap())

        var screenSeconds = state.screenSeconds
        if (state.screenOnSinceMs > 0) {
            val elapsed = now - state.screenOnSinceMs
            if (elapsed in 1..MAX_PLAUSIBLE_INTERVAL_MS) screenSeconds += elapsed / 1000
        }

        val perPackage = state.perPackageSeconds.toMutableMap()
        val openPackage = state.openPackage
        if (openPackage != null && state.openSinceMs > 0) {
            val elapsed = now - state.openSinceMs
            if (elapsed in 1..MAX_PLAUSIBLE_INTERVAL_MS) {
                perPackage[openPackage] = (perPackage[openPackage] ?: 0) + elapsed / 1000
            }
        }

        return Usage(today, screenSeconds, perPackage)
    }

    /**
     * Resolves [policy]'s budgets against today's usage and today's grants.
     *
     * Returns [BudgetState.UNRESTRICTED] when there is nothing to enforce *or* nothing to measure
     * with: without usage access a budget cannot be honestly evaluated, and silently suspending
     * every app because a permission went missing is far worse than letting the windows alone
     * decide until someone notices.
     */
    fun budgetState(context: Context, policy: PolicyResponse?, now: Calendar): BudgetState {
        if (policy == null) return BudgetState.UNRESTRICTED

        val hasDeviceBudget = policy.dailyScreenMinutes != null
        val appBudgets = policy.appRules.filter { it.dailyMinutes != null }
        if (!hasDeviceBudget && appBudgets.isEmpty()) return BudgetState.UNRESTRICTED

        if (!hasUsageAccess(context)) {
            Log.w(LOG_TAG, "Budgets are configured but usage access is missing - not enforcing them")
            return BudgetState.UNRESTRICTED
        }

        val usage = usage()
        val today = dayOf(now.timeInMillis)

        // Grants are additive and may be negative; only today's count. The device-wide ones (no
        // package) extend the whole-device budget, a scoped one extends just that app's.
        val deviceGrantMinutes = policy.timeGrants
            .filter { it.day == today && it.packageName == null }
            .sumOf { it.minutes }

        val deviceExhausted = policy.dailyScreenMinutes?.let { budget ->
            usage.screenSeconds >= (budget + deviceGrantMinutes).coerceAtLeast(0) * 60L
        } ?: false

        val exhausted = appBudgets.mapNotNullTo(mutableSetOf()) { rule ->
            val budget = rule.dailyMinutes ?: return@mapNotNullTo null
            val grantMinutes = policy.timeGrants
                .filter { it.day == today && it.packageName == rule.packageName }
                .sumOf { it.minutes }
            val used = usage.perPackageSeconds[rule.packageName] ?: 0
            if (used >= (budget + grantMinutes).coerceAtLeast(0) * 60L) rule.packageName else null
        }

        return BudgetState(deviceExhausted, exhausted)
    }

    private fun load(): State {
        val raw = LauncherPreferences.mdm().screenTimeState()
        if (raw.isNullOrBlank()) return State()
        return try {
            ServerJson.decodeFromString<State>(raw)
        } catch (e: Exception) {
            State()
        }
    }

    private fun save(state: State) {
        try {
            LauncherPreferences.mdm().screenTimeState(ServerJson.encodeToString(state))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Could not persist screen-time counters", e)
        }
    }
}
