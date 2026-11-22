package org.claudevoice.mobile.capabilities.permissions

import java.io.File

/**
 * Which permissions this install has asked for, by name only (ADR-CVM-011). Every place that asks
 * writes here first, so a permission never written down was never asked for. It lives in the
 * app's no-backup storage, so a new phone starts with nothing asked.
 */
class AskRecord(private val dir: File) {
    private val file = File(dir, FILE)

    /**
     * Writes [permission] down before the app asks for it. A write that fails leaves it unrecorded:
     * the access then says never_asked, whose spoken fix is the same as refused's.
     */
    fun record(permission: String) {
        require(permission.none { it == '\n' || it == '\r' }) { "a permission name is one line" }
        if (asked(permission)) return
        try {
            file.appendText(permission + "\n")
        } catch (_: java.io.IOException) {
            // Unrecorded; see above.
        }
    }

    /** Whether [permission] was written down by an earlier ask. */
    fun asked(permission: String): Boolean =
        try {
            file.isFile && permission in file.readLines()
        } catch (_: java.io.IOException) {
            false
        }

    companion object {
        const val FILE = "asked-permissions"
    }
}
