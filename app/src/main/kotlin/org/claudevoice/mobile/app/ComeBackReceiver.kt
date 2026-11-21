package org.claudevoice.mobile.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.claudevoice.mobile.core.ComeBack

/**
 * Starts the listener again after the phone restarts or the app is updated (ADR-CVM-003). Both
 * broadcasts let an app start a foreground service from the background, and only for a named,
 * paired phone, as the core's [ComeBack] decides. `BOOT_COMPLETED` comes after the first unlock,
 * when the hub's key can be read.
 */
class ComeBackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext as App
        if (ComeBack.afterRestart(app.machine.name, app.hub)) ListenerService.start(context)
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
