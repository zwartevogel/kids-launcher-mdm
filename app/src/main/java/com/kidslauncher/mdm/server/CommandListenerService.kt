package com.kidslauncher.mdm.server

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
// LOCAL-DEVIATION: an hour, not five minutes. This timer now only *fetches* policy - enforcement
// runs on its own schedule (see EnforcementScheduler), so a late sync delays a changed rule rather
// than suspending enforcement. The SSE nudge covers anything a parent wants applied right away,
// and the child's own button covers the rest.
private const val PERIODIC_SYNC_INTERVAL_MS = 60 * 60 * 1000L

/**
 * LOCAL-DEVIATION: how often the screen-time counters are folded up *while the screen is on* - see
 * [ScreenTimeTracker]. This interval is the budget's enforcement resolution: a budget can be
 * overrun by at most this long before the apps go away.
 *
 * It only runs with the screen on, because that is the only time screen time accrues. That removes
 * the whole question of what a timer does in deep sleep: there is nothing to count, so the tick is
 * stopped on SCREEN_OFF after one final fold-up and restarted on SCREEN_ON. Two minutes while
 * actually in use is cheap and tight enough that no one notices the slack.
 *
 * Counting is not sampled, so the interval does not affect accuracy - every interval is
 * reconstructed exactly from the event timestamps whenever we happen to look.
 *
 * Android offers `UsageStatsManager.registerAppUsageObserver`, which is this callback for free -
 * but it needs OBSERVE_APP_USAGE, held only by the app with ROLE_SYSTEM_WELLBEING. Without a system
 * app there is no way to get it, so polling it is.
 */
private const val SCREEN_TIME_TICK_MS = 2 * 60 * 1000L

/**
 * LOCAL-DEVIATION: shortest gap between two wake-triggered syncs - see [wakeReceiver].
 *
 * The screen comes on for every notification, so without a floor a chatty afternoon would sync
 * dozens of times. A minute is well under how long a phone stays awake once someone actually picks
 * it up, so a real wake still syncs immediately.
 */
private const val WAKE_SYNC_MIN_GAP_MS = 60 * 1000L

/**
 * LOCAL-DEVIATION: shortest gap between two syncs triggered by the command stream reconnecting.
 * A flaky network can reconnect over and over, and each sync costs a policy fetch and a status
 * report; a minute still means a lost phone is checked for pending commands the moment it is
 * genuinely back.
 */
