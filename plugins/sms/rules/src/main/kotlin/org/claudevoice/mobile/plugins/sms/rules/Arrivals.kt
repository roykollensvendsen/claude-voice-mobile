package org.claudevoice.mobile.plugins.sms.rules

/**
 * The parts one `SMS_RECEIVED` broadcast brings, as the messages they make. A long message comes
 * as several parts from one sender, in order, and is kept as one.
 */
object Arrivals {
    /** One part as the platform gives it: either may be missing. */
    data class Part(val from: String?, val body: String?)

    /** One whole message. */
    data class Message(val from: String, val body: String)

    /** The parts joined: parts from one sender in a row make one message. */
    fun joined(parts: List<Part>): List<Message> {
        val messages = ArrayList<Message>()
        for (part in parts) {
            val from = part.from.orEmpty()
            val last = messages.lastOrNull()
            if (last?.from == from) {
                messages[messages.size - 1] = Message(from, last.body + part.body.orEmpty())
            } else {
                messages += Message(from, part.body.orEmpty())
            }
        }
        return messages
    }
}
