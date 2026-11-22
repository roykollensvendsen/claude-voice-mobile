package org.claudevoice.mobile.core

/** A phone where Android grants everything, for tests that are not about permissions. */
object GrantedAll : Permissions {
    override fun granted(permission: String) = true

    override fun asked(permission: String) = false
}
