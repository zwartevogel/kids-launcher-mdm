package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.server.dto.ProvisioningExtras

/**
 * Saves the server URL and enrolls in one shot from a [ProvisioningExtras] - shared by
 * [com.kidslauncher.mdm.server.MdmDeviceAdminReceiver.onProfileProvisioningComplete] (Android's
 * native zero-touch flow) and the in-app QR scanner (for devices where that native flow has no
 * trigger at all), so there is exactly one enroll code path regardless of how Device Owner was
 * granted - by this point it already has been, either way.
 *
 * LOCAL-DEVIATION: upstream also persisted a Tailscale auth key here and blocked for up to 30s
 * bringing the embedded tailnet up before enrolling, because a `*.ts.net` server URL is only
 * resolvable through that tunnel. tsnet is removed in this fork and the server has a public
 * hostname with a valid certificate, so enrollment goes straight out over the normal network and
 * no longer needs a `Context` at all.
 */
suspend fun applyProvisioningExtras(extras: ProvisioningExtras): Result<Unit> {
    val mdm = LauncherPreferences.mdm()
    mdm.serverUrl(extras.serverUrl)

    return try {
        val response = createMdmApi(extras.serverUrl).enroll(EnrollRequest(extras.enrollmentCode))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            mdm.deviceToken(body.deviceToken)
            mdm.enrolled(true)
            Result.success(Unit)
        } else {
            Result.failure(Exception("HTTP ${response.code()}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}