private const val STREAM_RECONNECT_SYNC_MIN_GAP_MS = 60 * 1000L

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
    /** Last type actually claimed, so the promotion only logs when it genuinely changes. */
    private var promotedWithLocation = false
    private val handler = Handler(Looper.getMainLooper())
    private var eventSource: EventSource? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var stopped = false
    /** `elapsedRealtime` of the last wake-triggered sync - counts through sleep, unlike uptime. */
    private var lastWakeSyncAt = 0L
    /** Whether the budget tick is currently armed - it only runs while the screen is on. */
    private var screenTimeTicking = false
    /** `elapsedRealtime` of the last sync triggered by the stream reconnecting. */
    private var lastStreamSyncAt = 0L

    /**
     * LOCAL-DEVIATION: syncs the moment the device wakes up.
     *
     * The periodic timer below is a [Handler], and `postDelayed` runs on `uptimeMillis`, which does
     * not advance in deep sleep - a foreground service is exempt from app-standby but that does not
     * keep the CPU awake. So a phone in a pocket simply stops syncing, which is fine in itself
     * (nobody is using it, so no policy decision is pending) but leaves it stale at the one moment
     * that matters: when it is picked up again.
     *
     * Deliberately not an AlarmManager wake-up. Waking a sleeping phone every few minutes to ask a
     * server whether anything changed costs battery all day to answer "no"; reacting to a wake that
     * was going to happen anyway costs nothing.
     *
     * SCREEN_ON fires before the keyguard, USER_PRESENT after unlocking - both are registered
     * because a screen-on alone already justifies refreshing policy, while an unlock is the
     * strongest signal the device is about to be used.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // One last fold-up so the interval that just ended is counted, then stop ticking:
                // no screen, no screen time. Enforcement keeps its own alarm either way.
                stopScreenTimeTick()
                scope.launch {
                    try {
                        ScreenTimeTracker.poll(applicationContext)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Final screen-time fold-up failed", e)
                    }
                }
                return
            }

            startScreenTimeTick()

            val since = SystemClock.elapsedRealtime() - lastWakeSyncAt
            if (lastWakeSyncAt != 0L && since < WAKE_SYNC_MIN_GAP_MS) return
            lastWakeSyncAt = SystemClock.elapsedRealtime()

            Log.i(LOG_TAG, "Woke on ${intent?.action}, re-evaluating and syncing")
            scope.launch {
                try {
                    // Enforcement first and unconditionally: it needs no network, and the counters
                    // it reads have been frozen for as long as the device slept.
                    ScreenTimeTracker.poll(applicationContext)
                    val cached = cachedPolicy()
                    AppEnforcer.apply(applicationContext, cached)
                    EnforcementScheduler.schedule(applicationContext, cached)
                    performMdmSync(applicationContext)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Wake sync failed", e)
                }
            }
        }
    }

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
        promoteForegroundType()
        connect()
        schedulePeriodicSync()
        // Enforcement gets its own alarm rather than riding this service's timer - see
        // EnforcementScheduler. Armed here so a freshly started service always has one pending,
        // and re-armed after every evaluation.
        EnforcementScheduler.schedule(applicationContext, cachedPolicy())
        // The screen is on if the service is being started by a user action; if not, the next
        // SCREEN_ON arms it. Starting it here covers the boot case, where no SCREEN_ON follows.
        startScreenTimeTick()
        ContextCompat.registerReceiver(
            this,
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            // Both are protected system broadcasts - nothing but the OS can send them, so the
            // receiver has no reason to be visible to other apps.
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        try {
            unregisterReceiver(wakeReceiver)
        } catch (e: IllegalArgumentException) {
            // Never registered, or already gone - nothing to undo.
        }
        handler.removeCallbacksAndMessages(null)
        eventSource?.cancel()
        UnifiedPushRelay.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * LOCAL-DEVIATION: (re)enters the foreground with the widest service type this app is
     * currently *allowed* to claim.
     *
     * The sync that collects a location fix runs inside this service, and on API 34 a backgrounded
     * app only reaches LocationManager through a foreground service of type `location`. Running as
     * `dataSync` alone is why every provider returned null forever and `device_locations` stayed
     * empty even after a forced fix.
     *
     * The type cannot simply be hardcoded: `startForeground` throws SecurityException when a
     * declared type's permission is missing, and a foreground service that throws in onCreate is a
     * boot loop - the exact failure mode this project has hit before. So the location type is
     * claimed only while the permission is actually held, and this is called again on every
     * periodic cycle, which is what lets the service pick the type up once Device-Owner self-grant
     * has landed (on a fresh device the first sync happens after this service already started).
     *
     * Calling it repeatedly is cheap and idempotent: on an already-foreground service it updates
     * the type and refreshes the same notification rather than starting anything new.
     */
    private fun promoteForegroundType() {
        val hasLocation = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val type = if (hasLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        try {
            ServiceCompat.startForeground(
                this,
                COMMAND_LISTENER_NOTIFICATION_ID,
                buildNotification(),
                type,
            )
            if (hasLocation != promotedWithLocation) {
                Log.i(LOG_TAG, "Foreground service type now ${if (hasLocation) "dataSync|location" else "dataSync"}")
                promotedWithLocation = hasLocation
            }
        } catch (e: Exception) {
            // Never let this kill the service: without the notification Android stops us anyway,
            // but crashing out of onCreate would take the SSE connection, the periodic sync and the
            // screen-time tick with it.
            Log.w(LOG_TAG, "Could not enter foreground with type $type", e)
            try {
                startForeground(COMMAND_LISTENER_NOTIFICATION_ID, buildNotification())
            } catch (inner: Exception) {
                Log.e(LOG_TAG, "startForeground failed outright", inner)
            }
        }
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

                    // LOCAL-DEVIATION: a nudge sent while this stream was down is simply lost -
                    // the server does not replay it, it only marks the command delivered when the
                    // device fetches policy. So a ring/lock/locate queued while the phone was off
                    // the network used to wait for the next periodic sync, and that backstop is now
                    // an hour rather than five minutes. Reconnecting is exactly the moment to go
                    // and look, and it is also when a lost phone comes back within reach.
                    //
                    // Throttled, because a flaky network reconnects repeatedly and each sync costs
                    // a policy fetch and a status report.
                    val since = SystemClock.elapsedRealtime() - lastStreamSyncAt
                    if (lastStreamSyncAt != 0L && since < STREAM_RECONNECT_SYNC_MIN_GAP_MS) return
                    lastStreamSyncAt = SystemClock.elapsedRealtime()
                    scope.launch {
                        try {
                            performMdmSync(applicationContext)
                        } catch (e: Exception) {
                            Log.w(LOG_TAG, "Sync after stream reconnect failed", e)
                        }
                    }
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
                // Re-armed in a finally: this chain is the only thing scheduling the next run, so
                // anything that throws here would silently end periodic syncing until the service
                // is recreated - indistinguishable from the app "just stopping".
                try {
                    // Picks up the location permission whenever Device-Owner self-grant lands,
                    // which on a fresh device is after this service has already started.
                    promoteForegroundType()
                    scope.launch { performMdmSync(applicationContext) }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Periodic sync tick failed", e)
                } finally {
                    schedulePeriodicSync()
                }
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
    /**
     * LOCAL-DEVIATION: the budget's tick, alive only while the screen is on.
     *
     * Screen time only accrues while the screen is on, so there is nothing for this to do
     * otherwise - and stopping it removes the deep-sleep question entirely instead of trying to
     * work around it. [startScreenTimeTick] is idempotent, which matters because SCREEN_ON and
     * USER_PRESENT both arrive for a single unlock.
     *
     * It re-applies against the *cached* policy, so it needs no network: the point of counting on
     * the device is that a budget survives the server being unreachable.
     */
    private fun startScreenTimeTick() {
        if (stopped || screenTimeTicking) return
        screenTimeTicking = true
        scheduleScreenTimeTick()
    }

    private fun stopScreenTimeTick() {
        screenTimeTicking = false
        handler.removeCallbacks(screenTimeRunnable)
    }

    private val screenTimeRunnable = Runnable {
        // Re-armed in a finally: this chain is the only thing scheduling its own next run, so
        // anything throwing here would silently stop budget enforcement until the next unlock.
        try {
            scope.launch {
                try {
                    ScreenTimeTracker.poll(applicationContext)
                    val cached = cachedPolicy()
                    AppEnforcer.apply(applicationContext, cached)
                    EnforcementScheduler.schedule(applicationContext, cached)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Screen-time tick failed", e)
                }
            }
        } finally {
            scheduleScreenTimeTick()
        }
    }

    private fun scheduleScreenTimeTick() {
        if (stopped || !screenTimeTicking) return
        handler.postDelayed(screenTimeRunnable, SCREEN_TIME_TICK_MS)
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
