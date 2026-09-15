package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "BootReceiver"

/**
 * LOCAL-DEVIATION: brings enforcement back up after a reboot.
 *
 * Nothing did this before. [CommandListenerService] is only ever started from
 * `Application.onCreate`, i.e. whenever the app's process happens to start - and in this
 * deployment that is not a given, because One UI Home stays the home screen (Samsung Kids needs
 * it), so this launcher is not what runs at boot.
 *
 * Suspension itself survives a reboot, so the failure mode was one-sided but real: whatever was
 * blocked stayed blocked, including across a day boundary. A phone switched off at bedtime with
 * the day's budget spent came back up the next morning with those apps still suspended and nothing
 * scheduled to re-evaluate them - a new day with yesterday's verdict still applied.
 *
 * Hence the immediate poll-and-apply rather than only starting the service: the service's own tick
 * would get there eventually, but "eventually" is the wrong answer to a child asking why an app is
 * still blocked.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(LOG_TAG, "Boot completed, restarting enforcement")
        CommandListenerService.start(context)

        // goAsync() is deliberately not used: the work below is not ordered against anything and a
        // receiver may only hold the broadcast open for ~10s, which a sync can exceed on a slow
        // network at boot. The service started above owns the retry either way.
        // Alarms do not survive a reboot, so this is also the only thing that re-arms the
        // enforcement boundary - see EnforcementScheduler.
        EnforcementScheduler.evaluateNow(context)

        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                performMdmSync(appContext)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Post-boot sync failed", e)
            }
        }
    }
}
