package org.claudevoice.mobile.plugins.sms.rules

/**
 * One of a thing for the whole process, made the first time it is asked for. The receiver and
 * the tool each ask for the inbox, and must get the same one: one lock, one file.
 */
class Shared<T : Any> {
    private var held: T? = null

    @Synchronized
    fun get(make: () -> T): T {
        val made = held ?: make()
        held = made
        return made
    }
}
