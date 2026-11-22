package org.claudevoice.mobile.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException

/**
 * Asks for the notification permission once, at first start (ADR-CVM-003), so the listener's
 * notice can show. Refused, the listener runs anyway with its notice hidden; the line that says
 * so on the screen asks again only when the owner taps it. The app never asks on its own again.
 */
object AskNotifications {
    private const val ASKED = "asked-notifications"
    private const val REQUEST = 1

    fun once(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val asked = File(activity.noBackupFilesDir, ASKED)
        if (asked.exists() || granted(activity)) return
        try {
            asked.createNewFile()
        } catch (_: IOException) {
            // Not remembered: then it may ask once more at a later start, which does no harm.
        }
        (activity.application as App).asks.record(Manifest.permission.POST_NOTIFICATIONS)
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    /** A request from the hidden-notice line; the ask at first start uses [REQUEST]. */
    const val LINE_REQUEST = 2

    /** Asks from the line, written down first, so the access the hub reads knows this install asked. */
    fun fromLine(activity: Activity) {
        (activity.application as App).asks.record(Manifest.permission.POST_NOTIFICATIONS)
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), LINE_REQUEST)
    }

    /** The app's notification settings, or its info page on a phone without that page. */
    fun openSettings(activity: Activity) {
        val pkg = activity.packageName
        val notices = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        try {
            activity.startActivity(notices)
        } catch (_: ActivityNotFoundException) {
            val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            activity.startActivity(info)
        }
    }

    private fun granted(activity: Activity) =
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
