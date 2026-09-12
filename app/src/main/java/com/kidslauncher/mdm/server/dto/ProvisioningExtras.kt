package com.kidslauncher.mdm.server.dto

import android.os.PersistableBundle
import org.json.JSONObject

private const val KEY_ADMIN_EXTRAS_BUNDLE = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"

/**
 * LOCAL-DEVIATION: upstream carried a third field here, the Tailscale auth key. tsnet is removed in
 * this fork, so that key is neither parsed nor stored - an older server still sending it in the QR
 * payload is simply ignored, since both readers below pick out named keys rather than requiring an
 * exact shape.
 *
 * The two fields kid-phone-server embeds in every device's setup QR code, under
 * PROVISIONING_ADMIN_EXTRAS_BUNDLE - see that repo's `handlers::provisioning` for the server
 * side. Read two different ways depending on how provisioning happened: [fromAdminExtrasBundle]
 * for Android's native zero-touch flow (delivered via
 * [android.app.admin.DeviceAdminReceiver.onProfileProvisioningComplete]'s own PersistableBundle
 * extra), [fromQrJson] for the launcher's own in-app QR scanner - used when Device Owner was
 * granted some other way (currently only `adb shell dpm set-device-owner`, on devices like
 * GrapheneOS where the native flow has no trigger in the setup wizard at all).
 */
data class ProvisioningExtras(
    val serverUrl: String,
    val enrollmentCode: String,
) {
    companion object {
        fun fromAdminExtrasBundle(bundle: PersistableBundle?): ProvisioningExtras? {
            bundle ?: return null
            val serverUrl = bundle.getString("server_url").orEmpty()
            val enrollmentCode = bundle.getString("enrollment_code").orEmpty()
            if (serverUrl.isBlank() || enrollmentCode.isBlank()) return null
            return ProvisioningExtras(
                serverUrl = serverUrl,
                enrollmentCode = enrollmentCode,
            )
        }

        /**
         * Parses the *whole* QR payload, not just the admin-extras object - the same QR Android's
         * native provisioning flow consumes also carries the top-level PROVISIONING_* keys this
         * in-app scanner has no use for (admin component, signature checksum, download location,
         * WiFi), so this pulls out only the nested bundle. Returns null on anything that isn't
         * this server's provisioning JSON at all (e.g. an unrelated QR code), rather than
         * throwing - the scanner screen treats null as "not a setup code" and keeps scanning.
         */
        fun fromQrJson(json: String): ProvisioningExtras? {
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val extras = root.optJSONObject(KEY_ADMIN_EXTRAS_BUNDLE) ?: return null
            val serverUrl = extras.optString("server_url")
            val enrollmentCode = extras.optString("enrollment_code")
            if (serverUrl.isBlank() || enrollmentCode.isBlank()) return null
            return ProvisioningExtras(
                serverUrl = serverUrl,
                enrollmentCode = enrollmentCode,
            )
        }
    }
}
