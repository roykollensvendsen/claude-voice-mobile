package org.claudevoice.mobile.core

/**
 * Whether the listener starts by itself when Android has stopped the app: after a restart of the
 * phone, an update of the app, or a permission taken away (ADR-CVM-003). Only a phone whose
 * owner has confirmed its name and that is paired with a hub; any other phone waits for the owner
 * to open the app.
 */
object ComeBack {
    fun afterRestart(name: String?, hub: HubStore): Boolean =
        name != null && KeptGate.Kept.parse(hub.read()) != null
}
