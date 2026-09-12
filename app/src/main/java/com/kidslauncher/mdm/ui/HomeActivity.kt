package com.kidslauncher.mdm.ui

import android.app.ActivityManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.databinding.ActivityHomeBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.requestNotificationPermission
import com.kidslauncher.mdm.setDefaultHomeScreen
import com.kidslauncher.mdm.ui.minimalist.MinimalistHomeAdapter
import com.kidslauncher.mdm.ui.quickcontrols.QuickControlsActivity
import kotlin.math.abs

private const val SWIPE_UP_MIN_DISTANCE = 100
private const val SWIPE_UP_MIN_VELOCITY = 100
private const val SWIPE_LEFT_MIN_DISTANCE = 100
private const val SWIPE_LEFT_MIN_VELOCITY = 100
private const val LOCK_REASON_REFRESH_INTERVAL_MS = 60_000L

/**
 * [HomeActivity] is the actual application launcher.
 * It shows a fixed list of chosen apps; swiping up opens the full app drawer.
 */
class HomeActivity : UIObjectActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var minimalistAdapter: MinimalistHomeAdapter
    private lateinit var gestureDetector: GestureDetector

    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("display.") == true) {
                recreate()
            } else if (prefKey == LauncherPreferences.mdm().keys().lockReason()) {
                redirectToLockScreenIfLocked()
            } else if (prefKey == LauncherPreferences.mdm().keys().kioskEnabled()) {
                reconcileKioskMode()
            } else {
                // covers minimalist. (added/removed), apps.hidden (hidden while shown here)
                // and apps.custom_names (renamed) - all of which can change via the
                // home screen's own long-press menu.
                minimalistAdapter.updateAppsList()
            }
        }

    private val refreshHandler = Handler(Looper.getMainLooper())

    // Re-checks the schedule against the device's own clock every minute while the home screen is
    // visible, so a window closing while someone's just sitting idle on the home screen locks
    // promptly instead of waiting for the next ~15-minute background sync.
    private val refreshRunnable = object : Runnable {
        override fun run() {
            reevaluateLockReasonFromCache()
            refreshHandler.postDelayed(this, LOCK_REASON_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        minimalistAdapter = MinimalistHomeAdapter(this)
        binding.homeMinimalistList.layoutManager = LinearLayoutManager(this)
        binding.homeMinimalistList.adapter = minimalistAdapter

        // Back does nothing on the home screen, same as stock Android launchers.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (abs(diffY) > abs(diffX) &&
                    -diffY > SWIPE_UP_MIN_DISTANCE &&
                    abs(velocityY) > SWIPE_UP_MIN_VELOCITY
                ) {
                    openAppsList(this@HomeActivity, excludePinned = true)
                    return true
                }
                // The kid-facing replacement for Android's Quick Settings shade - see
                // QuickControlsActivity's doc comment for why this screen exists at all instead
                // of just using the real one.
                if (abs(diffX) > abs(diffY) &&
                    -diffX > SWIPE_LEFT_MIN_DISTANCE &&
                    abs(velocityX) > SWIPE_LEFT_MIN_VELOCITY
                ) {
                    startActivity(Intent(this@HomeActivity, QuickControlsActivity::class.java))
                    return true
                }
                return false
            }
        })

        // The Activity-level onTouchEvent() below only ever sees touches nobody else claimed -
        // it's a last resort, called only if the whole view hierarchy declines an event. The
        // minimalist list's row items are match_parent-width and clickable, so a swipe starting
        // on top of one (as opposed to the blank space above/below the short, wrap_content-height
        // list - see activity_home.xml) gets consumed entirely by that row's own click handling
        // and never reaches onTouchEvent() at all. RecyclerView.OnItemTouchListener is the
        // official hook for exactly this: it's invoked for every event that flows through the
        // RecyclerView, before it's dispatched to a child row. Always returning false here means
        // it's purely observing (not stealing the gesture from clicks/scrolling) - a real drag
        // already exceeds the framework's own touch-slop threshold, which independently cancels a
        // pending click on the row without any help from this listener.
        binding.homeMinimalistList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onStart() {
        super.onStart()

        // First launch: mark it done and try to set the default home screen
        if (!LauncherPreferences.internal().started()) {
            LauncherPreferences.internal().started(true)
            LauncherPreferences.internal().startedTime(System.currentTimeMillis() / 1000L)
            setDefaultHomeScreen(this, checkDefault = true)
            requestNotificationPermission(this)
        }

        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        refreshHandler.post(refreshRunnable)
    }

    override fun onResume() {
        super.onResume()
        // LOCAL-DEVIATION: upstream started its embedded tsnet connection here (deliberately not
        // from Application.onCreate(), to avoid a native-crash surface racing the first UI paint).
        // tsnet is removed in this fork, so there is nothing to start and that whole crash surface
        // is gone with it.
        // Fresh check against the clock every time the home screen comes to the foreground, not
        // just on the 60-second timer - covers e.g. the device having been asleep since the last
        // tick.
        reevaluateLockReasonFromCache()
        // Must run before the lock-screen check below: while the bedtime/screen-time block is
        // showing is exactly when kiosk pinning should also be engaged, so the kid can't use
        // recents/home/notification-shade to route around LockActivity.
        reconcileKioskMode()
        // Checked here (not just via the preference listener) so pressing Home while the lock
        // screen is showing can't be used to bounce back into the drawer/home list underneath it.
        if (redirectToLockScreenIfLocked()) return
        minimalistAdapter.updateAppsList()
    }

    /** @return true if currently locked (and [LockActivity] was launched). */
    private fun redirectToLockScreenIfLocked(): Boolean {
        if (LauncherPreferences.mdm().lockReason() != LockReason.NONE) {
            LockActivity.start(this)
            return true
        }
        return false
    }

    /**
     * Entering lock-task mode is never automatic on the OS side (only removing the pinned
     * package from DevicePolicyManager.setLockTaskPackages() auto-exits it) - AppEnforcer only
     * configures the DPM-side state from a background Worker, so an Activity has to actually
     * call startLockTask()/stopLockTask() to enter/exit. Runs on every onResume() since neither
     * the pinned state nor this check survives reboot/process death on their own.
     */
    private fun reconcileKioskMode() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val currentlyLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            activityManager.isInLockTaskMode
        }
        val shouldBeLocked = LauncherPreferences.mdm().kioskEnabled()

        if (shouldBeLocked && !currentlyLocked) {
            startLockTask()
        } else if (!shouldBeLocked && currentlyLocked) {
            stopLockTask()
        }
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        minimalistAdapter.destroy()
        super.onDestroy()
    }

    override fun isHomeScreen(): Boolean {
        return true
    }
}
