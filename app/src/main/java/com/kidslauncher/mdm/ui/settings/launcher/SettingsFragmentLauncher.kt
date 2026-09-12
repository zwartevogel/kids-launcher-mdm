package com.kidslauncher.mdm.ui.settings.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.copyToClipboard
import com.kidslauncher.mdm.getDeviceInfo
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.UnifiedPushRegistrationReceiver
import com.kidslauncher.mdm.server.UnifiedPushRelay
import com.kidslauncher.mdm.server.applyProvisioningExtras
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.createMdmApi
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import com.kidslauncher.mdm.server.performJournalSync
import com.kidslauncher.mdm.server.performMdmSync
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.LegalInfoActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SettingsFragmentLauncher"

/**
 * The [SettingsFragmentLauncher] holds all of the app's settings on a single screen.
 */
class SettingsFragmentLauncher : PreferenceFragmentCompat() {

    // Must be registered unconditionally before the fragment reaches CREATED - registering this
    // lazily inside a click listener (e.g. only when the "Scan setup QR" preference is tapped)
    // throws, per the Activity Result API's own contract.
    private val scanSetupQrLauncher = registerForActivityResult(ScanContract()) { result ->
        handleSetupQrScanResult(result)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val hiddenApps = findPreference<Preference>(
            LauncherPreferences.apps().keys().hidden()
        )
        hiddenApps?.setOnPreferenceClickListener {
            openAppsList(requireContext(), hidden = true)
            true
        }

        val licenses = findPreference<Preference>("settings_meta_licenses")
        licenses?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), LegalInfoActivity::class.java))
            true
        }

        val version = findPreference<Preference>("settings_meta_version")
        version?.summary = BuildConfig.VERSION_NAME
        version?.setOnPreferenceClickListener {
            copyToClipboard(requireContext(), getDeviceInfo(requireContext()))
            true
        }

        val mdm = LauncherPreferences.mdm()

        val serverUrl = findPreference<Preference>(mdm.keys().serverUrl())
        serverUrl?.summary = mdm.serverUrl().orNotSet()
        serverUrl?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.settings_mdm_server_url),
                mdm.serverUrl()
            ) { value ->
                mdm.serverUrl(value)
                serverUrl.summary = value.orNotSet()
            }
            true
        }

        val scanSetupQr = findPreference<Preference>("settings_mdm_scan_setup_qr")
        scanSetupQr?.setOnPreferenceClickListener {
            launchSetupQrScanner()
            true
        }

        val enrollNow = findPreference<Preference>("settings_mdm_enroll_now")
        enrollNow?.setOnPreferenceClickListener {
            showEditTextDialog(
                requireContext(),
                getString(R.string.dialog_enrollment_code_title),
                currentValue = null,
            ) { code ->
                enrollWithServer(requireContext(), code)
            }
            true
        }

        val syncNow = findPreference<Preference>("settings_mdm_sync_now")
        syncNow?.setOnPreferenceClickListener {
            syncNowWithServer(requireContext())
            true
        }

        val restrictionsPaused = findPreference<Preference>(mdm.keys().restrictionsPaused())
        restrictionsPaused?.setOnPreferenceChangeListener { _, _ ->
            // The Preference framework persists the new value right after this listener returns
            // true, synchronously within the same click - posting defers just past that write so
            // AppEnforcer.apply()/reevaluateLockReasonFromCache() (which both re-read the
            // preference themselves, rather than taking it as a parameter) see the new value.
            // No network call: this is what makes the toggle take effect immediately instead of
            // only on the next sync (which is what made it look broken while testing offline).
            //
            // The posted block itself must stay tiny and hand off to a background coroutine
            // rather than call AppEnforcer.apply() directly - confirmed live this froze the UI
            // thread for several seconds and triggered an ANR/force-close once apply() started
            // (re)starting KidVpnService, whose onCreate() does a synchronous disk read
            // (DnsFilterEngine.loadFromDisk) that's too slow for the main thread. AppEnforcer.apply()
            // was never actually cheap either (a DevicePolicyManager Binder call per changed
            // package), it just hadn't been reached by a slow enough operation to notice before.
            val context = requireContext()
            view?.post {
                CoroutineScope(Dispatchers.IO).launch {
                    AppEnforcer.apply(context, cachedPolicy())
                    reevaluateLockReasonFromCache()
                }
            }
            true
        }

        val unifiedPushEnabled =
            findPreference<Preference>(mdm.keys().unifiedpushDistributorEnabled())
        unifiedPushEnabled?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            val context = requireContext()
            // The receiver's manifest declaration is exported unconditionally (see its own doc
            // comment on why), but this component-enabled flip is a second, independent gate: a
            // parent who's never touched this toggle should never have their phone silently
            // discoverable as a UnifiedPush distributor. DONT_KILL_APP since flipping this off
            // shouldn't restart the whole launcher process.
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, UnifiedPushRegistrationReceiver::class.java),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
            if (enabled) {
                UnifiedPushRelay.start(context.applicationContext)
            } else {
                UnifiedPushRelay.stop()
            }
            true
        }
    }

    private fun String?.orNotSet(): String = this?.takeIf { it.isNotBlank() }
        ?: getString(R.string.settings_mdm_not_set)

    /**
     * [androidx.preference.EditTextPreference]'s built-in dialog doesn't pick up this app's
     * custom (dark) theme - it renders with invisible text/buttons. Uses the same themed
     * AlertDialog approach as the app-rename dialog instead.
     */
    private fun showEditTextDialog(
        context: Context,
        title: String,
        currentValue: String?,
        onSave: (String) -> Unit,
    ) {
        val dialog = AlertDialog.Builder(context, R.style.AlertDialogCustom).apply {
            setTitle(title)
            setView(R.layout.dialog_edit_text)
            setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            setPositiveButton(android.R.string.ok) { d, _ ->
                val input = (d as? AlertDialog)?.findViewById<EditText>(R.id.dialog_edit_text_input)
                onSave(input?.text?.toString().orEmpty())
            }
        }.create()
        dialog.show()
        dialog.findViewById<EditText>(R.id.dialog_edit_text_input)?.setText(currentValue)
    }

    /**
     * Dev-testing shortcut: enrolls directly against the server URL typed into the preference
     * field above and the one-shot code shown on the admin site, over the local network, without
     * needing the full factory-reset -> scan-QR provisioning flow. Only touches the server's
     * enroll endpoint - it doesn't grant Device Owner (that still needs
     * `adb shell dpm set-device-owner` or real provisioning).
     */
    private fun enrollWithServer(context: Context, enrollmentCode: String) {
        val mdm = LauncherPreferences.mdm()
        val serverUrl = mdm.serverUrl()

        if (serverUrl.isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }
        if (enrollmentCode.isBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_code, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val outcome = try {
                val response = createMdmApi(serverUrl).enroll(EnrollRequest(enrollmentCode))
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("HTTP ${response.code()}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }

            withContext(Dispatchers.Main) {
                outcome.onSuccess { enrollResponse ->
                    mdm.deviceToken(enrollResponse.deviceToken)
                    mdm.enrolled(true)
                    Toast.makeText(context, R.string.toast_mdm_enroll_success, Toast.LENGTH_LONG)
                        .show()
                }.onFailure { e ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_mdm_enroll_failure, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Launches ZXing's embedded scanner activity for the in-app "Scan setup QR" flow - the
     * GrapheneOS-friendly counterpart to Android's native zero-touch QR provisioning (which has
     * no trigger in that OS's setup wizard at all, see kid-phone-server's `handlers::provisioning`).
     * Only meaningful once Device Owner is already granted some other way (currently
     * `adb shell dpm set-device-owner`) - scanning here never touches Device Owner state itself,
     * only the server URL/enrollment code that would otherwise need typing in by hand across
     * separate preference dialogs.
     */
    private fun launchSetupQrScanner() {
        val context = requireContext()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) {
            // Silent grant, same mechanism/precedent as QuickControls' other self-granted runtime
            // permissions - a kid-phone parent scanning this during setup shouldn't need to
            // navigate a system permission dialog first.
            QuickControls.selfGrantPermission(context, dpm, admin, android.Manifest.permission.CAMERA)
        }
        scanSetupQrLauncher.launch(
            ScanOptions()
                // false told CaptureActivity to dynamically recompute the camera preview's
                // rotation transform on the fly - but this app has no actual rotation handling of
                // its own to match, and letting the transform recalculate against an Activity that
                // in practice never rotates produced a badly skewed preview (a narrow off-center
                // strip with diagonal artifacts, no visible framing rectangle) reported live.
                // Locking to the orientation already in effect at launch sidesteps that
                // recalculation entirely.
                .setOrientationLocked(true)
                .setBeepEnabled(false)
                // Restricting to just QR (the only format this feature ever produces) skips
                // decoding every frame against every other barcode symbology ZXing supports by
                // default - a real, not cosmetic, difference in how fast/reliably a code is
                // recognized, not just a validation nicety.
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.settings_mdm_scan_setup_qr_prompt))
        )
    }

    private fun handleSetupQrScanResult(result: ScanIntentResult) {
        val context = requireContext()
        val contents = result.contents
        if (contents == null) {
            Toast.makeText(context, R.string.toast_mdm_scan_qr_cancelled, Toast.LENGTH_SHORT).show()
            return
        }

        val extras = ProvisioningExtras.fromQrJson(contents)
        if (extras == null) {
            Toast.makeText(context, R.string.toast_mdm_scan_qr_invalid, Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val outcome = applyProvisioningExtras(extras)
            withContext(Dispatchers.Main) {
                outcome.onSuccess {
                    Toast.makeText(context, R.string.toast_mdm_enroll_success, Toast.LENGTH_LONG)
                        .show()
                    // Refresh preference summaries in place - applyProvisioningExtras persisted
                    // new values this screen already read into local vals in onCreatePreferences,
                    // so those closures' captured Preference views need an explicit update rather
                    // than relying on a full screen recreation.
                    val mdm = LauncherPreferences.mdm()
                    findPreference<Preference>(mdm.keys().serverUrl())?.summary = mdm.serverUrl()
                }.onFailure { e ->
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_mdm_enroll_failure, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Dev-testing shortcut: runs the same policy fetch + enforcement cycle
     * [com.kidslauncher.mdm.server.CommandListenerService] runs periodically, immediately - avoids
     * waiting a full cycle per test iteration (e.g. right after changing the allowlist or kiosk
     * setting on the admin site). Also kicks off the journal sync the same way
     * [CommandListenerService] does off its own triggers - own coroutine, not awaited before the
     * toast below, since [performMdmSync]'s return value (whether policy fetch succeeded) is
     * already the more useful "did this reach the server at all" signal, and a slow media upload
     * from the journal sync shouldn't hold up that feedback.
     */
    private fun syncNowWithServer(context: Context) {
        val mdm = LauncherPreferences.mdm()
        if (mdm.serverUrl().isNullOrBlank() || mdm.deviceToken().isNullOrBlank()) {
            Toast.makeText(context, R.string.toast_mdm_enroll_missing_fields, Toast.LENGTH_LONG)
                .show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch { performJournalSync(context) }

        CoroutineScope(Dispatchers.IO).launch {
            val reachedServer = try {
                performMdmSync(context)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Manual sync failed", e)
                false
            }
            withContext(Dispatchers.Main) {
                val messageRes =
                    if (reachedServer) R.string.toast_mdm_sync_done else R.string.toast_mdm_sync_failed
                Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
