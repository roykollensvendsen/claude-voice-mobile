package org.claudevoice.mobile.capabilities.permissions

import android.content.Context
import android.content.pm.PackageManager

/**
 * What Android allows the app now, and whether this install asked before (ADR-CVM-011). It only
 * reads: it never asks for a permission and never opens a settings page, so building the access
 * the hub reads can never ask the owner anything.
 */
class PermissionStates(private val context: Context, private val record: AskRecord) {
    fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun asked(permission: String): Boolean = record.asked(permission)
}
