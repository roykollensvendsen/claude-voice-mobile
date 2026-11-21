package org.claudevoice.mobile.core

/**
 * Whether the listener starts by itself when Android has stopped the app: after a restart of the
 * phone, an update of the app, or a permission taken away (ADR-CVM-003). Only a phone whose
 * owner has confirmed its name and that is paired with a hub; any other phone waits for the owner
 * to open the app.
 */
object ComeBack {
    /** Whether broadcast [action] starts the listener: only a restart after unlock, or an update. */
    fun starts(action: String?, name: String?, hub: HubStore): Boolean =
        action in ACTIONS && name != null && KeptGate.Kept.parse(hub.read()) != null

    /** Not the locked boot: the hub's key opens only after the first unlock. */
    private val ACTIONS = setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED")
}
