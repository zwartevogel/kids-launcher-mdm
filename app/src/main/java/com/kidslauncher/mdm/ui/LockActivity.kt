package com.kidslauncher.mdm.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityLockBinding
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.performMdmSync
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.preferences.LauncherPreferences
import androidx.lifecycle.lifecycleScope
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOCK_REASON_REFRESH_INTERVAL_MS = 60_000L

/**
 * Full-screen block shown while [LockReason] (from [com.kidslauncher.mdm.server.MdmSyncWorker])
 * is anything other than [LockReason.NONE]. No way to dismiss it besides the lock actually
 * clearing on its own, or the visible "Enter unlock code" button - a deliberately undisguised
 * entry point for [OfflineOverride], since the PIN itself is the actual security boundary here,
 * not the button being hard to find.
 */
class LockActivity : UIObjectActivity() {
    private lateinit var binding: ActivityLockBinding

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey == LauncherPreferences.mdm().keys().lockReason()) {
                finishIfUnlocked()
            }
        }

    private val refreshHandler = Handler(Looper.getMainLooper())

    // Re-checks the schedule against the device's own clock every minute while this screen is
    // showing - otherwise the lock would only ever clear whenever the next ~15-minute background
    // sync happens to land, which could leave someone stuck well after their allowed time began.
    private val refreshRunnable = object : Runnable {
        override fun run() {
            reevaluateLockReasonFromCache()
            refreshHandler.postDelayed(this, LOCK_REASON_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        binding.lockUnlockCodeButton.setOnClickListener { showUnlockCodeDialog() }
        binding.lockSyncButton.setOnClickListener { syncNow() }
    }

    private fun showUnlockCodeDialog() {
        if (!OfflineOverride.isConfigured()) {
            Toast.makeText(this, R.string.lock_unlock_code_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        if (OfflineOverride.isLockedOut()) {
            Toast.makeText(this, R.string.lock_unlock_code_locked_out, Toast.LENGTH_LONG).show()
            return
        }

        val dialog = AlertDialog.Builder(this, R.style.AlertDialogCustom).apply {
            setTitle(R.string.lock_unlock_code_dialog_title)
            setView(R.layout.dialog_offline_override_pin)
            setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            setPositiveButton(android.R.string.ok, null)
        }.create()
        dialog.show()

        // Overriding the positive button's listener after show() (rather than in the builder)
        // keeps the dialog open on a wrong code instead of dismissing - the whole point of a
        // failsafe is not making the parent re-open the dialog and re-type everything after one
        // typo.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val input = dialog.findViewById<EditText>(R.id.dialog_offline_override_pin_input)
            val pin = input?.text?.toString().orEmpty()
            if (OfflineOverride.verifyPin(pin)) {
                OfflineOverride.activate(this)
                dialog.dismiss()
                finish()
            } else {
                Toast.makeText(this, R.string.lock_unlock_code_wrong, Toast.LENGTH_SHORT).show()
                input?.text?.clear()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        refreshHandler.post(refreshRunnable)
        updateMessageOrFinish()
        updateLastSync()
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onStop()
    }

    private fun finishIfUnlocked() {
        if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) {
            finish()
        } else {
            updateMessageOrFinish()
        }
    }

    /**
     * LOCAL-DEVIATION: lets the child pull fresh policy without the admin PIN.
     *
     * The existing "Sync now" lives in Settings, which is precisely where a kid cannot go - so
     * after a parent granted extra time there was no way to make the phone notice it short of
     * waiting out a cycle, while the child stares at a screen saying the app is blocked.
     *
     * Safe to expose: a sync only ever *fetches* policy, so the worst a child can do by pressing
     * it repeatedly is apply the parent's rules sooner. [performMdmSync] serialises on its own
     * mutex, so a double tap cannot overlap with the periodic cycle either.
     */
    private fun syncNow() {
        binding.lockSyncButton.isEnabled = false
        binding.lockLastSync.setText(R.string.lock_sync_checking)
        lifecycleScope.launch {
            val reached = withContext(Dispatchers.IO) { performMdmSync(applicationContext) }
            binding.lockSyncButton.isEnabled = true
            if (reached) {
                // The sync may have lifted the lock entirely, in which case this activity should
                // disappear rather than report success at a child who can already tell.
                updateLastSync()
                finishIfUnlocked()
            } else {
                binding.lockLastSync.setText(R.string.lock_sync_failed)
            }
        }
    }

    private fun updateLastSync() {
        val at = LauncherPreferences.mdm().lastSyncAt()
        binding.lockLastSync.text = if (at <= 0L) {
            getString(R.string.lock_last_sync_never)
        } else {
            // Device locale and timezone, which is what a child reads the clock in.
            getString(
                R.string.lock_last_sync,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)),
            )
        }
    }

    private fun updateMessageOrFinish() {
        when (LauncherPreferences.mdm().lockReason()) {
            LockReason.BEDTIME -> binding.lockMessage.setText(R.string.lock_reason_bedtime)
            LockReason.SCREEN_TIME -> binding.lockMessage.setText(R.string.lock_reason_screen_time)
            LockReason.NONE -> finish()
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, LockActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
