package com.kidslauncher.mdm

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kidslauncher.mdm.ui.EXTRA_CRASH_LOG
import com.kidslauncher.mdm.ui.ReportCrashActivity
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.random.Random

private val NOTIFICATION_CHANNEL_CRASH = "launcher:crash"
val NOTIFICATION_CHANNEL_RING = "launcher:ring"
const val RING_NOTIFICATION_ID = 1001
val NOTIFICATION_CHANNEL_LISTENER = "launcher:command_listener"
const val COMMAND_LISTENER_NOTIFICATION_ID = 1002
val NOTIFICATION_CHANNEL_APP_INSTALL = "launcher:app_install"
private const val APP_INSTALL_NOTIFICATION_ID_BASE = 2000

fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_CRASH,
                context.getString(R.string.notification_channel_crash),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        // HIGH importance + own channel so this reliably heads-up/appears even over the lock
        // screen while Find My Device's ring is playing - the whole point is to give the kid an
        // obvious, immediate way to silence it once they unlock the device, not something that
        // silently sits in the shade.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_RING,
                context.getString(R.string.notification_channel_ring),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        // MIN importance, silent - this is the mandatory persistent notification for
        // CommandListenerService's foreground service (Android requires one for any foreground
        // service, no way around it), not something meant to draw attention the way the ring
        // channel above deliberately does.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_LISTENER,
                context.getString(R.string.notification_channel_listener),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        // LOW importance, not MIN - unlike the two foreground-service channels above, this one is
        // meant to actually be seen (a parent glancing at the shade should be able to tell an app
        // install/update - including the launcher's own silent self-update, which otherwise "just
        // happens" with zero visible indication - is in progress), just without sound/heads-up
        // interruption for something this routine.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_APP_INSTALL,
                context.getString(R.string.notification_channel_app_install),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}

/** Shown while [MdmSyncWorker] downloads+installs one tracked app's update (including the
 * launcher's own self-update) - cancelled by [notifyAppInstallResult] once the real result is
 * known. One notification per app (keyed by [appId], the server's stable tracked-app id) so
 * several updates queued in the same sync cycle don't clobber each other's progress notification. */
fun notifyAppInstalling(context: Context, appId: Long, appName: String) {
    val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_APP_INSTALL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(context.getString(R.string.notification_app_installing_title, appName))
        .setOngoing(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    try {
        NotificationManagerCompat.from(context).notify(appInstallNotificationId(appId), builder.build())
    } catch (e: SecurityException) {
        Log.w("Notifications", "Could not show app-install notification for $appName", e)
    }
}

/** On success, just cancels the ongoing "Installing..." notification - it disappearing is enough
 * signal, and a lingering "Installed" toast isn't worth the extra notification. On failure, swaps
 * it for a dismissible one, since a silently-failed background install/update is exactly the kind
 * of thing worth surfacing (same reasoning as this channel's own doc comment above). */
fun notifyAppInstallResult(context: Context, appId: Long, appName: String, success: Boolean) {
    val notificationManager = NotificationManagerCompat.from(context)
    val id = appInstallNotificationId(appId)
    if (success) {
        try {
            notificationManager.cancel(id)
        } catch (e: SecurityException) {
            // Nothing to clean up if we can't reach the notification manager anyway.
        }
        return
    }

    val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_APP_INSTALL)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(context.getString(R.string.notification_app_install_failed_title, appName))
        .setOngoing(false)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    try {
        notificationManager.notify(id, builder.build())
    } catch (e: SecurityException) {
        Log.w("Notifications", "Could not show app-install-failed notification for $appName", e)
    }
}

private fun appInstallNotificationId(appId: Long): Int = APP_INSTALL_NOTIFICATION_ID_BASE + appId.toInt()

fun requestNotificationPermission(activity: Activity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val permission =
        (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    if (!permission) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            1
        )
    }
}

fun sendCrashNotification(context: Context, throwable: Throwable) {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter)
    throwable.printStackTrace(printWriter)

    val intent = Intent(context, ReportCrashActivity::class.java)
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    intent.putExtra(EXTRA_CRASH_LOG, stringWriter.toString())

    val pendingIntent = PendingIntent.getActivity(
        context,
        Random.nextInt(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_CRASH)
        .setSmallIcon(R.drawable.baseline_bug_report_24)
        .setContentTitle(context.getString(R.string.notification_crash_title))
        .setContentText(context.getString(R.string.notification_crash_explanation))
        .setContentIntent(pendingIntent)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    val notificationManager = NotificationManagerCompat.from(context)
    try {
        notificationManager.notify(
            0,
            builder.build()
        )
    } catch (e: SecurityException) {
        Log.e("Crash Notification", "Could not send notification")
    }
}
