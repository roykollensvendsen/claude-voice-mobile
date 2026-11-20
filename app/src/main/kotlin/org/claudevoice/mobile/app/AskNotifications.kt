package org.claudevoice.mobile.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Asks for the notification permission once, at first start (ADR-CVM-003), so the listener's
 * notice can show. Refused, the listener runs anyway with its notice hidden; the line that says
 * so on the screen comes with #78. The app never asks on its own again.
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
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST)
    }

    private fun granted(activity: Activity) =
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
