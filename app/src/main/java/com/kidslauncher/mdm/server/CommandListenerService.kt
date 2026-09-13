package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.COMMAND_LISTENER_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

private const val LOG_TAG = "CommandListenerService"
private const val INITIAL_RECONNECT_DELAY_MS = 5_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L
private const val NOT_ENROLLED_RETRY_DELAY_MS = 30_000L
private const val PERIODIC_SYNC_INTERVAL_MS = 5 * 60 * 1000L

/**
 * LOCAL-DEVIATION: how often today's screen-time counters are refreshed and the budget re-applied -
 * see [ScreenTimeTracker]. A minute is the resolution the enforcement actually has: a budget can be
 * overrun by at most this long before the apps go away. Polling harder buys nothing a kid or parent
 * would notice and costs battery all day.
 *
 * Android offers `UsageStatsManager.registerAppUsageObserver`, which is exactly this callback for
 * free - but it needs OBSERVE_APP_USAGE, held only by the app with ROLE_SYSTEM_WELLBEING. Without a
 * system app there is no way to get it, so polling it is.
 */
private const val SCREEN_TIME_TICK_MS = 60 * 1000L

/**
 * Holds a long-lived SSE connection open to `/api/devices/commands/stream` so Find My Device's
 * ring/lock/stop-ring/wipe arrive in ~1s instead of waiting for the periodic sync below - a
 * supplement to it, not a replacement: every event received here is a content-free nudge, not the
 * command payload itself, and just triggers an immediate [performMdmSync] early, reusing the
 * exact same policy-fetch/dispatch logic as a normal scheduled sync (see
 * `handlers::device_api::commands_stream` on the server for the matching half of this).
 *
 * Also drives the periodic backstop sync directly, via its own timer - this used to be a separate
 * WorkManager `OneTimeWorkRequest` chain, but confirmed live that it could go quiet for hours on
 * an idle phone with the screen off, most likely Android's Doze/battery-optimization deferring
 * the underlying JobScheduler dispatch (WorkManager isn't exempt from that on its own). This
 * service already pays the cost of an always-on foreground service - exempt from Doze by design,
 * that's the entire point of a foreground service - to hold the SSE connection open, so there's no
 * reason to run a second, less-reliable scheduling mechanism alongside it for the periodic case.
 *
 * A foreground service, not a plain background connection - Android would otherwise throttle or
 * kill a long-lived socket once the app isn't in the foreground, which would defeat the entire
 * point (a lost/screen-off phone is exactly when this matters most). The tradeoff, and there's no
 * way around it, is Android's own mandatory persistent notification for any foreground service -
 * kept at MIN importance and silent, since it's not meant to draw attention the way the ring
 * notification deliberately does.
 *
 * Since this service already pays that foreground-service cost, it also optionally owns
 * [UnifiedPushRelay] (a UnifiedPush distributor for *other* apps on the device, opt-in from
 * Settings, off by default) - one persistent connection/notification doing two jobs instead of
 * a second dedicated distributor app running its own.

 */
class CommandListenerService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var eventSource: EventSource? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopped = false

    /**
     * Built fresh on every [connect] call rather than cached.
     *
     * LOCAL-DEVIATION: upstream also attached the embedded tsnet SOCKS5 proxy here, which was the
     * original reason this could not be a cached client. tsnet is removed in this fork (the server
     * is reachable over the public internet), so this now only builds a plain client - kept
     * per-call anyway, since [connect] retries via [scheduleReconnect] and rebuilding costs
     * nothing.
     */
    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            // SSE connections are meant to stay open indefinitely - a normal read timeout would
            // tear this down and force a reconnect every time it elapsed.
            .readTimeout(0, TimeUnit.MILLISECONDS)
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must be called promptly after a startForegroundService() launch, before anything else -
        // shown unconditionally (even before enrollment completes) since Android crashes the app
        // if this doesn't happen in time; connect() below handles "not enrolled yet" on its own by
        // retrying rather than needing this to wait for that state first.
        startForeground(COMMAND_LISTENER_NOTIFICATION_ID, buildNotification())
        connect()
        schedulePeriodicSync()
        scheduleScreenTimeTick()
        // Piggybacks on this same foreground service/notification rather than running as a
        // second one - see UnifiedPushRelay's own doc comment for why. Off by default (a parent
        // has to opt in from Settings), so this is a no-op on a device where that's never been
        // touched.
        if (LauncherPreferences.mdm().unifiedpushDistributorEnabled()) {
            UnifiedPushRelay.start(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        eventSource?.cancel()
        UnifiedPushRelay.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.baseline_settings_24)
            .setContentTitle(getString(R.string.notification_listener_title))
            .setContentText(getString(R.string.notification_listener_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun connect() {
        if (stopped) return

        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()
        val deviceToken = mdm.deviceToken()
        if (serverUrl.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            handler.postDelayed({ connect() }, NOT_ENROLLED_RETRY_DELAY_MS)
            return
        }

        val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        val request = Request.Builder()
            .url("${base}api/devices/commands/stream")
            .header("Authorization", "Bearer $deviceToken")
            .build()

        eventSource = EventSources.createFactory(buildClient()).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.i(LOG_TAG, "Command stream connected")
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    Log.i(LOG_TAG, "Command stream nudge received, syncing early")
                    scope.launch { performMdmSync(applicationContext) }
                }

                override fun onClosed(eventSource: EventSource) {
                    Log.i(LOG_TAG, "Command stream closed, reconnecting")
                    scheduleReconnect()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    Log.w(LOG_TAG, "Command stream connection failed, reconnecting", t)
                    scheduleReconnect()
                }
            },
        )
    }

    private fun schedulePeriodicSync() {
        if (stopped) return
        handler.postDelayed(
            {
                scope.launch { performMdmSync(applicationContext) }
                schedulePeriodicSync()
            },
            PERIODIC_SYNC_INTERVAL_MS,
        )
    }

    /**
     * LOCAL-DEVIATION: the budget's own heartbeat. Runs here rather than as a WorkManager job for
     * the same reason the periodic sync moved here - this service is already exempt from Doze,
     * which a JobScheduler-backed worker is not, and a counter that stops counting while the screen
     * is off would hand back the rest of the day for free.
     *
     * Re-applies against the *cached* policy, so it needs no network at all: the whole point of
     * counting on the device is that a budget survives the server being unreachable.
     */
    private fun scheduleScreenTimeTick() {
        if (stopped) return
        handler.postDelayed(
            {
                scope.launch {
                    try {
                        ScreenTimeTracker.poll(applicationContext)
                        AppEnforcer.apply(applicationContext, cachedPolicy())
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Screen-time tick failed", e)
                    }
                }
                scheduleScreenTimeTick()
            },
            SCREEN_TIME_TICK_MS,
        )
    }

    private fun scheduleReconnect() {
        if (stopped) return
        handler.postDelayed({ connect() }, reconnectDelayMs)
        // Simple exponential backoff so a server that's genuinely down doesn't get hammered with
        // reconnect attempts every 5 seconds forever.
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    companion object {
        /** Call once at app startup - safe to call repeatedly, Android no-ops a redundant start. */
        fun start(context: Context) {
            val intent = Intent(context, CommandListenerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
