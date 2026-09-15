package com.kidslauncher.mdm.server

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "EnforcementScheduler"
private const val ALARM_REQUEST_CODE = 4711

/**
 * LOCAL-DEVIATION: decides *when* the enforcement decision has to be re-taken, so that it no longer
 * rides on the sync.
 *
 * Until now both hung off one [android.os.Handler] chain inside [CommandListenerService]: the same
 * timer fetched policy and re-applied it. That coupling has no technical reason - enforcement needs
 * no network at all, it runs off the cached policy - and it cost real enforcement, because whenever
 * the service was stopped or the device slept, blocking stopped along with syncing.
 *
 * Three clocks now, deliberately independent:
 *
 *  - **Enforcement**: this class. An alarm at the next moment the verdict *could* change, plus the
 *    event triggers (screen on, unlock, boot, a freshly fetched policy).
 *  - **Sync**: the service's own hourly timer, the SSE nudge, and the manual button. If it fails,
 *    nothing stops being enforced - a changed rule merely lands later.
 *  - **Screen-time counting**: ticks only while the screen is on, because that is the only time
 *    usage accrues. See [CommandListenerService].
 *
 * A missed alarm is survivable in a way a missed sync never was: suspension is a state Android
 * holds, not something re-asserted each cycle. What is blocked stays blocked; only "should become
 * blocked" can lag, and the screen-on trigger catches that before anyone can use the phone.
 */
object EnforcementScheduler {

    /**
     * The next wall-clock moment at which [KidModeEnforcer.isAppAllowedNow] could return something
     * different, ignoring budgets - those are driven by usage, not by the clock, and are handled by
     * the screen-on tick instead.
     *
     * Every edge counts: the general window for today and tomorrow (a weekday and a weekend day
     * can differ), bedtime's start and end, and every per-app window. Midnight is always included,
     * since that is when the day's budgets and grants reset even if no window moves.
     *
     * Pure, so it can be reasoned about and tested without a device or an AlarmManager.
     */
    fun nextBoundary(policy: PolicyResponse?, now: Calendar): Calendar {
        val edges = sortedSetOf<Int>()
        // Midnight: budgets and grants are per calendar day, so the verdict can change there even
        // when no window does.
        edges.add(0)

        policy?.let { p ->
            listOfNotNull(
                p.weekdayStartMinutes, p.weekdayEndMinutes,
                p.weekendStartMinutes, p.weekendEndMinutes,
                p.bedtimeStartMinutes, p.bedtimeEndMinutes,
            ).forEach { edges.add(it) }

            p.appRules.forEach { rule ->
                rule.startMinutes?.let { edges.add(it) }
                rule.endMinutes?.let { edges.add(it) }
            }
        }

        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val next = edges.firstOrNull { it > nowMinute }

        return (now.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (next != null) {
                set(Calendar.HOUR_OF_DAY, next / 60)
                set(Calendar.MINUTE, next % 60)
            } else {
                // Nothing left today - the first edge of tomorrow, which is midnight at worst.
                add(Calendar.DAY_OF_YEAR, 1)
                val first = edges.first()
                set(Calendar.HOUR_OF_DAY, first / 60)
                set(Calendar.MINUTE, first % 60)
            }
        }
    }

    /** Arms a single alarm at [nextBoundary]. Replaces any alarm already set. */
    fun schedule(context: Context, policy: PolicyResponse?) {
        val at = nextBoundary(policy, Calendar.getInstance())
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            // setAndAllowWhileIdle, not setExact*: an exact alarm needs SCHEDULE_EXACT_ALARM, which
            // is not granted by default on API 31+, and being a few minutes late to close a window
            // is not worth that dependency. It still fires through Doze, which the Handler chain
            // this replaces never did.
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                at.timeInMillis,
                pendingIntent(context),
            )
            Log.i(LOG_TAG, "Next enforcement check at ${at.time}")
        } catch (e: Exception) {
            // Losing the alarm costs timeliness, never correctness - the screen-on trigger and the
            // hourly sync both re-evaluate anyway.
            Log.w(LOG_TAG, "Could not schedule the enforcement alarm", e)
        }
    }

    /**
     * Re-takes the decision from the cached policy and re-arms the alarm. No network: this is
     * exactly what has to keep working when the server is unreachable.
     */
    fun evaluateNow(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ScreenTimeTracker.poll(appContext)
                val policy = cachedPolicy()
                AppEnforcer.apply(appContext, policy)
                schedule(appContext, policy)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Enforcement run failed", e)
            }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, EnforcementAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Fires at the boundary computed by [EnforcementScheduler.nextBoundary]. */
class EnforcementAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(LOG_TAG, "Boundary reached, re-evaluating")
        EnforcementScheduler.evaluateNow(context)
    }
}
