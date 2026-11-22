package org.claudevoice.mobile.core

/**
 * What Android allows this app now, as the app reads it (ADR-CVM-011). Reading never asks for a
 * permission and never opens a settings page.
 */
interface Permissions {
    /** Whether Android grants [permission] now. */
    fun granted(permission: String): Boolean

    /** Whether this install has asked for [permission] before, by the app's own record. */
    fun asked(permission: String): Boolean
}

/**
 * Whether Android allows a plugin's use now, as the `access` value the hub reads: "granted",
 * "refused" or "never_asked" (ADR-CVM-011). Only the state travels, never anything read with it.
 */
object Access {
    const val GRANTED = "granted"
    const val REFUSED = "refused"
    const val NEVER_ASKED = "never_asked"

    /**
     * Granted only when every permission in [needs] is. Else refused when one not granted was
     * asked for before, and never_asked when none of those was.
     */
    fun of(needs: List<String>, permissions: Permissions): String {
        // A plain loop: the standard library's inlined any() leaves a mutant no test can tell apart.
        var answer = GRANTED
        for (permission in needs) {
            if (permissions.granted(permission)) continue
            if (permissions.asked(permission)) return REFUSED
            answer = NEVER_ASKED
        }
        return answer
    }
}
